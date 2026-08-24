package com.sova.delivery.http

import com.sova.DatabaseHealth
import com.sova.module
import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HandleWhatsappTextMessage
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.application.SendMessageResult
import com.sova.whatsapp.application.WhatsappMessageSender
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.infrastructure.persistence.PostgresWhatsappRepository
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsappWebhookIntegrationTest {
    @Test
    fun `authorized users share a household and duplicate or unknown senders do not respond twice`() {
        EmbeddedPostgres.builder().start().use { postgres ->
            val database = PostgresDatabase(
                DatabaseConfig(
                    url = postgres.getJdbcUrl("postgres", "postgres"),
                    user = "postgres",
                    password = "",
                ),
            )
            database.migrate()
            val repository = PostgresWhatsappRepository(database)
            val senderAddress = WhatsappAddress("whatsapp:+15550000000")
            val userAAddress = WhatsappAddress("whatsapp:+5585991234567")
            val userAAddressWithoutNinthDigit = WhatsappAddress("whatsapp:+558591234567")
            val userBAddress = WhatsappAddress("whatsapp:+15550000002")
            runBlocking {
                repository.provision(
                    HouseholdProvisioning(
                        name = "Sova Household",
                        users = listOf(
                            ProvisionedWhatsappUser("User A", userAAddress),
                            ProvisionedWhatsappUser("User B", userBAddress),
                        ),
                    ),
                )
            }
            val sender = IntegrationFakeSender()
            val dependencies = WhatsappHttpDependencies(
                expectedAccountSid = ACCOUNT_SID,
                expectedSenderAddress = senderAddress,
                signatureValidator = { _, _, signature -> signature == "valid" },
                handler = HandleWhatsappTextMessage(repository, senderAddress, sender),
            )

            testApplication {
                application { module(DatabaseHealth { true }, dependencies) }

                assertEquals(HttpStatusCode.OK, postWebhook("SM-A", userAAddressWithoutNinthDigit, " ping ", "valid"))
                assertEquals(HttpStatusCode.OK, postWebhook("SM-B", userBAddress, "PING", "valid"))
                assertEquals(HttpStatusCode.OK, postWebhook("SM-A", userAAddress, "ping", "valid"))
                assertEquals(
                    HttpStatusCode.OK,
                    postWebhook("SM-UNKNOWN", WhatsappAddress("whatsapp:+15550000009"), "ping", "valid"),
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    postWebhook("SM-INVALID", userAAddress, "ping", "invalid"),
                )
            }

            assertEquals(listOf("pong", "pong"), sender.sent.map { it.second })
            database.withConnection { connection ->
                val context = DSL.using(connection, SQLDialect.POSTGRES)
                assertEquals(
                    2L,
                    context.fetchValue(
                        "SELECT count(*) FROM whatsapp_messages WHERE direction = 'INBOUND'",
                        Long::class.java,
                    ),
                )
                assertEquals(
                    2L,
                    context.fetchValue(
                        "SELECT count(*) FROM whatsapp_messages WHERE direction = 'OUTBOUND'",
                        Long::class.java,
                    ),
                )
                assertEquals(
                    1L,
                    context.fetchValue(
                        """
                        SELECT count(DISTINCT u.household_id)
                        FROM whatsapp_messages wm
                        JOIN users u ON u.id = wm.user_id
                        WHERE wm.direction = 'INBOUND'
                        """.trimIndent(),
                        Long::class.java,
                    ),
                )
                assertEquals(
                    2L,
                    context.fetchValue(
                        "SELECT count(DISTINCT user_id) FROM whatsapp_messages WHERE direction = 'INBOUND'",
                        Long::class.java,
                    ),
                )
            }
        }
    }

    private companion object {
        const val ACCOUNT_SID = "AC00000000000000000000000000000000"
    }
}

private class IntegrationFakeSender : WhatsappMessageSender {
    val sent = mutableListOf<Pair<WhatsappAddress, String>>()

    override suspend fun send(to: WhatsappAddress, body: String): SendMessageResult {
        sent += to to body
        return SendMessageResult("SM-INTEGRATION-OUT-${sent.size}")
    }
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postWebhook(
    messageSid: String,
    from: WhatsappAddress,
    body: String,
    signature: String,
): HttpStatusCode = client.submitForm(
    url = "/webhooks/twilio/whatsapp",
    formParameters = parameters {
        append("MessageSid", messageSid)
        append("AccountSid", "AC00000000000000000000000000000000")
        append("From", from.value)
        append("To", "whatsapp:+15550000000")
        append("Body", body)
        append("NumMedia", "0")
    },
) {
    header("X-Twilio-Signature", signature)
}.status
