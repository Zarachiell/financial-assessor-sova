package com.sova.whatsapp.infrastructure.persistence

import com.sova.shared.config.DatabaseConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.ProvisionedWhatsappUser
import com.sova.whatsapp.application.StoredWhatsappMessage
import com.sova.whatsapp.application.StoredWhatsappAttachment
import com.sova.whatsapp.application.WhatsappMediaType
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.domain.WhatsappMessageDirection
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresWhatsappRepositoryIntegrationTest {
    @Test
    fun `provisions one household and enforces identity authorship and MessageSid idempotency`() {
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
            val userAAddress = WhatsappAddress("whatsapp:+5585991234567")
            val userAAddressWithoutNinthDigit = WhatsappAddress("whatsapp:+558591234567")
            val userBAddress = WhatsappAddress("whatsapp:+15550000002")
            val senderAddress = WhatsappAddress("whatsapp:+15550000000")
            val provisioning = HouseholdProvisioning(
                name = "Sova Household",
                users = listOf(
                    ProvisionedWhatsappUser("User A", userAAddress),
                    ProvisionedWhatsappUser("User B", userBAddress),
                ),
            )

            runBlocking {
                repository.provision(provisioning)
                repository.provision(provisioning)

                val userA = assertNotNull(repository.findActiveUser(userAAddress))
                val userAWithoutNinthDigit = assertNotNull(repository.findActiveUser(userAAddressWithoutNinthDigit))
                assertEquals(userA.userId, userAWithoutNinthDigit.userId)
                assertEquals(userA.householdId, userAWithoutNinthDigit.householdId)
                assertEquals(userAAddressWithoutNinthDigit, userAWithoutNinthDigit.address)
                val userB = assertNotNull(repository.findActiveUser(userBAddress))
                assertEquals(userA.householdId, userB.householdId)
                assertNotEquals(userA.userId, userB.userId)
                assertNull(repository.findActiveUser(WhatsappAddress("whatsapp:+15550000009")))

                val inbound = StoredWhatsappMessage(
                    messageSid = "SM-INTEGRATION-IN",
                    direction = WhatsappMessageDirection.INBOUND,
                    userId = userA.userId,
                    from = userAAddress,
                    to = senderAddress,
                    body = "ping",
                    occurredAt = Instant.parse("2026-08-24T12:00:00Z"),
                )
                val attachmentId = UUID.randomUUID()
                val attachment = StoredWhatsappAttachment(
                    id = attachmentId,
                    whatsappMessageId = inbound.id,
                    mediaIndex = 0,
                    mediaUrl = "https://api.twilio.com/media/ME-INTEGRATION",
                    contentType = "audio/ogg",
                    mediaType = WhatsappMediaType.AUDIO,
                )
                assertTrue(repository.saveInboundIfNew(inbound, listOf(attachment), attachmentId))
                assertFalse(repository.saveInboundIfNew(inbound))
                repository.saveOutbound(
                    StoredWhatsappMessage(
                        messageSid = "SM-INTEGRATION-OUT",
                        direction = WhatsappMessageDirection.OUTBOUND,
                        userId = userA.userId,
                        from = senderAddress,
                        to = userAAddress,
                        body = "pong",
                        occurredAt = Instant.parse("2026-08-24T12:00:01Z"),
                    ),
                )

                database.withConnection { connection ->
                    val context = DSL.using(connection, SQLDialect.POSTGRES)
                    assertEquals(1L, context.fetchValue("SELECT count(*) FROM households", Long::class.java))
                    assertEquals(2L, context.fetchValue("SELECT count(*) FROM users", Long::class.java))
                    assertEquals(2L, context.fetchValue("SELECT count(*) FROM whatsapp_identities", Long::class.java))
                    assertEquals(2L, context.fetchValue("SELECT count(*) FROM whatsapp_messages", Long::class.java))
                    assertEquals(1L, context.fetchValue("SELECT count(*) FROM attachments", Long::class.java))
                    assertEquals(1L, context.fetchValue("SELECT count(*) FROM processing_jobs", Long::class.java))
                    assertEquals(
                        "PENDING",
                        context.fetchValue("SELECT status FROM processing_jobs", String::class.java),
                    )
                    assertEquals(
                        userA.userId,
                        context.fetchValue(
                            "SELECT user_id FROM whatsapp_messages WHERE message_sid = 'SM-INTEGRATION-IN'",
                            java.util.UUID::class.java,
                        ),
                    )
                    assertEquals(
                        1L,
                        context.fetchValue(
                            "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_whatsapp_messages_message_sid'",
                            Long::class.java,
                        ),
                    )
                    assertEquals(
                        userAAddress.identityKey,
                        context.fetchValue(
                            "SELECT normalized_phone_number FROM whatsapp_identities WHERE user_id = ?",
                            userA.userId,
                        ),
                    )
                    assertEquals(
                        1L,
                        context.fetchValue(
                            "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_whatsapp_identities_normalized_phone_number'",
                            Long::class.java,
                        ),
                    )
                    context.execute(
                        "UPDATE whatsapp_identities SET active = FALSE WHERE phone_number = ?",
                        userAAddress.value,
                    )
                }
                assertNull(repository.findActiveUser(userAAddress))

                repository.provision(provisioning)
                assertNotNull(repository.findActiveUser(userAAddress))
            }
        }
    }
}
