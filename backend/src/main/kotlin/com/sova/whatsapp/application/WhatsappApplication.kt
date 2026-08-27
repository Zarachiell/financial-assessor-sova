package com.sova.whatsapp.application

import com.sova.whatsapp.domain.AuthorizedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.domain.WhatsappMessageDirection
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class HouseholdProvisioning(
    val name: String,
    val users: List<ProvisionedWhatsappUser>,
)

data class ProvisionedWhatsappUser(
    val name: String,
    val address: WhatsappAddress,
)

data class InboundWhatsappMessage(
    val messageSid: String,
    val accountSid: String,
    val from: WhatsappAddress,
    val to: WhatsappAddress,
    val body: String,
    val media: List<InboundWhatsappMedia>,
) {
    val numMedia: Int = media.size
}

data class InboundWhatsappMedia(
    val index: Int,
    val mediaUrl: String,
    val contentType: String,
) {
    init {
        require(index >= 0) { "Media index must not be negative" }
        require(mediaUrl.isNotBlank()) { "Media URL must not be blank" }
        require(contentType.isNotBlank()) { "Media content type must not be blank" }
    }
}

data class StoredWhatsappMessage(
    val messageSid: String,
    val direction: WhatsappMessageDirection,
    val userId: UUID,
    val from: WhatsappAddress,
    val to: WhatsappAddress,
    val body: String,
    val occurredAt: Instant,
    val id: UUID = UUID.randomUUID(),
)

enum class WhatsappMediaType {
    AUDIO,
    IMAGE,
    DOCUMENT,
    OTHER,
}

enum class WhatsappAttachmentStatus {
    RECEIVED,
    DOWNLOADING,
    DOWNLOADED,
    PROCESSING,
    PROCESSED,
    FAILED,
}

data class StoredWhatsappAttachment(
    val id: UUID,
    val whatsappMessageId: UUID,
    val mediaIndex: Int,
    val mediaUrl: String,
    val contentType: String,
    val mediaType: WhatsappMediaType,
    val status: WhatsappAttachmentStatus = WhatsappAttachmentStatus.RECEIVED,
)

data class SendMessageResult(val messageSid: String) {
    init {
        require(messageSid.isNotBlank()) { "Provider message SID must not be blank" }
    }
}

fun interface WhatsappMessageSender {
    suspend fun send(to: WhatsappAddress, body: String): SendMessageResult
}

fun interface WhatsappWebhookSignatureValidator {
    fun isValid(url: String, parameters: Map<String, String>, signature: String): Boolean
}

interface WhatsappRepository {
    suspend fun provision(household: HouseholdProvisioning)

    suspend fun findActiveUser(address: WhatsappAddress): AuthorizedWhatsappUser?

    suspend fun saveInboundIfNew(
        message: StoredWhatsappMessage,
        attachments: List<StoredWhatsappAttachment> = emptyList(),
        audioJobAttachmentId: UUID? = null,
    ): Boolean

    suspend fun saveOutbound(message: StoredWhatsappMessage)
}

fun interface WhatsappWebhookHandler {
    suspend fun handle(message: InboundWhatsappMessage): WhatsappHandlingResult
}

data class WhatsappConversationContext(
    val householdId: UUID,
    val userId: UUID,
    val inboundMessageSid: String,
    val text: String,
    val origin: WhatsappMessageOrigin = WhatsappMessageOrigin.TEXT,
)

enum class WhatsappMessageOrigin {
    TEXT,
    AUDIO,
}

fun interface WhatsappTextConversation {
    suspend fun respond(context: WhatsappConversationContext): String
}

sealed interface WhatsappHandlingResult {
    data class Processed(
        val userId: UUID,
        val householdId: UUID,
        val outboundMessageSid: String,
    ) : WhatsappHandlingResult

    data class Queued(
        val userId: UUID,
        val householdId: UUID,
        val attachmentId: UUID,
    ) : WhatsappHandlingResult

    data class Duplicate(
        val userId: UUID,
        val householdId: UUID,
    ) : WhatsappHandlingResult

    data object Unauthorized : WhatsappHandlingResult
}

