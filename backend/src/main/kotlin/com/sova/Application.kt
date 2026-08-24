package com.sova

import com.sova.delivery.http.WhatsappHttpDependencies
import com.sova.delivery.http.configureHttp
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
    val senderAddress = WhatsappAddress(config.twilio.whatsappSender)
    val whatsapp = WhatsappHttpDependencies(
        expectedAccountSid = config.twilio.accountSid,
        expectedSenderAddress = senderAddress,
        signatureValidator = TwilioWebhookSignatureValidator(config.twilio.authToken),
        handler = HandleWhatsappTextMessage(
            repository = whatsappRepository,
            senderAddress = senderAddress,
            sender = TwilioWhatsappMessageSender(senderAddress, config.twilio),
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

fun Application.module(
    database: DatabaseHealth,
    whatsapp: WhatsappHttpDependencies? = null,
) {
    configureHttp(database, whatsapp)
}
