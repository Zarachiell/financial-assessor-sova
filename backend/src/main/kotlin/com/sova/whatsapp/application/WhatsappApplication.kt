package com.sova.whatsapp.application

import com.sova.whatsapp.domain.AuthorizedWhatsappUser
import com.sova.whatsapp.domain.WhatsappAddress
import com.sova.whatsapp.domain.WhatsappMessageDirection
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
            NEUTRAL_RESPONSE
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

    private companion object {
        const val NEUTRAL_RESPONSE = "Mensagem recebida. Nesta fase, envie \"ping\" para testar."
    }
}

class WhatsappDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
