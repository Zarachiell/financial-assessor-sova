package com.sova.image.application

import com.sova.media.application.MediaDownloadException
import com.sova.media.application.MediaDownloadFailure
import com.sova.media.application.MediaStorage
import com.sova.media.application.WhatsappMediaDownloader
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ImageDocumentType {
    RECEIPT,
    INVOICE,
    PAYMENT_PROOF,
    OTHER,
}

data class ImageInput(
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String,
)

data class ImageAnalysisContext(
    val caption: String,
    val knownCategories: List<String>,
    val currentDate: LocalDate,
)

data class ImageAnalysisResult(
    val documentType: ImageDocumentType,
    val merchant: String?,
    val amount: BigDecimal?,
    val occurredOn: LocalDate?,
    val suggestedCategory: String?,
    val confidence: BigDecimal,
    val merchantConfidence: BigDecimal?,
    val amountConfidence: BigDecimal?,
    val dateConfidence: BigDecimal?,
    val categoryConfidence: BigDecimal?,
    val provider: String,
    val model: String,
) {
    init {
        amount?.let { require(it.signum() > 0) { "Image candidate amount must be positive" } }
        listOfNotNull(
            confidence,
            merchantConfidence,
            amountConfidence,
            dateConfidence,
            categoryConfidence,
        ).forEach { value -> require(value in BigDecimal.ZERO..BigDecimal.ONE) { "Confidence must be between zero and one" } }
        require(provider.isNotBlank()) { "Image analysis provider must not be blank" }
        require(model.isNotBlank()) { "Image analysis model must not be blank" }
    }
}

fun interface ImageAnalysisProvider {
    suspend fun analyze(image: ImageInput, context: ImageAnalysisContext): ImageAnalysisResult
}

enum class ImageFailure(val code: String, val retryable: Boolean) {
    DOWNLOAD_TIMEOUT("download_timeout", true),
    DOWNLOAD_RATE_LIMITED("download_rate_limited", true),
    DOWNLOAD_UNAVAILABLE("download_unavailable", true),
    DOWNLOAD_AUTHENTICATION("download_authentication", false),
    DOWNLOAD_REJECTED("download_rejected", false),
    FILE_TOO_LARGE("file_too_large", false),
    UNSUPPORTED_IMAGE("unsupported_image", false),
    INVALID_IMAGE("invalid_image", false),
    VISION_TIMEOUT("vision_timeout", true),
    VISION_RATE_LIMITED("vision_rate_limited", true),
    VISION_UNAVAILABLE("vision_unavailable", true),
    VISION_REJECTED("vision_rejected", false),
    VISION_INVALID_RESPONSE("vision_invalid_response", false),
    ORCHESTRATOR_ERROR("orchestrator_error", false),
    STORAGE_FAILURE("storage_failure", false),
    OUTBOUND_FAILURE("outbound_failure", false),
}

class ImageAnalysisException(val failure: ImageFailure, cause: Throwable? = null) : RuntimeException(failure.code, cause)

data class ClaimedImageJob(
    val jobId: UUID,
    val attachmentId: UUID,
    val messageSid: String,
    val caption: String,
    val householdId: UUID,
    val userId: UUID,
    val from: WhatsappAddress,
    val mediaUrl: String,
    val contentType: String,
    val attempt: Int,
)

interface ImageProcessingRepository {
    suspend fun claimNext(maxAttempts: Int, staleBefore: Instant): ClaimedImageJob?
    suspend fun markDownloaded(job: ClaimedImageJob, sizeBytes: Long)
    suspend fun markProcessing(job: ClaimedImageJob)
    suspend fun saveAnalysis(
        job: ClaimedImageJob,
        result: ImageAnalysisResult,
        storageKey: String,
        sha256Hex: String,
        sizeBytes: Long,
        confirmationRequired: Boolean,
        analyzedAt: Instant,
    )
    suspend fun markCompleted(job: ClaimedImageJob)
    suspend fun scheduleRetry(job: ClaimedImageJob, failure: ImageFailure, availableAt: Instant)
    suspend fun markFailed(job: ClaimedImageJob, failure: ImageFailure)
}

data class ImageProcessingConfig(
    val maxImageBytes: Long,
    val maxAttempts: Int = 3,
    val minimumAutoConfidence: BigDecimal = BigDecimal("0.85"),
    val retryBaseDelay: Duration = Duration.ofSeconds(5),
    val processingLease: Duration = Duration.ofMinutes(10),
    val pollInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(maxImageBytes > 0) { "Maximum image size must be positive" }
        require(maxAttempts in 1..10) { "Maximum image attempts must be between 1 and 10" }
        require(minimumAutoConfidence in BigDecimal.ZERO..BigDecimal.ONE) {
            "Minimum image confidence must be between zero and one"
        }
    }
}