class HandleWhatsappMessage(
    private val repository: WhatsappRepository,
    private val senderAddress: WhatsappAddress,
    private val sender: WhatsappMessageSender,
    private val clock: Clock = Clock.systemUTC(),
    private val conversation: WhatsappTextConversation = WhatsappTextConversation { NEUTRAL_RESPONSE },
) : WhatsappWebhookHandler {
    override suspend fun handle(message: InboundWhatsappMessage): WhatsappHandlingResult {
        val user = repository.findActiveUser(message.from) ?: return WhatsappHandlingResult.Unauthorized
        val storedMessage = StoredWhatsappMessage(
            messageSid = message.messageSid,
            direction = WhatsappMessageDirection.INBOUND,
            userId = user.userId,
            from = message.from,
            to = message.to,
            body = message.body,
            occurredAt = clock.instant(),
        )
        val attachments = message.media.map { media ->
            StoredWhatsappAttachment(
                id = UUID.randomUUID(),
                whatsappMessageId = storedMessage.id,
                mediaIndex = media.index,
                mediaUrl = media.mediaUrl,
                contentType = media.normalizedContentType,
                mediaType = media.mediaType,
            )
        }
        val firstAudio = attachments.firstOrNull { it.mediaType == WhatsappMediaType.AUDIO }
        val supportedAudio = firstAudio?.takeIf { it.contentType in SUPPORTED_AUDIO_CONTENT_TYPES }
        val inserted = repository.saveInboundIfNew(
            message = storedMessage,
            attachments = attachments,
            audioJobAttachmentId = supportedAudio?.id,
        )
        if (!inserted) {
            return WhatsappHandlingResult.Duplicate(user.userId, user.householdId)
        }
        if (supportedAudio != null) {
            return WhatsappHandlingResult.Queued(user.userId, user.householdId, supportedAudio.id)
        }
        if (attachments.isNotEmpty()) {
            val sendResult = sendAndPersist(user.userId, message.from, UNSUPPORTED_MEDIA_RESPONSE)
            return WhatsappHandlingResult.Processed(user.userId, user.householdId, sendResult.messageSid)
        }

        val responseBody = if (message.body.trim().equals("ping", ignoreCase = true)) {
            "pong"
        } else {
            safelyRespond(
                WhatsappConversationContext(
                    householdId = user.householdId,
                    userId = user.userId,
                    inboundMessageSid = message.messageSid,
                    text = message.body,
                ),
            )
        }
        val sendResult = sendAndPersist(user.userId, message.from, responseBody)
        return WhatsappHandlingResult.Processed(
            userId = user.userId,
            householdId = user.householdId,
            outboundMessageSid = sendResult.messageSid,
        )
    }

    private suspend fun sendAndPersist(
        userId: UUID,
        recipient: WhatsappAddress,
        responseBody: String,
    ): SendMessageResult {
        val sendResult = sender.send(recipient, responseBody)
        whatsappRepositorySaveOutbound(userId, recipient, responseBody, sendResult)
        return sendResult
    }

    private suspend fun whatsappRepositorySaveOutbound(
        userId: UUID,
        recipient: WhatsappAddress,
        responseBody: String,
        sendResult: SendMessageResult,
    ) {
        repository.saveOutbound(
            StoredWhatsappMessage(
                messageSid = sendResult.messageSid,
                direction = WhatsappMessageDirection.OUTBOUND,
                userId = userId,
                from = senderAddress,
                to = recipient,
                body = responseBody,
                occurredAt = clock.instant(),
            ),
        )
    }

    private suspend fun safelyRespond(context: WhatsappConversationContext): String = try {
        conversation.respond(context).takeIf(String::isNotBlank) ?: SAFE_FAILURE_RESPONSE
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        LOGGER.atWarn()
            .setCause(cause)
            .addKeyValue("messageSid", context.inboundMessageSid)
            .addKeyValue("userId", context.userId)
            .addKeyValue("householdId", context.householdId)
            .log("WhatsApp conversation failed")
        SAFE_FAILURE_RESPONSE
    }

    private companion object {
        const val NEUTRAL_RESPONSE = "Mensagem recebida. Nesta fase, envie \"ping\" para testar."
        const val SAFE_FAILURE_RESPONSE = "Não consegui processar sua mensagem agora. Tente novamente em instantes."
        const val UNSUPPORTED_MEDIA_RESPONSE =
            "Ainda não consigo processar esse tipo de mídia. Nesta fase, envie texto ou áudio."
        val SUPPORTED_AUDIO_CONTENT_TYPES = setOf(
            "audio/ogg",
            "audio/mpeg",
            "audio/mp3",
            "audio/wav",
            "audio/aiff",
            "audio/aac",
            "audio/flac",
        )
        val LOGGER = LoggerFactory.getLogger("com.sova.whatsapp.conversation")
    }
}

typealias HandleWhatsappTextMessage = HandleWhatsappMessage

private val InboundWhatsappMedia.normalizedContentType: String
    get() = contentType.substringBefore(';').trim().lowercase()

private val InboundWhatsappMedia.mediaType: WhatsappMediaType
    get() = when {
        normalizedContentType.startsWith("audio/") -> WhatsappMediaType.AUDIO
        normalizedContentType.startsWith("image/") -> WhatsappMediaType.IMAGE
        normalizedContentType == "application/pdf" -> WhatsappMediaType.DOCUMENT
        else -> WhatsappMediaType.OTHER
    }

class WhatsappDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
