package com.sova.image.application

import com.sova.media.application.DownloadedMedia
import com.sova.media.application.MediaStorage
import com.sova.media.application.StoredMedia
import com.sova.media.application.StoredMediaContent
import com.sova.media.application.WhatsappMediaDownloader
import com.sova.whatsapp.application.HouseholdProvisioning
import com.sova.whatsapp.application.SendMessageResult
import com.sova.whatsapp.application.StoredWhatsappAttachment
import com.sova.whatsapp.application.StoredWhatsappMessage
import com.sova.whatsapp.application.WhatsappConversationContext
import com.sova.whatsapp.application.WhatsappMessageOrigin
import com.sova.whatsapp.application.WhatsappMessageSender
import com.sova.whatsapp.application.WhatsappRepository
import com.sova.whatsapp.application.WhatsappTextConversation
import com.sova.whatsapp.domain.AuthorizedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessImageMessageTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)
    private val senderAddress = WhatsappAddress("whatsapp:+15550000000")

    @Test
    fun `high confidence image is stored analyzed and routed to financial conversation`() = runBlocking {
        val repository = RecordingImageRepository(job())
        val whatsapp = RecordingWhatsappRepository()
        val sender = RecordingSender()
        var conversationContext: WhatsappConversationContext? = null
        val processor = processor(
            repository = repository,
            whatsapp = whatsapp,
            sender = sender,
            result = analysis(confidence = "0.95"),
            conversation = WhatsappTextConversation { context ->
                conversationContext = context
                "Despesa registrada com segurança."
            },
        )

        assertTrue(processor.processNext())

        assertEquals(listOf("DOWNLOADED", "PROCESSING", "ANALYZED", "COMPLETED"), repository.events)
        assertFalse(repository.confirmationRequired ?: true)
        assertEquals(WhatsappMessageOrigin.IMAGE, conversationContext?.origin)
        assertEquals(repository.job.attachmentId, conversationContext?.attachmentId)
        assertTrue(conversationContext?.text.orEmpty().contains("Padaria Sova"))
        assertEquals("Despesa registrada com segurança.", sender.messages.single())
        assertEquals(1, whatsapp.outbound.size)
    }

    @Test
    fun `low confidence image asks confirmation without invoking financial conversation`() = runBlocking {
        val repository = RecordingImageRepository(job())
        val sender = RecordingSender()
        var conversationCalled = false
        val processor = processor(
            repository = repository,
            whatsapp = RecordingWhatsappRepository(),
            sender = sender,
            result = analysis(confidence = "0.60"),
            conversation = WhatsappTextConversation {
                conversationCalled = true
                "não deveria chamar"
            },
        )

        assertTrue(processor.processNext())

        assertFalse(conversationCalled)
        assertTrue(repository.confirmationRequired == true)
        assertTrue(sender.messages.single().contains("preciso da sua confirmação"))
        assertTrue(sender.messages.single().contains("R$ 42,90"))
    }

    @Test
    fun `invalid image signature is rejected before storage and vision`() = runBlocking {
        val repository = RecordingImageRepository(job())
        val sender = RecordingSender()
        var analyzed = false
        val processor = ProcessImageMessage(
            repository = repository,
            whatsappRepository = RecordingWhatsappRepository(),
            downloader = WhatsappMediaDownloader { DownloadedMedia(byteArrayOf(1, 2, 3), "image/png", "image.png") },
            storage = RecordingStorage(),
            analyzer = ImageAnalysisProvider { _, _ ->
                analyzed = true
                analysis("0.95")
            },
            conversation = WhatsappTextConversation { "não deveria chamar" },
            senderAddress = senderAddress,
            sender = sender,
            categories = listOf("Alimentação"),
            config = ImageProcessingConfig(maxImageBytes = 1024),
            clock = clock,
        )

        assertTrue(processor.processNext())

        assertFalse(analyzed)
        assertEquals(ImageFailure.INVALID_IMAGE, repository.failed)
        assertTrue(sender.messages.single().contains("JPEG, PNG ou WebP"))
    }

    private fun processor(
        repository: RecordingImageRepository,
        whatsapp: RecordingWhatsappRepository,
        sender: RecordingSender,
        result: ImageAnalysisResult,
        conversation: WhatsappTextConversation,
    ) = ProcessImageMessage(
        repository = repository,
        whatsappRepository = whatsapp,
        downloader = WhatsappMediaDownloader { DownloadedMedia(PNG, "image/png", "image.png") },
        storage = RecordingStorage(),
        analyzer = ImageAnalysisProvider { _, context ->
            assertEquals("comprovante do café", context.caption)
            assertEquals(LocalDate.of(2026, 8, 30), context.currentDate)
            result
        },
        conversation = conversation,
        senderAddress = senderAddress,
        sender = sender,
        categories = listOf("Alimentação"),
        config = ImageProcessingConfig(maxImageBytes = 1024),
        clock = clock,
    )

    private fun job() = ClaimedImageJob(
        jobId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        attachmentId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
        messageSid = "MM-IMAGE",
        caption = "comprovante do café",
        householdId = UUID.fromString("30000000-0000-0000-0000-000000000001"),
        userId = UUID.fromString("40000000-0000-0000-0000-000000000001"),
        from = WhatsappAddress("whatsapp:+15550000001"),
        mediaUrl = "https://api.twilio.com/media/ME-IMAGE",
        contentType = "image/png",
        attempt = 1,
    )

    private fun analysis(confidence: String) = ImageAnalysisResult(
        documentType = ImageDocumentType.RECEIPT,
        merchant = "Padaria Sova",
        amount = BigDecimal("42.90"),
        occurredOn = LocalDate.of(2026, 8, 30),
        suggestedCategory = "Alimentação",
        confidence = BigDecimal(confidence),
        merchantConfidence = BigDecimal(confidence),
        amountConfidence = BigDecimal(confidence),
        dateConfidence = BigDecimal(confidence),
        categoryConfidence = BigDecimal(confidence),
        provider = "gemini",
        model = "gemini-test",
    )

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}