class ProcessImageMessage(
    private val repository: ImageProcessingRepository,
    private val whatsappRepository: WhatsappRepository,
    private val downloader: WhatsappMediaDownloader,
    private val storage: MediaStorage,
    private val analyzer: ImageAnalysisProvider,
    private val conversation: WhatsappTextConversation,
    private val senderAddress: WhatsappAddress,
    private val sender: WhatsappMessageSender,
    private val categories: List<String>,
    private val config: ImageProcessingConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun processNext(): Boolean {
        val job = repository.claimNext(config.maxAttempts, clock.instant().minus(config.processingLease)) ?: return false
        val startedAt = System.nanoTime()
        return try {
            process(job)
            log(job, "completed", startedAt)
            true
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: MediaDownloadException) {
            handleFailure(job, cause.failure.toImageFailure(), startedAt)
            true
        } catch (cause: ImageAnalysisException) {
            handleFailure(job, cause.failure, startedAt)
            true
        } catch (cause: Exception) {
            LOGGER.atError().setCause(cause)
                .addKeyValue("messageSid", job.messageSid)
                .addKeyValue("attachmentId", job.attachmentId)
                .log("Unexpected image processing failure")
            finishWithFailure(job, ImageFailure.ORCHESTRATOR_ERROR)
            log(job, ImageFailure.ORCHESTRATOR_ERROR.code, startedAt)
            true
        }
    }

    private suspend fun process(job: ClaimedImageJob) {
        val media = downloader.download(job.mediaUrl)
        if (media.bytes.size.toLong() > config.maxImageBytes) {
            throw MediaDownloadException(MediaDownloadFailure.FILE_TOO_LARGE)
        }
        validateImage(media.contentType, media.bytes)
        repository.markDownloaded(job, media.bytes.size.toLong())
        val stored = try {
            storage.store(job.householdId, job.attachmentId, media.contentType, media.bytes)
        } catch (cause: Exception) {
            throw ImageAnalysisException(ImageFailure.STORAGE_FAILURE, cause)
        }
        repository.markProcessing(job)
        val result = analyzer.analyze(
            ImageInput(media.bytes, media.contentType, media.filename),
            ImageAnalysisContext(job.caption, categories, LocalDate.now(clock)),
        )
        val confirmationRequired = requiresConfirmation(result)
        repository.saveAnalysis(
            job,
            result,
            stored.storageKey,
            stored.sha256Hex,
            stored.sizeBytes,
            confirmationRequired,
            clock.instant(),
        )
        val response = if (confirmationRequired) {
            confirmationPrompt(result)
        } else {
            try {
                conversation.respond(
                    WhatsappConversationContext(
                        householdId = job.householdId,
                        userId = job.userId,
                        inboundMessageSid = job.messageSid,
                        text = analysisPrompt(job, result),
                        origin = WhatsappMessageOrigin.IMAGE,
                        attachmentId = job.attachmentId,
                    ),
                ).takeIf(String::isNotBlank) ?: SAFE_FAILURE_RESPONSE
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                throw ImageAnalysisException(ImageFailure.ORCHESTRATOR_ERROR, cause)
            }
        }
        deliver(job, response)
        repository.markCompleted(job)
    }

    private fun requiresConfirmation(result: ImageAnalysisResult): Boolean =
        result.confidence < config.minimumAutoConfidence ||
            result.amount == null ||
            result.occurredOn == null ||
            result.amountConfidence?.let { it < config.minimumAutoConfidence } != false ||
            result.dateConfidence?.let { it < config.minimumAutoConfidence } != false

    private suspend fun handleFailure(job: ClaimedImageJob, failure: ImageFailure, startedAt: Long) {
        if (failure.retryable && job.attempt < config.maxAttempts) {
            val delay = config.retryBaseDelay.multipliedBy(1L shl (job.attempt - 1).coerceAtLeast(0))
            repository.scheduleRetry(job, failure, clock.instant().plus(delay))
            log(job, "retry_${failure.code}", startedAt)
        } else {
            finishWithFailure(job, failure)
            log(job, failure.code, startedAt)
        }
    }

    private suspend fun finishWithFailure(job: ClaimedImageJob, failure: ImageFailure) {
        val response = when (failure) {
            ImageFailure.FILE_TOO_LARGE -> "Essa imagem é grande demais para eu processar. Envie uma imagem menor."
            ImageFailure.UNSUPPORTED_IMAGE, ImageFailure.INVALID_IMAGE ->
                "Não consegui validar essa imagem. Envie em JPEG, PNG ou WebP."
            else -> SAFE_FAILURE_RESPONSE
        }
        deliver(job, response)
        repository.markFailed(job, failure)
    }

    private suspend fun deliver(job: ClaimedImageJob, body: String): SendMessageResult = try {
        sender.send(job.from, body).also { result ->
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
        }
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        throw ImageAnalysisException(ImageFailure.OUTBOUND_FAILURE, cause)
    }

    private fun confirmationPrompt(result: ImageAnalysisResult): String = buildString {
        append("Consegui ler a imagem, mas preciso da sua confirmação antes de registrar: ")
        append(result.merchant ?: "estabelecimento não identificado")
        append(", ")
        append(result.amount?.let { "R$ ${it.setScale(2).toPlainString().replace('.', ',')}" } ?: "valor não identificado")
        append(", ")
        append(result.occurredOn?.toString() ?: "data não identificada")
        result.suggestedCategory?.let { append(", categoria sugerida $it") }
        append(". Responda confirmando ou corrigindo os dados.")
    }

    private fun analysisPrompt(job: ClaimedImageJob, result: ImageAnalysisResult): String = buildString {
        appendLine("Imagem financeira analisada. Use os dados apenas como candidatos e passe por tools locais.")
        appendLine("attachmentId: ${job.attachmentId}")
        appendLine("tipoDocumento: ${result.documentType}")
        appendLine("estabelecimento: ${result.merchant ?: "não identificado"}")
        appendLine("valor: ${result.amount?.toPlainString() ?: "não identificado"}")
        appendLine("data: ${result.occurredOn ?: "não identificada"}")
        appendLine("categoriaSugerida: ${result.suggestedCategory ?: "não identificada"}")
        appendLine("confiança: ${result.confidence.toPlainString()}")
        append("legendaDoUsuario: ${job.caption.ifBlank { "sem legenda" }}")
    }

    private fun log(job: ClaimedImageJob, status: String, startedAt: Long) {
        LOGGER.atInfo()
            .addKeyValue("messageSid", job.messageSid)
            .addKeyValue("attachmentId", job.attachmentId)
            .addKeyValue("contentType", job.contentType)
            .addKeyValue("status", status)
            .addKeyValue("attempt", job.attempt)
            .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
            .log("WhatsApp image job processed")
    }

    private companion object {
        const val SAFE_FAILURE_RESPONSE = "Não consegui processar sua imagem agora. Tente novamente em instantes."
        val LOGGER = LoggerFactory.getLogger("com.sova.image.processing")
    }
}

