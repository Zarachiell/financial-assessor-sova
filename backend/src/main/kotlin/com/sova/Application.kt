package com.sova

import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.financialLocalTools
import com.sova.ai.infrastructure.groq.GroqAiProvider
import com.sova.conversation.application.AiConversationOrchestrator
import com.sova.conversation.infrastructure.persistence.PostgresConversationHistory
import com.sova.delivery.http.WhatsappHttpDependencies
import com.sova.delivery.http.configureHttp
import com.sova.finance.application.DefaultFinancialCatalogProvisioner
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.domain.FinancialContext
import com.sova.finance.infrastructure.persistence.PostgresFinancialRepository
import com.sova.shared.config.AppConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HandleWhatsappTextMessage
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import com.sova.whatsapp.infrastructure.twilio.TwilioWebhookSignatureValidator
import com.sova.whatsapp.infrastructure.twilio.TwilioWhatsappMessageSender
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking

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
    val financialService = FinancialApplicationService(PostgresFinancialRepository(database))
    val catalogOwner = runBlocking {
        whatsappRepository.findActiveUser(WhatsappAddress(config.household.users.first().whatsappAddress))
            ?: error("Configured catalog owner was not provisioned")
    }
    runBlocking {
        DefaultFinancialCatalogProvisioner(financialService).provision(
            FinancialContext(catalogOwner.householdId, catalogOwner.userId),
        )
    }

    val senderAddress = WhatsappAddress(config.twilio.whatsappSender)
    GroqAiProvider(config.ai.apiKey, config.ai.model).use { provider ->
        val toolRegistry = LocalToolRegistry(financialLocalTools(financialService))
        val conversation = AiConversationOrchestrator(
            provider = provider,
            registry = toolRegistry,
            dispatcher = LocalToolDispatcher(toolRegistry),
            history = PostgresConversationHistory(database),
        )
        val whatsapp = WhatsappHttpDependencies(
            expectedAccountSid = config.twilio.accountSid,
            expectedSenderAddress = senderAddress,
            signatureValidator = TwilioWebhookSignatureValidator(config.twilio.authToken),
            handler = HandleWhatsappTextMessage(
                repository = whatsappRepository,
                senderAddress = senderAddress,
                sender = TwilioWhatsappMessageSender(senderAddress, config.twilio),
                conversation = conversation,
            ),
        )

        embeddedServer(
            factory = Netty,
            host = config.http.host,
            port = config.http.port,
        ) {
            module(database, whatsapp)
        }.start(wait = true)
    }
}

fun Application.module(
    database: DatabaseHealth,
    whatsapp: WhatsappHttpDependencies? = null,
) {
    configureHttp(database, whatsapp)
}
