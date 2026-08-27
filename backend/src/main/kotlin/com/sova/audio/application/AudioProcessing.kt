package com.sova.audio.application

import com.sova.whatsapp.application.SendMessageResult
import com.sova.whatsapp.application.StoredWhatsappMessage
import com.sova.whatsapp.application.WhatsappConversationContext
import com.sova.whatsapp.application.WhatsappMessageOrigin
import com.sova.whatsapp.application.WhatsappMessageSender
import com.sova.whatsapp.application.WhatsappRepository
import com.sova.whatsapp.application.WhatsappTextConversation
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.domain.WhatsappMessageDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DownloadedMedia(
    val bytes: ByteArray,
    val contentType: String,
    val filename: String,
)

fun interface WhatsappMediaDownloader {
    suspend fun download(mediaUrl: String): DownloadedMedia
}

data class AudioInput(
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String,
)

data class SpeechToTextContext(
    val languageHint: String,
    val mimeType: String,
    val filename: String,
)

data class TranscriptionResult(
    val text: String,
    val provider: String,
    val model: String,
    val durationMs: Long,
    val confidence: Double? = null,
    val language: String? = null,
) {
    init {
        require(provider.isNotBlank()) { "Transcription provider must not be blank" }
        require(model.isNotBlank()) { "Transcription model must not be blank" }
        require(durationMs >= 0) { "Audio duration must not be negative" }
        confidence?.let { require(it in 0.0..1.0) { "Confidence must be between zero and one" } }
    }
}

fun interface SpeechToTextProvider {
    suspend fun transcribe(audio: AudioInput, context: SpeechToTextContext): TranscriptionResult
}

enum class AudioFailure(
    val code: String,
    val retryable: Boolean,
) {
    DOWNLOAD_TIMEOUT("download_timeout", true),
    DOWNLOAD_RATE_LIMITED("download_rate_limited", true),
    DOWNLOAD_UNAVAILABLE("download_unavailable", true),
    DOWNLOAD_AUTHENTICATION("download_authentication", false),
    DOWNLOAD_REJECTED("download_rejected", false),
    FILE_TOO_LARGE("file_too_large", false),
    STT_TIMEOUT("stt_timeout", true),
    STT_RATE_LIMITED("stt_rate_limited", true),
    STT_UNAVAILABLE("stt_unavailable", true),
    STT_REJECTED("stt_rejected", false),
    STT_INVALID_RESPONSE("stt_invalid_response", false),
    AUDIO_TOO_LONG("audio_too_long", false),
    BLANK_TRANSCRIPTION("blank_transcription", false),
    ORCHESTRATOR_ERROR("orchestrator_error", false),
    OUTBOUND_FAILURE("outbound_failure", false),
}

class MediaDownloadException(
    val failure: AudioFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.code, cause) {
    init {
        require(failure.name.startsWith("DOWNLOAD_") || failure == AudioFailure.FILE_TOO_LARGE)
    }
}

class SpeechToTextException(
    val failure: AudioFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.code, cause) {
    init {
        require(failure.name.startsWith("STT_"))
    }
}

data class ClaimedAudioJob(
    val jobId: UUID,
    val attachmentId: UUID,
    val messageSid: String,
    val householdId: UUID,
    val userId: UUID,
    val from: WhatsappAddress,
    val to: WhatsappAddress,
    val mediaUrl: String,
    val contentType: String,
    val attempt: Int,
)

interface AudioProcessingRepository {
    suspend fun claimNext(
        maxAttempts: Int,
        staleBefore: Instant,
    ): ClaimedAudioJob?

    suspend fun markDownloaded(job: ClaimedAudioJob, sizeBytes: Long)

    suspend fun markProcessing(job: ClaimedAudioJob)

    suspend fun saveTranscription(job: ClaimedAudioJob, result: TranscriptionResult)

    suspend fun markCompleted(job: ClaimedAudioJob)

    suspend fun scheduleRetry(job: ClaimedAudioJob, failure: AudioFailure, availableAt: Instant)

    suspend fun markFailed(job: ClaimedAudioJob, failure: AudioFailure)
}

