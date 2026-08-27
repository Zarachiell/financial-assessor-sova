package com.sova.audio.application

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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessAudioMessageTest {
    private val job = ClaimedAudioJob(
        jobId = UUID.fromString("30000000-0000-0000-0000-000000000001"),
        attachmentId = UUID.fromString("40000000-0000-0000-0000-000000000001"),
        messageSid = "SM-AUDIO",
        householdId = UUID.fromString("10000000-0000-0000-0000-000000000000"),
        userId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
        from = WhatsappAddress("whatsapp:+15550000001"),
        to = WhatsappAddress("whatsapp:+15550000000"),
        mediaUrl = "https://api.twilio.com/media/ME1",
        contentType = "audio/ogg",
        attempt = 1,
    )
    private val clock = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `valid transcription reuses textual conversation with audio origin`() = runBlocking {
        val audioRepository = RecordingAudioRepository(job)
        val whatsappRepository = RecordingWhatsappRepository()
        val sender = RecordingSender()
        var conversationContext: WhatsappConversationContext? = null
        val processor = processor(
            audioRepository = audioRepository,
            whatsappRepository = whatsappRepository,
            sender = sender,
            speechToText = SpeechToTextProvider { _, _ ->
                TranscriptionResult(
                    text = "Gastei 35 reais no almoço hoje.",
                    provider = "gemini",
                    model = "gemini-test",
                    durationMs = 4_000,
                    language = "pt-BR",
                )
            },
            conversation = WhatsappTextConversation { context ->
                conversationContext = context
                "Despesa registrada."
            },
        )

        assertTrue(processor.processNext())

        assertEquals(WhatsappMessageOrigin.AUDIO, conversationContext?.origin)
        assertEquals("SM-AUDIO", conversationContext?.inboundMessageSid)
        assertEquals("Gastei 35 reais no almoço hoje.", conversationContext?.text)
        assertEquals(listOf("DOWNLOADED", "PROCESSING", "TRANSCRIBED", "COMPLETED"), audioRepository.events)
        assertEquals(listOf("Despesa registrada."), sender.messages)
        assertEquals(1, whatsappRepository.outbound.size)
    }

    @Test
    fun `blank transcription does not call financial conversation`() = runBlocking {
        val audioRepository = RecordingAudioRepository(job)
        val sender = RecordingSender()
        var conversationCalled = false
        val processor = processor(
            audioRepository = audioRepository,
            whatsappRepository = RecordingWhatsappRepository(),
            sender = sender,
            speechToText = SpeechToTextProvider { _, _ ->
                TranscriptionResult("   ", "gemini", "gemini-test", 2_000)
            },
            conversation = WhatsappTextConversation {
                conversationCalled = true
                "should not happen"
            },
        )

        processor.processNext()

        assertFalse(conversationCalled)
        assertEquals(AudioFailure.BLANK_TRANSCRIPTION, audioRepository.failed)
        assertTrue(sender.messages.single().contains("Não consegui entender"))
    }

    @Test
    fun `transient download failure schedules a bounded retry without response`() = runBlocking {
        val audioRepository = RecordingAudioRepository(job)
        val sender = RecordingSender()
        val processor = processor(
            audioRepository = audioRepository,
            whatsappRepository = RecordingWhatsappRepository(),
            sender = sender,
            downloader = WhatsappMediaDownloader {
                throw MediaDownloadException(AudioFailure.DOWNLOAD_UNAVAILABLE)
            },
        )

        processor.processNext()

        assertEquals(AudioFailure.DOWNLOAD_UNAVAILABLE, audioRepository.retryFailure)
        assertEquals(Instant.parse("2026-08-27T12:00:05Z"), audioRepository.retryAt)
        assertTrue(sender.messages.isEmpty())
    }

    private fun processor(
        audioRepository: RecordingAudioRepository,
        whatsappRepository: RecordingWhatsappRepository,
        sender: RecordingSender,
        downloader: WhatsappMediaDownloader = WhatsappMediaDownloader {
            DownloadedMedia(byteArrayOf(1, 2, 3), "audio/ogg", "audio.ogg")
        },
        speechToText: SpeechToTextProvider = SpeechToTextProvider { _, _ ->
            TranscriptionResult("Quanto gastamos hoje?", "gemini", "gemini-test", 3_000)
        },
        conversation: WhatsappTextConversation = WhatsappTextConversation { "Resposta" },
    ) = ProcessAudioMessage(
        repository = audioRepository,
        whatsappRepository = whatsappRepository,
        downloader = downloader,
        speechToText = speechToText,
        conversation = conversation,
        senderAddress = job.to,
        sender = sender,
        config = AudioProcessingConfig(
            maxAudioBytes = 1024,
            maxAudioDuration = Duration.ofMinutes(5),
            maxAttempts = 3,
            retryBaseDelay = Duration.ofSeconds(5),
        ),
        clock = clock,
    )
}

private class RecordingAudioRepository(
    private var next: ClaimedAudioJob?,
) : AudioProcessingRepository {
    val events = mutableListOf<String>()
    var retryFailure: AudioFailure? = null
    var retryAt: Instant? = null
    var failed: AudioFailure? = null

    override suspend fun claimNext(maxAttempts: Int, staleBefore: Instant): ClaimedAudioJob? =
        next.also { next = null }

    override suspend fun markDownloaded(job: ClaimedAudioJob, sizeBytes: Long) {
        events += "DOWNLOADED"
    }

    override suspend fun markProcessing(job: ClaimedAudioJob) {
        events += "PROCESSING"
    }

    override suspend fun saveTranscription(job: ClaimedAudioJob, result: TranscriptionResult) {
        events += "TRANSCRIBED"
    }

    override suspend fun markCompleted(job: ClaimedAudioJob) {
        events += "COMPLETED"
    }

    override suspend fun scheduleRetry(job: ClaimedAudioJob, failure: AudioFailure, availableAt: Instant) {
        retryFailure = failure
        retryAt = availableAt
    }

    override suspend fun markFailed(job: ClaimedAudioJob, failure: AudioFailure) {
        failed = failure
    }
}

private class RecordingWhatsappRepository : WhatsappRepository {
    val outbound = mutableListOf<StoredWhatsappMessage>()

    override suspend fun provision(household: HouseholdProvisioning) = Unit

    override suspend fun findActiveUser(address: WhatsappAddress): AuthorizedWhatsappUser? = null

    override suspend fun saveInboundIfNew(
        message: StoredWhatsappMessage,
        attachments: List<StoredWhatsappAttachment>,
        audioJobAttachmentId: UUID?,
    ): Boolean = true

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
