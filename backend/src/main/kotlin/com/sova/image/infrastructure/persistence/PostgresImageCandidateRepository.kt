package com.sova.image.infrastructure.persistence

import com.sova.image.application.ImageCandidate
import com.sova.image.application.ImageCandidateRepository
import com.sova.image.application.ImageDocumentType
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class PostgresImageCandidateRepository(
    private val database: PostgresDatabase,
) : ImageCandidateRepository {
    override suspend fun find(householdId: UUID, attachmentId: UUID): ImageCandidate? =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetchOne(
                    """
                    SELECT analysis.*, message.body AS caption
                    FROM image_analyses analysis
                    JOIN attachments attachment ON attachment.id = analysis.attachment_id
                    JOIN whatsapp_messages message ON message.id = attachment.whatsapp_message_id
                    WHERE analysis.household_id = ? AND analysis.attachment_id = ? AND analysis.status = 'CANDIDATE'
                    """.trimIndent(),
                    householdId,
                    attachmentId,
                )?.toImageCandidate()
            }
        }

    override suspend fun listPending(householdId: UUID, limit: Int): List<ImageCandidate> =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetch(
                    """
                    SELECT analysis.*, message.body AS caption
                    FROM image_analyses analysis
                    JOIN attachments attachment ON attachment.id = analysis.attachment_id
                    JOIN whatsapp_messages message ON message.id = attachment.whatsapp_message_id
                    WHERE analysis.household_id = ? AND analysis.status = 'CANDIDATE'
                    ORDER BY analysis.analyzed_at DESC
                    LIMIT ?
                    """.trimIndent(),
                    householdId,
                    limit,
                ).map { it.toImageCandidate() }
            }
        }

    override suspend fun markConsumed(householdId: UUID, attachmentId: UUID, consumedAt: Instant): Boolean =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).execute(
                    """
                    UPDATE image_analyses
                    SET status = 'CONSUMED', consumed_at = CAST(? AS TIMESTAMPTZ)
                    WHERE household_id = ? AND attachment_id = ? AND status = 'CANDIDATE'
                    """.trimIndent(),
                    consumedAt.toString(),
                    householdId,
                    attachmentId,
                ) == 1
            }
        }
}

private fun Record.toImageCandidate(): ImageCandidate = ImageCandidate(
    attachmentId = get("attachment_id", UUID::class.java),
    householdId = get("household_id", UUID::class.java),
    caption = get("caption", String::class.java),
    documentType = ImageDocumentType.valueOf(get("document_type", String::class.java)),
    merchant = get("merchant", String::class.java),
    amount = get("amount", java.math.BigDecimal::class.java),
    occurredOn = get("occurred_on", java.time.LocalDate::class.java),
    suggestedCategory = get("suggested_category", String::class.java),
    confidence = get("confidence", java.math.BigDecimal::class.java),
    confirmationRequired = get("confirmation_required", Boolean::class.java),
    analyzedAt = get("analyzed_at", java.time.OffsetDateTime::class.java).toInstant(),
)
