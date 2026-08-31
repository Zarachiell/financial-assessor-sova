package com.sova.receipt.infrastructure.persistence

import com.sova.finance.domain.FinancialContext
import com.sova.receipt.application.ReceiptCreation
import com.sova.receipt.application.SettlementReceipt
import com.sova.receipt.application.SettlementReceiptMedia
import com.sova.receipt.application.SettlementReceiptRepository
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class PostgresSettlementReceiptRepository(
    private val database: PostgresDatabase,
) : SettlementReceiptRepository {
    override suspend fun link(
        context: FinancialContext,
        receiptId: UUID,
        settlementId: UUID,
        attachmentId: UUID,
        caption: String?,
        linkedAt: Instant,
    ): ReceiptCreation? = withContext(Dispatchers.IO) {
        database.inTransaction { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            val inserted = sql.fetchOne(
                """
                INSERT INTO settlement_receipts (
                    id, household_id, settlement_id, attachment_id, caption, linked_by_user_id, linked_at
                )
                SELECT ?, settlement.household_id, settlement.id, attachment.id,
                       COALESCE(NULLIF(btrim(?), ''), NULLIF(btrim(message.body), '')),
                       app_user.id, CAST(? AS TIMESTAMPTZ)
                FROM cash_flow_settlements settlement
                JOIN attachments attachment
                  ON attachment.id = ? AND attachment.household_id = settlement.household_id
                JOIN whatsapp_messages message ON message.id = attachment.whatsapp_message_id
                JOIN users app_user
                  ON app_user.id = ? AND app_user.household_id = settlement.household_id AND app_user.active
                WHERE settlement.id = ?
                  AND settlement.household_id = ?
                  AND settlement.status = 'ACTIVE'
                  AND attachment.media_type = 'IMAGE'
                  AND attachment.status = 'PROCESSED'
                  AND attachment.storage_key IS NOT NULL
                  AND attachment.sha256_hex IS NOT NULL
                ON CONFLICT (settlement_id, attachment_id) DO NOTHING
                RETURNING *
                """.trimIndent(),
                receiptId,
                caption,
                linkedAt.toString(),
                attachmentId,
                context.userId,
                settlementId,
                context.householdId,
            )
            val receipt = inserted?.let { loadReceipt(sql, it.get("id", UUID::class.java), context.householdId) }
                ?: sql.fetchOne(
                    """
                    SELECT receipt.id
                    FROM settlement_receipts receipt
                    JOIN users app_user
                      ON app_user.id = ? AND app_user.household_id = receipt.household_id AND app_user.active
                    WHERE receipt.household_id = ? AND receipt.settlement_id = ? AND receipt.attachment_id = ?
                    """.trimIndent(),
                    context.userId,
                    context.householdId,
                    settlementId,
                    attachmentId,
                )?.let { loadReceipt(sql, it.get("id", UUID::class.java), context.householdId) }
                ?: return@inTransaction null
            sql.execute(
                """
                UPDATE image_analyses
                SET status = 'CONSUMED', consumed_at = COALESCE(consumed_at, CAST(? AS TIMESTAMPTZ))
                WHERE household_id = ? AND attachment_id = ? AND status = 'CANDIDATE'
                """.trimIndent(),
                linkedAt.toString(),
                context.householdId,
                attachmentId,
            )
            ReceiptCreation(receipt, created = inserted != null)
        }
    }

    override suspend fun list(context: FinancialContext, settlementId: UUID): List<SettlementReceipt> =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetch(
                    """
                    SELECT receipt.*, attachment.content_type, attachment.size_bytes, attachment.sha256_hex
                    FROM settlement_receipts receipt
                    JOIN attachments attachment ON attachment.id = receipt.attachment_id
                    JOIN users app_user
                      ON app_user.id = ? AND app_user.household_id = receipt.household_id AND app_user.active
                    WHERE receipt.household_id = ? AND receipt.settlement_id = ?
                    ORDER BY receipt.linked_at DESC
                    """.trimIndent(),
                    context.userId,
                    context.householdId,
                    settlementId,
                ).map { it.toSettlementReceipt() }
            }
        }

    override suspend fun findMedia(receiptId: UUID): SettlementReceiptMedia? =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetchOne(
                    """
                    SELECT receipt.id, attachment.storage_key, attachment.content_type, attachment.sha256_hex
                    FROM settlement_receipts receipt
                    JOIN attachments attachment
                      ON attachment.id = receipt.attachment_id AND attachment.household_id = receipt.household_id
                    WHERE receipt.id = ? AND attachment.status = 'PROCESSED'
                    """.trimIndent(),
                    receiptId,
                )?.let { record ->
                    SettlementReceiptMedia(
                        receiptId = record.get("id", UUID::class.java),
                        storageKey = record.get("storage_key", String::class.java),
                        contentType = record.get("content_type", String::class.java),
                        sha256Hex = record.get("sha256_hex", String::class.java),
                    )
                }
            }
        }

    private fun loadReceipt(sql: org.jooq.DSLContext, receiptId: UUID, householdId: UUID): SettlementReceipt =
        sql.fetchOne(
            """
            SELECT receipt.*, attachment.content_type, attachment.size_bytes, attachment.sha256_hex
            FROM settlement_receipts receipt
            JOIN attachments attachment ON attachment.id = receipt.attachment_id
            WHERE receipt.id = ? AND receipt.household_id = ?
            """.trimIndent(),
            receiptId,
            householdId,
        )?.toSettlementReceipt() ?: error("Settlement receipt could not be loaded")
}

private fun Record.toSettlementReceipt(): SettlementReceipt = SettlementReceipt(
    id = get("id", UUID::class.java),
    householdId = get("household_id", UUID::class.java),
    settlementId = get("settlement_id", UUID::class.java),
    attachmentId = get("attachment_id", UUID::class.java),
    caption = get("caption", String::class.java),
    contentType = get("content_type", String::class.java),
    sizeBytes = get("size_bytes", Long::class.java),
    sha256Hex = get("sha256_hex", String::class.java),
    linkedByUserId = get("linked_by_user_id", UUID::class.java),
    linkedAt = get("linked_at", OffsetDateTime::class.java).toInstant(),
)
