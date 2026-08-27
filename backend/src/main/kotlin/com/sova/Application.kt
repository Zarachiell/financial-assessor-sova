package com.sova

import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.financialLocalTools
import com.sova.ai.application.AiProvider
import com.sova.ai.infrastructure.gemini.GeminiAiProvider
import com.sova.audio.application.AudioProcessingConfig
import com.sova.audio.application.AudioProcessingWorker
import com.sova.audio.application.ProcessAudioMessage
import com.sova.audio.infrastructure.gemini.GeminiSpeechToTextProvider
import com.sova.audio.infrastructure.persistence.PostgresAudioProcessingRepository
import com.sova.conversation.application.AiConversationOrchestrator
import com.sova.conversation.application.FamilyRole
import com.sova.conversation.infrastructure.persistence.PostgresConversationHistory
import com.sova.delivery.http.WhatsappHttpDependencies
import com.sova.delivery.http.configureHttp
import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.domain.FinancialContext
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.finance.insights.application.FinancialInsightsApplicationService
import com.sova.finance.insights.infrastructure.persistence.PostgresFinancialInsightsRepository
import com.sova.shared.config.AppConfig
import com.sova.shared.config.AiConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HandleWhatsappMessage
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import com.sova.whatsapp.infrastructure.twilio.TwilioWebhookSignatureValidator
import com.sova.whatsapp.infrastructure.twilio.TwilioWhatsappMediaDownloader
import com.sova.whatsapp.infrastructure.twilio.TwilioWhatsappMessageSender
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

fun main() {
    val config = AppConfig.fromEnvironment()
    val database = PostgresDatabase(config.database)

    database.migrate()
    val whatsappRepository = PostgresWhatsappRepository(database)
    runBlocking {
        whatsappRepository.provision(
            HouseholdProvisioning(
                name = config.household.name,
                users = config.household.users.map { user ->
                    ProvisionedWhatsappUser(user.name, WhatsappAddress(user.whatsappAddress))
                },
            ),
        )
    }
    val financialService = FinancialApplicationService(
        repository = PostgresFinancialRepository(database),
        maxProjectionMonths = config.cashFlow.maxProjectionMonths,
    )
    val financialInsightsService = FinancialInsightsApplicationService(
        repository = PostgresFinancialInsightsRepository(database),
    )
    val authorizedUsers = runBlocking {
        config.household.users.map { configuredUser ->
            whatsappRepository.findActiveUser(WhatsappAddress(configuredUser.whatsappAddress))
                ?: error("Configured WhatsApp user was not provisioned")
        }
    }
    val catalogOwner = authorizedUsers.first()
    runBlocking {
        DefaultFinancialCatalogProvisioner(financialService).provision(
            FinancialContext(catalogOwner.householdId, catalogOwner.userId),
        )
    }

    val senderAddress = WhatsappAddress(config.twilio.whatsappSender)
    createAiProvider(config.ai).use { provider ->
        val toolRegistry = LocalToolRegistry(financialLocalTools(financialService, financialInsightsService))
        val conversation = AiConversationOrchestrator(
            provider = provider,
            registry = toolRegistry,
            dispatcher = LocalToolDispatcher(toolRegistry),
            history = PostgresConversationHistory(database),
            familyRolesByUserId = mapOf(
                authorizedUsers[0].userId to FamilyRole.FATHER,
                authorizedUsers[1].userId to FamilyRole.MOTHER,
            ),
            maxToolRounds = config.ai.maxToolIterations,
        )
        val sender = TwilioWhatsappMessageSender(senderAddress, config.twilio)
        val audioProcessingConfig = AudioProcessingConfig(
            maxAudioBytes = config.audio.maxAudioBytes,
            maxAudioDuration = Duration.ofMinutes(config.audio.maxAudioDurationMinutes.toLong()),
            maxAttempts = config.audio.maxAttempts,
        )
        TwilioWhatsappMediaDownloader(config.twilio, config.audio.maxAudioBytes).use { downloader ->
            GeminiSpeechToTextProvider(
                project = config.ai.settings.project,
                location = config.ai.settings.location,
                model = config.audio.model,
            ).use { speechToText ->
                val worker = AudioProcessingWorker(
                    processor = ProcessAudioMessage(
                        repository = PostgresAudioProcessingRepository(database),
                        whatsappRepository = whatsappRepository,
                        downloader = downloader,
                        speechToText = speechToText,
                        conversation = conversation,
                        senderAddress = senderAddress,
                        sender = sender,
                        config = audioProcessingConfig,
                    ),
                    pollInterval = audioProcessingConfig.pollInterval,
                )
                val whatsapp = WhatsappHttpDependencies(
                    expectedAccountSid = config.twilio.accountSid,
                    expectedSenderAddress = senderAddress,
                    signatureValidator = TwilioWebhookSignatureValidator(config.twilio.authToken),
                    handler = HandleWhatsappMessage(
                        repository = whatsappRepository,
                        senderAddress = senderAddress,
                        sender = sender,
                        conversation = conversation,
                    ),
                )
                worker.start()
                try {
                    embeddedServer(
                        factory = Netty,
                        host = config.http.host,
                        port = config.http.port,
                    ) {
                        module(database, whatsapp)
                    }.start(wait = true)
                } finally {
                    worker.close()
                }
            }
        }
    }
}

private fun createAiProvider(config: AiConfig): AiProvider {
    val settings = config.settings
    val credentialsPath = Path.of(settings.credentialsPath)
    require(Files.isRegularFile(credentialsPath) && Files.isReadable(credentialsPath)) {
        "Google application credentials must be a readable regular file"
    }
    return GeminiAiProvider(settings.project, settings.location, settings.model)
}

fun Application.module(
    database: DatabaseHealth,
    whatsapp: WhatsappHttpDependencies? = null,
) {
    configureHttp(database, whatsapp)
}