private class RecordingImageRepository(val job: ClaimedImageJob) : ImageProcessingRepository {
    var claimed = false
    val events = mutableListOf<String>()
    var confirmationRequired: Boolean? = null
    var failed: ImageFailure? = null

    override suspend fun claimNext(maxAttempts: Int, staleBefore: Instant): ClaimedImageJob? =
        if (claimed) null else job.also { claimed = true }

    override suspend fun markDownloaded(job: ClaimedImageJob, sizeBytes: Long) {
        events += "DOWNLOADED"
    }

    override suspend fun markProcessing(job: ClaimedImageJob) {
        events += "PROCESSING"
    }

    override suspend fun saveAnalysis(
        job: ClaimedImageJob,
        result: ImageAnalysisResult,
        storageKey: String,
        sha256Hex: String,
        sizeBytes: Long,
        confirmationRequired: Boolean,
        analyzedAt: Instant,
    ) {
        events += "ANALYZED"
        this.confirmationRequired = confirmationRequired
    }

    override suspend fun markCompleted(job: ClaimedImageJob) {
        events += "COMPLETED"
    }

    override suspend fun scheduleRetry(job: ClaimedImageJob, failure: ImageFailure, availableAt: Instant) = Unit

    override suspend fun markFailed(job: ClaimedImageJob, failure: ImageFailure) {
        failed = failure
    }
}

private class RecordingStorage : MediaStorage {
    override suspend fun store(
        householdId: UUID,
        attachmentId: UUID,
        contentType: String,
        bytes: ByteArray,
    ) = StoredMedia("$householdId/$attachmentId.png", "a".repeat(64), bytes.size.toLong())

    override suspend fun read(storageKey: String, expectedSha256Hex: String, contentType: String) =
        StoredMediaContent(byteArrayOf(), contentType, expectedSha256Hex)
}

private class RecordingWhatsappRepository : WhatsappRepository {
    val outbound = mutableListOf<StoredWhatsappMessage>()
    override suspend fun provision(household: HouseholdProvisioning) = Unit
    override suspend fun findActiveUser(address: WhatsappAddress): AuthorizedWhatsappUser? = null
    override suspend fun saveInboundIfNew(
        message: StoredWhatsappMessage,
        attachments: List<StoredWhatsappAttachment>,
        audioJobAttachmentId: UUID?,
        imageJobAttachmentId: UUID?,
    ) = true
    override suspend fun saveOutbound(message: StoredWhatsappMessage) {
        outbound += message
    }
}

private class RecordingSender : WhatsappMessageSender {
    val messages = mutableListOf<String>()
    override suspend fun send(to: WhatsappAddress, body: String): SendMessageResult {
        messages += body
        return SendMessageResult("SM-OUT-${messages.size}")
    }
}
