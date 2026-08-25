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
    val numMedia: Int,
)

data class StoredWhatsappMessage(
    val messageSid: String,
    val direction: WhatsappMessageDirection,
    val userId: UUID,
    val from: WhatsappAddress,
    val to: WhatsappAddress,
    val body: String,
    val occurredAt: Instant,
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

    suspend fun saveInboundIfNew(message: StoredWhatsappMessage): Boolean

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
)

fun interface WhatsappTextConversation {
    suspend fun respond(context: WhatsappConversationContext): String
}

sealed interface WhatsappHandlingResult {
    data class Processed(
        val userId: UUID,
        val householdId: UUID,
        val outboundMessageSid: String,
    ) : WhatsappHandlingResult

    data class Duplicate(
        val userId: UUID,
        val householdId: UUID,
    ) : WhatsappHandlingResult

    data object Unauthorized : WhatsappHandlingResult
}

class HandleWhatsappTextMessage(
    private val repository: WhatsappRepository,
    private val senderAddress: WhatsappAddress,
    private val sender: WhatsappMessageSender,
    private val clock: Clock = Clock.systemUTC(),
    private val conversation: WhatsappTextConversation = WhatsappTextConversation { NEUTRAL_RESPONSE },
) : WhatsappWebhookHandler {
    override suspend fun handle(message: InboundWhatsappMessage): WhatsappHandlingResult {
        val user = repository.findActiveUser(message.from) ?: return WhatsappHandlingResult.Unauthorized
        val occurredAt = clock.instant()
        val inserted = repository.saveInboundIfNew(
            StoredWhatsappMessage(
                messageSid = message.messageSid,
                direction = WhatsappMessageDirection.INBOUND,
                userId = user.userId,
                from = message.from,
                to = message.to,
                body = message.body,
                occurredAt = occurredAt,
            ),
        )
        if (!inserted) {
            return WhatsappHandlingResult.Duplicate(user.userId, user.householdId)
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
        val sendResult = sender.send(message.from, responseBody)
        repository.saveOutbound(
            StoredWhatsappMessage(
                messageSid = sendResult.messageSid,
                direction = WhatsappMessageDirection.OUTBOUND,
                userId = user.userId,
                from = senderAddress,
                to = message.from,
                body = responseBody,
                occurredAt = clock.instant(),
            ),
        )

        return WhatsappHandlingResult.Processed(
            userId = user.userId,
            householdId = user.householdId,
            outboundMessageSid = sendResult.messageSid,
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
        val LOGGER = LoggerFactory.getLogger("com.sova.whatsapp.conversation")
    }
}

class WhatsappDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
