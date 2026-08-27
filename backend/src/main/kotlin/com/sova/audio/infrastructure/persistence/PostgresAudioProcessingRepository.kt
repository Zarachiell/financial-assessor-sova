package com.sova.audio.infrastructure.persistence

import com.sova.audio.application.AudioFailure
import com.sova.audio.application.AudioProcessingRepository
import com.sova.audio.application.ClaimedAudioJob
import com.sova.audio.application.TranscriptionResult
import com.sova.audio.application.boundedErrorCode
import com.sova.shared.infrastructure.database.PostgresDatabase
import com.sova.whatsapp.domain.WhatsappAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class PostgresAudioProcessingRepository(
    private val database: PostgresDatabase,
) : AudioProcessingRepository {
    override suspend fun claimNext(maxAttempts: Int, staleBefore: Instant): ClaimedAudioJob? =
        withContext(Dispatchers.IO) {
            database.inTransaction { connection ->
                val context = DSL.using(connection, SQLDialect.POSTGRES)
                val expired = context.fetch(
                    """
                    UPDATE processing_jobs
                    SET status = 'FAILED',
                        last_error_code = 'processing_lease_expired',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE type = 'PROCESS_AUDIO_MESSAGE'
                      AND status = 'PROCESSING'
                      AND attempts >= ?
                      AND updated_at <= CAST(? AS TIMESTAMPTZ)
                    RETURNING reference_id
                    """.trimIndent(),
                    maxAttempts,
                    staleBefore.toString(),
                )
                expired.forEach { record ->
                    context.execute(
                        """
                        UPDATE attachments
                        SET status = 'FAILED',
                            last_error_code = 'processing_lease_expired',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.trimIndent(),
                        record.get("reference_id", UUID::class.java),
                    )
                }

                val claimed = context.fetchOne(
                    """
                    WITH candidate AS (
                        SELECT id
                        FROM processing_jobs
                        WHERE type = 'PROCESS_AUDIO_MESSAGE'
                          AND attempts < ?
                          AND (
                              (status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP)
                              OR
                              (status = 'PROCESSING' AND updated_at <= CAST(? AS TIMESTAMPTZ))
                          )
                        ORDER BY available_at, created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE processing_jobs job
                    SET status = 'PROCESSING',
                        attempts = attempts + 1,
                        updated_at = CURRENT_TIMESTAMP
                    FROM candidate
                    WHERE job.id = candidate.id
                    RETURNING job.id
                    """.trimIndent(),
                    maxAttempts,
                    staleBefore.toString(),
                ) ?: return@inTransaction null

                context.fetchOne(
                    """
                    SELECT
                        job.id AS job_id,
                        job.attempts,
                        attachment.id AS attachment_id,
                        attachment.media_url,
                        attachment.content_type,
                        message.message_sid,
                        message.from_address,
                        message.to_address,
                        message.user_id,
                        app_user.household_id
                    FROM processing_jobs job
                    JOIN attachments attachment ON attachment.id = job.reference_id
                    JOIN whatsapp_messages message ON message.id = attachment.whatsapp_message_id
                    JOIN users app_user ON app_user.id = message.user_id
                    WHERE job.id = ?
                    """.trimIndent(),
                    claimed.get("id", UUID::class.java),
                )?.toClaimedAudioJob()
                    ?: error("Claimed audio job could not be loaded")
            }
        }

    override suspend fun markDownloaded(job: ClaimedAudioJob, sizeBytes: Long): Unit =
        updateAttachment(job.attachmentId, "DOWNLOADED", sizeBytes)

    override suspend fun markProcessing(job: ClaimedAudioJob): Unit =
        updateAttachment(job.attachmentId, "PROCESSING")

    override suspend fun saveTranscription(job: ClaimedAudioJob, result: TranscriptionResult): Unit =
        withContext(Dispatchers.IO) {
            database.inTransaction { connection ->
                val context = DSL.using(connection, SQLDialect.POSTGRES)
                requireUpdated(
                    context.execute(
                        """
                        UPDATE whatsapp_messages
                        SET body = ?
                        WHERE message_sid = ? AND direction = 'INBOUND'
                        """.trimIndent(),
                        result.text,
                        job.messageSid,
                    ),
                    "Inbound WhatsApp message was not found for transcription",
                )
                requireUpdated(
                    context.execute(
                        """
                        UPDATE attachments
                        SET transcription_provider = ?,
                            transcription_model = ?,
                            transcription_duration_ms = ?,
                            transcription_language = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'PROCESSING'
                        """.trimIndent(),
                        result.provider,
                        result.model,
                        result.durationMs,
                        result.language,
                        job.attachmentId,
                    ),
                    "Audio attachment was not processing",
                )
            }
        }

    override suspend fun markCompleted(job: ClaimedAudioJob): Unit =
        finish(job, attachmentStatus = "PROCESSED", jobStatus = "COMPLETED", failure = null)

    override suspend fun scheduleRetry(
        job: ClaimedAudioJob,
        failure: AudioFailure,
        availableAt: Instant,
    ): Unit = withContext(Dispatchers.IO) {
        database.inTransaction { connection ->
            val context = DSL.using(connection, SQLDialect.POSTGRES)
            requireUpdated(
                context.execute(
                    """
                    UPDATE processing_jobs
                    SET status = 'PENDING',
                        available_at = CAST(? AS TIMESTAMPTZ),
                        last_error_code = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'PROCESSING'
                    """.trimIndent(),
                    availableAt.toString(),
                    boundedErrorCode(failure.code),
                    job.jobId,
                ),
                "Audio processing job was not processing",
            )
            requireUpdated(
                context.execute(
                    """
                    UPDATE attachments
                    SET status = 'RECEIVED',
                        last_error_code = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent(),
                    boundedErrorCode(failure.code),
                    job.attachmentId,
                ),
                "Audio attachment was not found",
            )
        }
    }

    override suspend fun markFailed(job: ClaimedAudioJob, failure: AudioFailure): Unit =
        finish(job, attachmentStatus = "FAILED", jobStatus = "FAILED", failure = failure)

    private suspend fun updateAttachment(
        attachmentId: UUID,
        status: String,
        sizeBytes: Long? = null,
    ): Unit = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            requireUpdated(
                DSL.using(connection, SQLDialect.POSTGRES).execute(
                    """
                    UPDATE attachments
                    SET status = ?,
                        size_bytes = COALESCE(?, size_bytes),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent(),
                    status,
                    sizeBytes,
                    attachmentId,
                ),
                "Audio attachment was not found",
            )
        }
    }

    private suspend fun finish(
        job: ClaimedAudioJob,
        attachmentStatus: String,
        jobStatus: String,
        failure: AudioFailure?,
    ): Unit = withContext(Dispatchers.IO) {
        database.inTransaction { connection ->
            val context = DSL.using(connection, SQLDialect.POSTGRES)
            val errorCode = failure?.code?.let(::boundedErrorCode)
            requireUpdated(
                context.execute(
                    """
                    UPDATE processing_jobs
                    SET status = ?,
                        last_error_code = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'PROCESSING'
                    """.trimIndent(),
                    jobStatus,
                    errorCode,
                    job.jobId,
                ),
                "Audio processing job was not processing",
            )
            requireUpdated(
                context.execute(
                    """
                    UPDATE attachments
                    SET status = ?,
                        last_error_code = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent(),
                    attachmentStatus,
                    errorCode,
                    job.attachmentId,
                ),
                "Audio attachment was not found",
            )
        }
    }
}

private fun Record.toClaimedAudioJob(): ClaimedAudioJob = ClaimedAudioJob(
    jobId = get("job_id", UUID::class.java),
    attachmentId = get("attachment_id", UUID::class.java),
    messageSid = get("message_sid", String::class.java),
    householdId = get("household_id", UUID::class.java),
    userId = get("user_id", UUID::class.java),
    from = WhatsappAddress(get("from_address", String::class.java)),
    to = WhatsappAddress(get("to_address", String::class.java)),
    mediaUrl = get("media_url", String::class.java),
    contentType = get("content_type", String::class.java),
    attempt = get("attempts", Int::class.java),
)

private fun requireUpdated(rows: Int, message: String) {
    check(rows == 1) { message }
}
