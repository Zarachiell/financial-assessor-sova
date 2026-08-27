package com.sova.whatsapp.infrastructure.persistence

import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.StoredWhatsappMessage
import com.sova.whatsapp.application.StoredWhatsappAttachment
import com.sova.whatsapp.application.WhatsappRepository
import com.sova.whatsapp.domain.AuthorizedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.util.UUID

class PostgresWhatsappRepository(
    private val database: PostgresDatabase,
) : WhatsappRepository {
    override suspend fun provision(household: HouseholdProvisioning): Unit = withContext(Dispatchers.IO) {
        require(household.users.size == EXPECTED_USER_COUNT) { "Exactly two users must be provisioned" }
        require(household.users.map { it.address.identityKey }.distinct().size == EXPECTED_USER_COUNT) {
            "Provisioned WhatsApp addresses must be distinct"
        }

        database.inTransaction { connection ->
            val context = DSL.using(connection, SQLDialect.POSTGRES)
            val households = context.fetch("SELECT id FROM households ORDER BY created_at LIMIT 2")
            check(households.size <= 1) { "Sova expects exactly one household" }
            val householdId = households.firstOrNull()?.get("id", UUID::class.java) ?: UUID.randomUUID().also { id ->
                context.execute(
                    "INSERT INTO households (id, name) VALUES (?, ?)",
                    id,
                    household.name,
                )
            }
            context.execute("UPDATE households SET name = ? WHERE id = ?", household.name, householdId)

            val activeUserIds = household.users.map { configuredUser ->
                val existing = context.fetchOne(
                    """
                    SELECT wi.id AS identity_id, u.id AS user_id
                    FROM whatsapp_identities wi
                    JOIN users u ON u.id = wi.user_id
                    WHERE wi.normalized_phone_number = ?
                    """.trimIndent(),
                    configuredUser.address.identityKey,
                )
                if (existing == null) {
                    val userId = UUID.randomUUID()
                    context.execute(
                        "INSERT INTO users (id, household_id, name, active) VALUES (?, ?, ?, TRUE)",
                        userId,
                        householdId,
                        configuredUser.name,
                    )
                    context.execute(
                        "INSERT INTO whatsapp_identities (id, user_id, phone_number, active) VALUES (?, ?, ?, TRUE)",
                        UUID.randomUUID(),
                        userId,
                        configuredUser.address.value,
                    )
                    userId
                } else {
                    val userId = existing.get("user_id", UUID::class.java)
                    context.execute(
                        "UPDATE users SET household_id = ?, name = ?, active = TRUE WHERE id = ?",
                        householdId,
                        configuredUser.name,
                        userId,
                    )
                    context.execute(
                        "UPDATE whatsapp_identities SET phone_number = ?, active = TRUE WHERE id = ?",
                        configuredUser.address.value,
                        existing.get("identity_id", UUID::class.java),
                    )
                    userId
                }
            }

            context.execute(
                """
                UPDATE whatsapp_identities
                SET active = FALSE
                WHERE user_id IN (SELECT id FROM users WHERE household_id = ?)
                  AND user_id NOT IN (?, ?)
                """.trimIndent(),
                householdId,
                activeUserIds[0],
                activeUserIds[1],
            )
            context.execute(
                "UPDATE users SET active = FALSE WHERE household_id = ? AND id NOT IN (?, ?)",
                householdId,
                activeUserIds[0],
                activeUserIds[1],
            )
        }
    }

    override suspend fun findActiveUser(address: WhatsappAddress): AuthorizedWhatsappUser? =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetchOne(
                    """
                    SELECT u.id AS user_id, u.household_id
                    FROM whatsapp_identities wi
                    JOIN users u ON u.id = wi.user_id
                    WHERE wi.normalized_phone_number = ?
                      AND wi.active
                      AND u.active
                    """.trimIndent(),
                    address.identityKey,
                )?.let { record ->
                    AuthorizedWhatsappUser(
                        userId = record.get("user_id", UUID::class.java),
                        householdId = record.get("household_id", UUID::class.java),
                        address = address,
                    )
                }
            }
        }

    override suspend fun saveInboundIfNew(
        message: StoredWhatsappMessage,
        attachments: List<StoredWhatsappAttachment>,
        audioJobAttachmentId: UUID?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            require(attachments.all { it.whatsappMessageId == message.id }) {
                "Every attachment must belong to the inbound message"
            }
            require(audioJobAttachmentId == null || attachments.any { it.id == audioJobAttachmentId }) {
                "Audio job attachment must belong to the inbound message"
            }
            database.inTransaction { connection ->
                val context = DSL.using(connection, SQLDialect.POSTGRES)
                val insertedRows = context.execute(
                    """
                    INSERT INTO whatsapp_messages
                        (id, message_sid, direction, user_id, from_address, to_address, body, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))
                    ON CONFLICT (message_sid) DO NOTHING
                    """.trimIndent(),
                    message.id,
                    message.messageSid,
                    message.direction.name,
                    message.userId,
                    message.from.value,
                    message.to.value,
                    message.body,
                    message.occurredAt.toString(),
                )
                if (insertedRows != 1) return@inTransaction false

                attachments.forEach { attachment ->
                    context.execute(
                        """
                        INSERT INTO attachments
                            (id, whatsapp_message_id, media_index, media_url, content_type, media_type, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        attachment.id,
                        attachment.whatsappMessageId,
                        attachment.mediaIndex,
                        attachment.mediaUrl,
                        attachment.contentType,
                        attachment.mediaType.name,
                        attachment.status.name,
                    )
                }
                audioJobAttachmentId?.let { attachmentId ->
                    context.execute(
                        """
                        INSERT INTO processing_jobs (id, type, reference_id, status)
                        VALUES (?, 'PROCESS_AUDIO_MESSAGE', ?, 'PENDING')
                        """.trimIndent(),
                        UUID.randomUUID(),
                        attachmentId,
                    )
                }
                true
            }
        }

    override suspend fun saveOutbound(message: StoredWhatsappMessage): Unit = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).execute(
                """
                INSERT INTO whatsapp_messages
                    (id, message_sid, direction, user_id, from_address, to_address, body, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))
                """.trimIndent(),
                message.id,
                message.messageSid,
                message.direction.name,
                message.userId,
                message.from.value,
                message.to.value,
                message.body,
                message.occurredAt.toString(),
            )
        }
    }

    private companion object {
        const val EXPECTED_USER_COUNT = 2
    }
}
