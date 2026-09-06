package com.sova

import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.financialLocalTools
import com.sova.ai.application.receiptLocalTools
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
import com.sova.delivery.http.ReceiptMediaHttpDependencies
import com.sova.delivery.http.configureHttp
import com.sova.delivery.http.DashboardHttpDependencies
import com.sova.dashboard.application.DashboardQueryService
import com.sova.dashboard.application.DashboardSessionAuthenticator
import com.sova.dashboard.application.DashboardUser
import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.DefaultFinancialCatalog
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.domain.FinancialContext
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.finance.insights.application.FinancialInsightsApplicationService
import com.sova.finance.insights.infrastructure.persistence.PostgresFinancialInsightsRepository
import com.sova.image.application.ImageProcessingConfig
import com.sova.image.application.ImageProcessingWorker
import com.sova.image.application.ImageCandidateService
import com.sova.image.application.ProcessImageMessage
import com.sova.image.infrastructure.gemini.GeminiImageAnalysisProvider
import com.sova.image.infrastructure.persistence.PostgresImageProcessingRepository
import com.sova.image.infrastructure.persistence.PostgresImageCandidateRepository
import com.sova.media.infrastructure.LocalMediaStorage
import com.sova.receipt.application.ReceiptAccessLinkSigner
import com.sova.receipt.application.ReceiptMediaAccess
import com.sova.receipt.application.SettlementReceiptService
import com.sova.receipt.infrastructure.persistence.PostgresSettlementReceiptRepository
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
        val imageCandidateService = ImageCandidateService(PostgresImageCandidateRepository(database))
        val mediaStorage = LocalMediaStorage(Path.of(config.image.storagePath))
        val settlementReceiptRepository = PostgresSettlementReceiptRepository(database)
        val settlementReceiptService = SettlementReceiptService(settlementReceiptRepository)
        val receiptLinkSigner = ReceiptAccessLinkSigner(
            config.image.publicBaseUrl,
            config.image.signingSecret,
        )
        val dashboard = config.dashboard.userPasswords?.let { passwords ->
            val sessionDuration = Duration.ofHours(config.dashboard.sessionDurationHours.toLong())
            DashboardHttpDependencies(
                authenticator = DashboardSessionAuthenticator(
                    users = authorizedUsers.mapIndexed { index, user ->
                        DashboardUser(
                            name = config.household.users[index].name,
                            context = FinancialContext(user.householdId, user.userId),
                            password = passwords[index],
                        )
                    },
                    signingSecret = requireNotNull(config.dashboard.sessionSigningKey),
                    sessionDuration = sessionDuration,
                ),
                queries = DashboardQueryService(financialService),
                financialService = financialService,
                sessionDuration = sessionDuration,
                secureCookies = config.image.publicBaseUrl.startsWith("https://", ignoreCase = true),
                receiptService = settlementReceiptService,
                receiptLinkSigner = receiptLinkSigner,
            )
        }
        val toolRegistry = LocalToolRegistry(
            financialLocalTools(financialService, financialInsightsService, imageCandidateService) +
                receiptLocalTools(settlementReceiptService, receiptLinkSigner),
        )
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
        TwilioWhatsappMediaDownloader(config.twilio, maxOf(config.audio.maxAudioBytes, config.image.maxImageBytes)).use { downloader ->
            GeminiSpeechToTextProvider(
                project = config.ai.settings.project,
                location = config.ai.settings.location,
                model = config.audio.model,
            ).use { speechToText ->
                GeminiImageAnalysisProvider(
                    project = config.ai.settings.project,
                    location = config.ai.settings.location,
                    model = config.image.model,
                ).use { imageAnalyzer ->
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
                val imageProcessingConfig = ImageProcessingConfig(
                    maxImageBytes = config.image.maxImageBytes,
                    maxAttempts = config.image.maxAttempts,
                    minimumAutoConfidence = config.image.minimumAutoConfidence,
                )
                val imageWorker = ImageProcessingWorker(
                    processor = ProcessImageMessage(
                        repository = PostgresImageProcessingRepository(database),
                        whatsappRepository = whatsappRepository,
                        downloader = downloader,
                        storage = mediaStorage,
                        analyzer = imageAnalyzer,
                        conversation = conversation,
                        senderAddress = senderAddress,
                        sender = sender,
                        categories = (
                            DefaultFinancialCatalog.expenseCategories.keys +
                                DefaultFinancialCatalog.incomeCategories
                            ).toList(),
                        config = imageProcessingConfig,
                    ),
                    pollInterval = imageProcessingConfig.pollInterval,
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
                val receiptMedia = ReceiptMediaHttpDependencies(
                    ReceiptMediaAccess(settlementReceiptRepository, mediaStorage, receiptLinkSigner),
                )
                worker.start()
                imageWorker.start()
                try {
                    embeddedServer(
                        factory = Netty,
                        host = config.http.host,
                        port = config.http.port,
                    ) {
                        module(database, whatsapp, receiptMedia, dashboard)
                    }.start(wait = true)
                } finally {
                    imageWorker.close()
                    worker.close()
                }
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
    receiptMedia: ReceiptMediaHttpDependencies? = null,
    dashboard: DashboardHttpDependencies? = null,
) {
    configureHttp(database, whatsapp, receiptMedia, dashboard)
}
