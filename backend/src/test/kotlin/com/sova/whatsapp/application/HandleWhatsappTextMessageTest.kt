package com.sova.whatsapp.application

import com.sova.whatsapp.domain.AuthorizedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HandleWhatsappTextMessageTest {
    private val householdId = UUID.fromString("10000000-0000-0000-0000-000000000000")
    private val userA = authorizedUser("20000000-0000-0000-0000-000000000001", "+15550000001")
    private val userB = authorizedUser("20000000-0000-0000-0000-000000000002", "+15550000002")
    private val senderAddress = WhatsappAddress("whatsapp:+15550000000")
    private val clock = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `both household users receive pong and preserve authorship`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA, userB)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappTextMessage(repository, senderAddress, sender, clock)

        val resultA = handler.handle(message("SM-A", userA.address, "  PING "))
        val resultB = handler.handle(message("SM-B", userB.address, "ping"))

        assertIs<WhatsappHandlingResult.Processed>(resultA)
        assertIs<WhatsappHandlingResult.Processed>(resultB)
        assertEquals(householdId, resultA.householdId)
        assertEquals(householdId, resultB.householdId)
        assertEquals(userA.userId, repository.inbound.getValue("SM-A").userId)
        assertEquals(userB.userId, repository.inbound.getValue("SM-B").userId)
        assertEquals(listOf("pong", "pong"), sender.sent.map { it.second })
        assertEquals(2, repository.outbound.size)
    }

    @Test
    fun `unknown sender is ignored without persistence or response`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA, userB)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappTextMessage(repository, senderAddress, sender, clock)

        val result = handler.handle(message("SM-UNKNOWN", WhatsappAddress("whatsapp:+15550000009"), "ping"))

        assertEquals(WhatsappHandlingResult.Unauthorized, result)
        assertTrue(repository.inbound.isEmpty())
        assertTrue(repository.outbound.isEmpty())
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `duplicate MessageSid is not processed or answered twice`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA, userB)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappTextMessage(repository, senderAddress, sender, clock)
        val message = message("SM-DUPLICATE", userA.address, "ping")

        val first = handler.handle(message)
        val duplicate = handler.handle(message)

        assertIs<WhatsappHandlingResult.Processed>(first)
        assertIs<WhatsappHandlingResult.Duplicate>(duplicate)
        assertEquals(1, repository.inbound.size)
        assertEquals(1, repository.outbound.size)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `supported audio is persisted and queued without synchronous response`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappMessage(repository, senderAddress, sender, clock)

        val result = handler.handle(
            message("SM-AUDIO", userA.address, "").copy(
                media = listOf(
                    InboundWhatsappMedia(0, "https://api.twilio.com/media/ME1", "audio/ogg; codecs=opus"),
                ),
            ),
        )

        val queued = assertIs<WhatsappHandlingResult.Queued>(result)
        assertEquals(queued.attachmentId, repository.queuedAudioAttachmentId)
        assertEquals(WhatsappMediaType.AUDIO, repository.attachments.single().mediaType)
        assertEquals("audio/ogg", repository.attachments.single().contentType)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `unsupported media is persisted and receives controlled response`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappMessage(repository, senderAddress, sender, clock)

        val result = handler.handle(
            message("SM-IMAGE", userA.address, "legenda").copy(
                media = listOf(InboundWhatsappMedia(0, "https://api.twilio.com/media/ME2", "image/jpeg")),
            ),
        )

        assertIs<WhatsappHandlingResult.Processed>(result)
        assertEquals(WhatsappMediaType.IMAGE, repository.attachments.single().mediaType)
        assertTrue(sender.sent.single().second.contains("texto ou áudio"))
    }

    @Test
    fun `non-ping text receives a neutral response without NLP`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappTextMessage(repository, senderAddress, sender, clock)

        handler.handle(message("SM-NEUTRAL", userA.address, "Quanto gastei?"))

        assertEquals("Mensagem recebida. Nesta fase, envie \"ping\" para testar.", sender.sent.single().second)
    }

    @Test
    fun `financial conversation receives authenticated context after inbound persistence`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA)
        val sender = RecordingWhatsappSender()
        var captured: WhatsappConversationContext? = null
        val handler = HandleWhatsappTextMessage(
            repository = repository,
            senderAddress = senderAddress,
            sender = sender,
            clock = clock,
            conversation = WhatsappTextConversation { context ->
                assertTrue("SM-FINANCE" in repository.inbound)
                captured = context
                "Resposta financeira"
            },
        )

        handler.handle(message("SM-FINANCE", userA.address, "Qual nosso saldo?"))

        assertEquals(householdId, captured?.householdId)
        assertEquals(userA.userId, captured?.userId)
        assertEquals("SM-FINANCE", captured?.inboundMessageSid)
        assertEquals("Qual nosso saldo?", captured?.text)
        assertEquals("Resposta financeira", sender.sent.single().second)
    }

    @Test
    fun `conversation failure returns a safe response without internal details`() = runBlocking {
        val repository = InMemoryWhatsappRepository(userA)
        val sender = RecordingWhatsappSender()
        val handler = HandleWhatsappTextMessage(
            repository = repository,
            senderAddress = senderAddress,
            sender = sender,
            clock = clock,
            conversation = WhatsappTextConversation { error("secret provider response") },
        )

        handler.handle(message("SM-FAIL", userA.address, "Qual nosso saldo?"))

        val response = sender.sent.single().second
        assertEquals("Não consegui processar sua mensagem agora. Tente novamente em instantes.", response)
        assertFalse(response.contains("secret"))
    }

    private fun authorizedUser(id: String, phoneNumber: String) = AuthorizedWhatsappUser(
        userId = UUID.fromString(id),
        householdId = householdId,
        address = WhatsappAddress("whatsapp:$phoneNumber"),
    )

    private fun message(messageSid: String, from: WhatsappAddress, body: String) = InboundWhatsappMessage(
        messageSid = messageSid,
        accountSid = "AC00000000000000000000000000000000",
        from = from,
        to = senderAddress,
        body = body,
        media = emptyList(),
    )
}

private class RecordingWhatsappSender : WhatsappMessageSender {
    val sent = mutableListOf<Pair<WhatsappAddress, String>>()

    override suspend fun send(to: WhatsappAddress, body: String): SendMessageResult {
        sent += to to body
        return SendMessageResult("SM-OUT-${sent.size}")
    }
}

private class InMemoryWhatsappRepository(
    vararg users: AuthorizedWhatsappUser,
) : WhatsappRepository {
    private val usersByAddress = users.associateBy { it.address }
    val inbound = linkedMapOf<String, StoredWhatsappMessage>()
    val outbound = mutableListOf<StoredWhatsappMessage>()
    val attachments = mutableListOf<StoredWhatsappAttachment>()
    var queuedAudioAttachmentId: UUID? = null

    override suspend fun provision(household: HouseholdProvisioning) = Unit

    override suspend fun findActiveUser(address: WhatsappAddress): AuthorizedWhatsappUser? = usersByAddress[address]

    override suspend fun saveInboundIfNew(
        message: StoredWhatsappMessage,
        attachments: List<StoredWhatsappAttachment>,
        audioJobAttachmentId: UUID?,
    ): Boolean {
        if (inbound.putIfAbsent(message.messageSid, message) != null) return false
        this.attachments += attachments
        queuedAudioAttachmentId = audioJobAttachmentId
        return true
    }

    override suspend fun saveOutbound(message: StoredWhatsappMessage) {
        outbound += message
    }
}