class ImageProcessingWorker(
    private val processor: ProcessImageMessage,
    private val pollInterval: Duration,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    fun start() {
        check(workerJob == null) { "Image processing worker has already started" }
        workerJob = scope.launch {
            while (isActive) {
                val processed = try {
                    processor.processNext()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    LOGGER.atError().setCause(cause).log("Image worker iteration failed")
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
        val LOGGER = LoggerFactory.getLogger("com.sova.image.worker")
    }
}

private fun validateImage(contentType: String, bytes: ByteArray) {
    val valid = when (contentType) {
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "image/webp" -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        else -> false
    }
    if (!valid) throw ImageAnalysisException(
        if (contentType in SUPPORTED_IMAGE_TYPES) ImageFailure.INVALID_IMAGE else ImageFailure.UNSUPPORTED_IMAGE,
    )
}

private fun MediaDownloadFailure.toImageFailure(): ImageFailure = when (this) {
    MediaDownloadFailure.TIMEOUT -> ImageFailure.DOWNLOAD_TIMEOUT
    MediaDownloadFailure.RATE_LIMITED -> ImageFailure.DOWNLOAD_RATE_LIMITED
    MediaDownloadFailure.UNAVAILABLE -> ImageFailure.DOWNLOAD_UNAVAILABLE
    MediaDownloadFailure.AUTHENTICATION -> ImageFailure.DOWNLOAD_AUTHENTICATION
    MediaDownloadFailure.REJECTED -> ImageFailure.DOWNLOAD_REJECTED
    MediaDownloadFailure.FILE_TOO_LARGE -> ImageFailure.FILE_TOO_LARGE
}

private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