data class AudioProcessingConfig(
    val maxAudioBytes: Long,
    val maxAudioDuration: Duration,
    val maxAttempts: Int = 3,
    val retryBaseDelay: Duration = Duration.ofSeconds(5),
    val processingLease: Duration = Duration.ofMinutes(10),
    val pollInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(maxAudioBytes > 0) { "Maximum audio size must be positive" }
        require(!maxAudioDuration.isNegative && !maxAudioDuration.isZero) {
            "Maximum audio duration must be positive"
        }
        require(maxAttempts in 1..10) { "Maximum audio attempts must be between 1 and 10" }
        require(!retryBaseDelay.isNegative) { "Retry delay must not be negative" }
        require(!processingLease.isNegative && !processingLease.isZero) { "Processing lease must be positive" }
        require(!pollInterval.isNegative && !pollInterval.isZero) { "Polling interval must be positive" }
    }
}

class ProcessAudioMessage(
    private val repository: AudioProcessingRepository,
    private val whatsappRepository: WhatsappRepository,
    private val downloader: WhatsappMediaDownloader,
    private val speechToText: SpeechToTextProvider,
    private val conversation: WhatsappTextConversation,
    private val senderAddress: WhatsappAddress,
    private val sender: WhatsappMessageSender,
    private val config: AudioProcessingConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun processNext(): Boolean {
        val job = repository.claimNext(
            maxAttempts = config.maxAttempts,
            staleBefore = clock.instant().minus(config.processingLease),
        ) ?: return false
        val startedAt = System.nanoTime()
        return try {
            process(job)
            log(job, "completed", startedAt)
            true
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: MediaDownloadException) {
            handleFailure(job, cause.failure, startedAt)
            true
        } catch (cause: SpeechToTextException) {
            handleFailure(job, cause.failure, startedAt)
            true
        } catch (cause: OutboundDeliveryException) {
            repository.markFailed(job, AudioFailure.OUTBOUND_FAILURE)
            log(job, AudioFailure.OUTBOUND_FAILURE.code, startedAt)
            true
        } catch (cause: Exception) {
            LOGGER.atError()
                .setCause(cause)
                .addKeyValue("messageSid", job.messageSid)
                .addKeyValue("attachmentId", job.attachmentId)
                .addKeyValue("attempt", job.attempt)
                .log("Unexpected audio processing failure")
            repository.markFailed(job, AudioFailure.ORCHESTRATOR_ERROR)
            log(job, AudioFailure.ORCHESTRATOR_ERROR.code, startedAt)
            true
        }
    }

    private suspend fun process(job: ClaimedAudioJob) {
        val media = downloader.download(job.mediaUrl)
        if (media.bytes.size.toLong() > config.maxAudioBytes) {
            throw MediaDownloadException(AudioFailure.FILE_TOO_LARGE)
        }
        repository.markDownloaded(job, media.bytes.size.toLong())
        repository.markProcessing(job)

        val result = speechToText.transcribe(
            audio = AudioInput(media.bytes, media.contentType, media.filename),
            context = SpeechToTextContext(
                languageHint = LANGUAGE_HINT,
                mimeType = media.contentType,
                filename = media.filename,
            ),
        )
        if (result.durationMs > config.maxAudioDuration.toMillis()) {
            finishWithControlledFailure(job, AudioFailure.AUDIO_TOO_LONG)
            return
        }
        val transcription = result.text.trim()
        if (transcription.isEmpty()) {
            finishWithControlledFailure(job, AudioFailure.BLANK_TRANSCRIPTION)
            return
        }

        repository.saveTranscription(job, result.copy(text = transcription))
        val response = try {
            conversation.respond(
                WhatsappConversationContext(
                    householdId = job.householdId,
                    userId = job.userId,
                    inboundMessageSid = job.messageSid,
                    text = transcription,
                    origin = WhatsappMessageOrigin.AUDIO,
                ),
            ).takeIf(String::isNotBlank) ?: SAFE_FAILURE_RESPONSE
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            LOGGER.atWarn()
                .setCause(cause)
                .addKeyValue("messageSid", job.messageSid)
                .addKeyValue("attachmentId", job.attachmentId)
                .log("Audio conversation failed")
            finishWithControlledFailure(job, AudioFailure.ORCHESTRATOR_ERROR)
            return
        }
        deliver(job, response)
        repository.markCompleted(job)
    }

    private suspend fun handleFailure(job: ClaimedAudioJob, failure: AudioFailure, startedAt: Long) {
        if (failure.retryable && job.attempt < config.maxAttempts) {
            val multiplier = 1L shl (job.attempt - 1).coerceAtLeast(0)
            val delay = config.retryBaseDelay.multipliedBy(multiplier)
            repository.scheduleRetry(job, failure, clock.instant().plus(delay))
            log(job, "retry_${failure.code}", startedAt)
            return
        }
        finishWithControlledFailure(job, failure)
        log(job, failure.code, startedAt)
    }

    private suspend fun finishWithControlledFailure(job: ClaimedAudioJob, failure: AudioFailure) {
        val response = when (failure) {
            AudioFailure.FILE_TOO_LARGE -> FILE_TOO_LARGE_RESPONSE
            AudioFailure.AUDIO_TOO_LONG -> AUDIO_TOO_LONG_RESPONSE
            AudioFailure.BLANK_TRANSCRIPTION -> BLANK_TRANSCRIPTION_RESPONSE
            else -> SAFE_FAILURE_RESPONSE
        }
        deliver(job, response)
        repository.markFailed(job, failure)
    }

    private suspend fun deliver(job: ClaimedAudioJob, body: String): SendMessageResult {
        try {
            val result = sender.send(job.from, body)
            whatsappRepository.saveOutbound(
                StoredWhatsappMessage(
                    messageSid = result.messageSid,
                    direction = WhatsappMessageDirection.OUTBOUND,
                    userId = job.userId,
                    from = senderAddress,
                    to = job.from,
                    body = body,
                    occurredAt = clock.instant(),
                ),
            )
            return result
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            throw OutboundDeliveryException(cause)
        }
    }

    private fun log(job: ClaimedAudioJob, status: String, startedAt: Long) {
        LOGGER.atInfo()
            .addKeyValue("messageSid", job.messageSid)
            .addKeyValue("attachmentId", job.attachmentId)
            .addKeyValue("contentType", job.contentType)
            .addKeyValue("status", status)
            .addKeyValue("attempt", job.attempt)
            .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
            .log("WhatsApp audio job processed")
    }

    private companion object {
        const val LANGUAGE_HINT = "pt"
        const val BLANK_TRANSCRIPTION_RESPONSE =
            "Meu ouvido de beagle falhou nessa. Não consegui entender o áudio. Tente enviar novamente."
        const val FILE_TOO_LARGE_RESPONSE = "Esse áudio é grande demais para eu processar. Envie um áudio menor."
        const val AUDIO_TOO_LONG_RESPONSE = "Esse áudio é longo demais para eu processar. Envie até cinco minutos."
        const val SAFE_FAILURE_RESPONSE = "Não consegui processar seu áudio agora. Tente novamente em instantes."
        val LOGGER = LoggerFactory.getLogger("com.sova.audio.processing")
    }
}

private class OutboundDeliveryException(cause: Throwable) : RuntimeException(cause)

class AudioProcessingWorker(
    private val processor: ProcessAudioMessage,
    private val pollInterval: Duration,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    fun start() {
        check(workerJob == null) { "Audio processing worker has already started" }
        workerJob = scope.launch {
            while (isActive) {
                val processed = try {
                    processor.processNext()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    LOGGER.atError().setCause(cause).log("Audio worker iteration failed")
                    false
                }
                if (!processed) delay(pollInterval.toMillis())
            }
        }
    }

    override fun close() {
        val running = workerJob ?: return
        runBlocking { running.cancelAndJoin() }
        workerJob = null
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("com.sova.audio.worker")
    }
}

internal fun boundedErrorCode(value: String): String = value.take(MAX_ERROR_CODE_LENGTH)

private const val MAX_ERROR_CODE_LENGTH = 100
