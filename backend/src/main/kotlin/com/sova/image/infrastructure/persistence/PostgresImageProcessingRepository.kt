package com.sova.image.infrastructure.persistence

import com.sova.image.application.ClaimedImageJob
import com.sova.image.application.ImageAnalysisResult
import com.sova.image.application.ImageFailure
import com.sova.image.application.ImageProcessingRepository
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.domain.WhatsappAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class PostgresImageProcessingRepository(
    private val database: PostgresDatabase,
) : ImageProcessingRepository {
    override suspend fun claimNext(maxAttempts: Int, staleBefore: Instant): ClaimedImageJob? =
        withContext(Dispatchers.IO) {
            database.inTransaction { connection ->
                val sql = DSL.using(connection, SQLDialect.POSTGRES)
                val expired = sql.fetch(
                    """
                    UPDATE processing_jobs
                    SET status = 'FAILED', last_error_code = 'processing_lease_expired', updated_at = CURRENT_TIMESTAMP
                    WHERE type = 'PROCESS_IMAGE_MESSAGE'
                      AND status = 'PROCESSING'
                      AND attempts >= ?
                      AND updated_at <= CAST(? AS TIMESTAMPTZ)
                    RETURNING reference_id
                    """.trimIndent(),
                    maxAttempts,
                    staleBefore.toString(),
                )
                expired.forEach { record ->
                    sql.execute(
                        """
                        UPDATE attachments
                        SET status = 'FAILED', last_error_code = 'processing_lease_expired', updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.trimIndent(),
                        record.get("reference_id", UUID::class.java),
                    )
                }
                val claimed = sql.fetchOne(
                    """
                    WITH candidate AS (
                        SELECT id
                        FROM processing_jobs
                        WHERE type = 'PROCESS_IMAGE_MESSAGE'
                          AND attempts < ?
                          AND (
                              (status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP) OR
                              (status = 'PROCESSING' AND updated_at <= CAST(? AS TIMESTAMPTZ))
                          )
                        ORDER BY available_at, created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE processing_jobs job
                    SET status = 'PROCESSING', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    FROM candidate
                    WHERE job.id = candidate.id
                    RETURNING job.id
                    """.trimIndent(),
                    maxAttempts,
                    staleBefore.toString(),
                ) ?: return@inTransaction null
                sql.fetchOne(
                    """
                    SELECT job.id AS job_id, job.attempts,
                           attachment.id AS attachment_id, attachment.media_url, attachment.content_type,
                           message.message_sid, message.body, message.from_address, message.user_id,
                           attachment.household_id
                    FROM processing_jobs job
                    JOIN attachments attachment ON attachment.id = job.reference_id
                    JOIN whatsapp_messages message ON message.id = attachment.whatsapp_message_id
                    WHERE job.id = ?
                    """.trimIndent(),
                    claimed.get("id", UUID::class.java),
                )?.toClaimedImageJob() ?: error("Claimed image job could not be loaded")
            }
        }

    override suspend fun markDownloaded(job: ClaimedImageJob, sizeBytes: Long): Unit =
        updateAttachment(job.attachmentId, "DOWNLOADED", sizeBytes)

    override suspend fun markProcessing(job: ClaimedImageJob): Unit =
        updateAttachment(job.attachmentId, "PROCESSING")

    override suspend fun saveAnalysis(
        job: ClaimedImageJob,
        result: ImageAnalysisResult,
        storageKey: String,
        sha256Hex: String,
        sizeBytes: Long,
        confirmationRequired: Boolean,
        analyzedAt: Instant,
    ): Unit = withContext(Dispatchers.IO) {
        database.inTransaction { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            requireUpdated(
                sql.execute(
                    """
                    UPDATE attachments
                    SET storage_key = ?, sha256_hex = ?, size_bytes = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND household_id = ? AND status = 'PROCESSING'
                    """.trimIndent(),
                    storageKey,
                    sha256Hex,
                    sizeBytes,
                    job.attachmentId,
                    job.householdId,
                ),
                "Image attachment was not processing",
            )
            sql.execute(
                """
                INSERT INTO image_analyses (
                    attachment_id, household_id, document_type, merchant, amount, occurred_on,
                    suggested_category, confidence, merchant_confidence, amount_confidence,
                    date_confidence, category_confidence, confirmation_required, status,
                    provider, model, analyzed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, CAST(? AS TIMESTAMPTZ))
                ON CONFLICT (attachment_id) DO UPDATE SET
                    document_type = EXCLUDED.document_type,
                    merchant = EXCLUDED.merchant,
                    amount = EXCLUDED.amount,
                    occurred_on = EXCLUDED.occurred_on,
                    suggested_category = EXCLUDED.suggested_category,
                    confidence = EXCLUDED.confidence,
                    merchant_confidence = EXCLUDED.merchant_confidence,
                    amount_confidence = EXCLUDED.amount_confidence,
                    date_confidence = EXCLUDED.date_confidence,
                    category_confidence = EXCLUDED.category_confidence,
                    confirmation_required = EXCLUDED.confirmation_required,
                    provider = EXCLUDED.provider,
                    model = EXCLUDED.model,
                    analyzed_at = EXCLUDED.analyzed_at
                """.trimIndent(),
                job.attachmentId,
                job.householdId,
                result.documentType.name,
                result.merchant,
                result.amount,
                result.occurredOn,
                result.suggestedCategory,
                result.confidence,
                result.merchantConfidence,
                result.amountConfidence,
                result.dateConfidence,
                result.categoryConfidence,
                confirmationRequired,
                result.provider,
                result.model,
                analyzedAt.toString(),
            )
        }
    }

    override suspend fun markCompleted(job: ClaimedImageJob): Unit =
        finish(job, "PROCESSED", "COMPLETED", null)

    override suspend fun scheduleRetry(job: ClaimedImageJob, failure: ImageFailure, availableAt: Instant): Unit =
        withContext(Dispatchers.IO) {
            database.inTransaction { connection ->
                val sql = DSL.using(connection, SQLDialect.POSTGRES)
                requireUpdated(
                    sql.execute(
                        """
                        UPDATE processing_jobs
                        SET status = 'PENDING', available_at = CAST(? AS TIMESTAMPTZ),
                            last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'PROCESSING'
                        """.trimIndent(),
                        availableAt.toString(),
                        failure.code.take(100),
                        job.jobId,
                    ),
                    "Image processing job was not processing",
                )
                requireUpdated(
                    sql.execute(
                        """
                        UPDATE attachments
                        SET status = 'RECEIVED', last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND household_id = ?
                        """.trimIndent(),
                        failure.code.take(100),
                        job.attachmentId,
                        job.householdId,
                    ),
                    "Image attachment was not found",
                )
            }
        }

    override suspend fun markFailed(job: ClaimedImageJob, failure: ImageFailure): Unit =
        finish(job, "FAILED", "FAILED", failure)

    private suspend fun updateAttachment(attachmentId: UUID, status: String, sizeBytes: Long? = null): Unit =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                requireUpdated(
                    DSL.using(connection, SQLDialect.POSTGRES).execute(
                        """
                        UPDATE attachments
                        SET status = ?, size_bytes = COALESCE(?, size_bytes), updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.trimIndent(),
                        status,
                        sizeBytes,
                        attachmentId,
                    ),
                    "Image attachment was not found",
                )
            }
        }

    private suspend fun finish(
        job: ClaimedImageJob,
        attachmentStatus: String,
        jobStatus: String,
        failure: ImageFailure?,
    ): Unit = withContext(Dispatchers.IO) {
        database.inTransaction { connection ->
            val sql = DSL.using(connection, SQLDialect.POSTGRES)
            requireUpdated(
                sql.execute(
                    """
                    UPDATE processing_jobs
                    SET status = ?, last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'PROCESSING'
                    """.trimIndent(),
                    jobStatus,
                    failure?.code?.take(100),
                    job.jobId,
                ),
                "Image processing job was not processing",
            )
            requireUpdated(
                sql.execute(
                    """
                    UPDATE attachments
                    SET status = ?, last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND household_id = ?
                    """.trimIndent(),
                    attachmentStatus,
                    failure?.code?.take(100),
                    job.attachmentId,
                    job.householdId,
                ),
                "Image attachment was not found",
            )
        }
    }
}

private fun Record.toClaimedImageJob(): ClaimedImageJob = ClaimedImageJob(
    jobId = get("job_id", UUID::class.java),
    attachmentId = get("attachment_id", UUID::class.java),
    messageSid = get("message_sid", String::class.java),
    caption = get("body", String::class.java),
    householdId = get("household_id", UUID::class.java),
    userId = get("user_id", UUID::class.java),
    from = WhatsappAddress(get("from_address", String::class.java)),
    mediaUrl = get("media_url", String::class.java),
    contentType = get("content_type", String::class.java),
    attempt = get("attempts", Int::class.java),
)

private fun requireUpdated(rows: Int, message: String) {
    check(rows == 1) { message }
}
