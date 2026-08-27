package com.sova.delivery.http

import com.sova.DatabaseHealth
import com.sova.module
import com.sova.whatsapp.application.InboundWhatsappMessage
import com.sova.whatsapp.application.WhatsappDeliveryException
import com.sova.whatsapp.application.WhatsappHandlingResult
import com.sova.whatsapp.application.WhatsappWebhookHandler
import com.sova.whatsapp.application.WhatsappWebhookSignatureValidator
import com.sova.whatsapp.domain.WhatsappAddress
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsappRoutesTest {
    private val userId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val householdId = UUID.fromString("10000000-0000-0000-0000-000000000000")

    @Test
    fun `valid form webhook uses the public forwarded URL and reaches the handler`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler {
            WhatsappHandlingResult.Processed(userId, householdId, "SM-OUT")
        }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "valid", forwarded = true)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ping", handler.received?.body)
        assertEquals("SM-IN", handler.received?.messageSid)
        assertEquals(0, handler.received?.numMedia)
        assertEquals("https://sova.example.com/webhooks/twilio/whatsapp", validator.url)
        assertEquals("future-value", validator.parameters?.get("FutureParameter"))
        assertEquals("valid", validator.signature)
    }

    @Test
    fun `audio webhook maps all Twilio media metadata`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { WhatsappHandlingResult.Queued(userId, householdId, UUID.randomUUID()) }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = client.submitForm(
            url = "/webhooks/twilio/whatsapp",
            formParameters = parameters {
                append("MessageSid", "SM-AUDIO")
                append("AccountSid", ACCOUNT_SID)
                append("From", "whatsapp:+15550000001")
                append("To", "whatsapp:+15550000000")
                append("NumMedia", "2")
                append("MediaUrl0", "https://api.twilio.com/media/ME1")
                append("MediaContentType0", "audio/ogg")
                append("MediaUrl1", "https://api.twilio.com/media/ME2")
                append("MediaContentType1", "image/jpeg")
            },
        ) { header("X-Twilio-Signature", "valid") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", handler.received?.body)
        assertEquals(2, handler.received?.numMedia)
        assertEquals("audio/ogg", handler.received?.media?.first()?.contentType)
        assertEquals("https://api.twilio.com/media/ME2", handler.received?.media?.last()?.mediaUrl)
    }

    @Test
    fun `invalid signature returns forbidden before parsing or handling`() = testApplication {
        val validator = RecordingSignatureValidator(valid = false)
        val handler = RecordingHandler { WhatsappHandlingResult.Unauthorized }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "invalid")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(handler.received)
    }

    @Test
    fun `valid signature with malformed form returns bad request`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { WhatsappHandlingResult.Unauthorized }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = client.submitForm(
            url = "/webhooks/twilio/whatsapp",
            formParameters = parameters {
                append("MessageSid", "SM-IN")
                append("AccountSid", ACCOUNT_SID)
            },
        ) {
            header("X-Twilio-Signature", "valid")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(handler.received)
    }

    @Test
    fun `unexpected Twilio account is rejected without handling`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { WhatsappHandlingResult.Unauthorized }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "valid", accountSid = "AC-OTHER")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(handler.received)
    }

    @Test
    fun `unexpected Twilio receiver is rejected without handling`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { WhatsappHandlingResult.Unauthorized }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "valid", to = "whatsapp:+15550000008")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(handler.received)
    }

    @Test
    fun `unauthorized sender receives an empty success response`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { WhatsappHandlingResult.Unauthorized }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "valid")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().isEmpty())
    }

    @Test
    fun `provider error is translated without exposing internal details`() = testApplication {
        val validator = RecordingSignatureValidator(valid = true)
        val handler = RecordingHandler { throw WhatsappDeliveryException("sensitive provider detail") }
        application { module(DatabaseHealth { true }, dependencies(validator, handler)) }

        val response = postWebhook(signature = "valid")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(body.contains("WHATSAPP_PROVIDER_UNAVAILABLE"))
        assertFalse(body.contains("sensitive provider detail"))
    }

    private fun dependencies(
        validator: WhatsappWebhookSignatureValidator,
        handler: WhatsappWebhookHandler,
    ) = WhatsappHttpDependencies(
        expectedAccountSid = ACCOUNT_SID,
        expectedSenderAddress = WhatsappAddress("whatsapp:+15550000000"),
        signatureValidator = validator,
        handler = handler,
    )

    private suspend fun ApplicationTestBuilder.postWebhook(
        signature: String,
        accountSid: String = ACCOUNT_SID,
        to: String = "whatsapp:+15550000000",
        forwarded: Boolean = false,
    ): HttpResponse = client.submitForm(
        url = "/webhooks/twilio/whatsapp",
        formParameters = parameters {
            append("MessageSid", "SM-IN")
            append("AccountSid", accountSid)
            append("From", "whatsapp:+15550000001")
            append("To", to)
            append("Body", "ping")
            append("NumMedia", "0")
            append("FutureParameter", "future-value")
        },
    ) {
        header("X-Twilio-Signature", signature)
        if (forwarded) {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "sova.example.com")
            header("X-Forwarded-Port", "443")
        }
    }

    private companion object {
        const val ACCOUNT_SID = "AC00000000000000000000000000000000"
    }
}

private class RecordingSignatureValidator(
    private val valid: Boolean,
) : WhatsappWebhookSignatureValidator {
    var url: String? = null
    var parameters: Map<String, String>? = null
    var signature: String? = null

    override fun isValid(url: String, parameters: Map<String, String>, signature: String): Boolean {
        this.url = url
        this.parameters = parameters
        this.signature = signature
        return valid
    }
}

private class RecordingHandler(
    private val result: suspend (InboundWhatsappMessage) -> WhatsappHandlingResult,
) : WhatsappWebhookHandler {
    var received: InboundWhatsappMessage? = null

    override suspend fun handle(message: InboundWhatsappMessage): WhatsappHandlingResult {
        received = message
        return result(message)
    }
}
