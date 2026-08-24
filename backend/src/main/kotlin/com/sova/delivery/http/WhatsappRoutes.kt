package com.sova.delivery.http

import com.sova.whatsapp.application.InboundWhatsappMessage
import com.sova.whatsapp.application.WhatsappHandlingResult
import com.sova.whatsapp.application.WhatsappWebhookHandler
import com.sova.whatsapp.application.WhatsappWebhookSignatureValidator
import com.sova.whatsapp.domain.WhatsappAddress
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

data class WhatsappHttpDependencies(
    val expectedAccountSid: String,
    val expectedSenderAddress: WhatsappAddress,
    val signatureValidator: WhatsappWebhookSignatureValidator,
    val handler: WhatsappWebhookHandler,
)

fun Route.whatsappRoutes(dependencies: WhatsappHttpDependencies) {
    post("/webhooks/twilio/whatsapp") {
        val parameters = call.receiveParameters()
        val signature = call.request.headers[TWILIO_SIGNATURE_HEADER].orEmpty()
        if (!dependencies.signatureValidator.isValid(
                url = call.externalRequestUrl(),
                parameters = parameters.asSingleValueMap(),
                signature = signature,
            )
        ) {
            logResult(status = "invalid_signature")
            call.respondText("", status = HttpStatusCode.Forbidden)
            return@post
        }

        val payload = parameters.toWhatsappPayload()
        if (payload == null) {
            logResult(status = "invalid_payload")
            call.respondText("", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (payload.accountSid != dependencies.expectedAccountSid) {
            logResult(status = "unexpected_account", messageSid = payload.messageSid)
            call.respondText("", status = HttpStatusCode.Forbidden)
            return@post
        }
        if (payload.to != dependencies.expectedSenderAddress) {
            logResult(status = "unexpected_receiver", messageSid = payload.messageSid)
            call.respondText("", status = HttpStatusCode.Forbidden)
            return@post
        }

        when (val result = dependencies.handler.handle(payload)) {
            is WhatsappHandlingResult.Processed -> logResult(
                status = "processed",
                messageSid = payload.messageSid,
                userId = result.userId.toString(),
                householdId = result.householdId.toString(),
            )

            is WhatsappHandlingResult.Duplicate -> logResult(
                status = "duplicate",
                messageSid = payload.messageSid,
                userId = result.userId.toString(),
                householdId = result.householdId.toString(),
            )

            WhatsappHandlingResult.Unauthorized -> logResult(
                status = "unauthorized_sender",
                messageSid = payload.messageSid,
            )
        }
        call.respondText("", status = HttpStatusCode.OK)
    }
}

private fun Parameters.toWhatsappPayload(): InboundWhatsappMessage? {
    val messageSid = this["MessageSid"]?.takeIf(String::isNotBlank) ?: return null
    val accountSid = this["AccountSid"]?.takeIf(String::isNotBlank) ?: return null
    val from = this["From"]?.let(::validAddressOrNull) ?: return null
    val to = this["To"]?.let(::validAddressOrNull) ?: return null
    val body = this["Body"] ?: return null
    val numMedia = this["NumMedia"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return InboundWhatsappMessage(messageSid, accountSid, from, to, body, numMedia)
}

private fun validAddressOrNull(value: String): WhatsappAddress? =
    runCatching { WhatsappAddress(value) }.getOrNull()

private fun Parameters.asSingleValueMap(): Map<String, String> =
    entries().associate { entry -> entry.key to entry.value.lastOrNull().orEmpty() }

private fun ApplicationCall.externalRequestUrl(): String {
    val origin = request.origin
    val defaultPort = (origin.scheme == "http" && origin.serverPort == 80) ||
        (origin.scheme == "https" && origin.serverPort == 443)
    val port = if (defaultPort) "" else ":${origin.serverPort}"
    return "${origin.scheme}://${origin.serverHost}$port${request.uri}"
}

private fun logResult(
    status: String,
    messageSid: String? = null,
    userId: String? = null,
    householdId: String? = null,
) {
    val event = LOGGER.atInfo().addKeyValue("status", status)
    messageSid?.let { event.addKeyValue("messageSid", it) }
    userId?.let { event.addKeyValue("userId", it) }
    householdId?.let { event.addKeyValue("householdId", it) }
    event.log("WhatsApp webhook processed")
}

private const val TWILIO_SIGNATURE_HEADER = "X-Twilio-Signature"
private val LOGGER = LoggerFactory.getLogger("com.sova.whatsapp.webhook")
