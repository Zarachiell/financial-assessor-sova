package com.sova.whatsapp.infrastructure.twilio

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwilioWebhookSignatureValidatorTest {
    @Test
    fun `delegates form signature verification to the official Twilio validator`() {
        val authToken = "test-auth-token"
        val url = "https://sova.example.com/webhooks/twilio/whatsapp"
        val parameters = mapOf(
            "AccountSid" to "AC00000000000000000000000000000000",
            "Body" to "ping",
            "From" to "whatsapp:+15550000001",
            "MessageSid" to "SM-IN",
            "NumMedia" to "0",
            "To" to "whatsapp:+15550000000",
        )
        val signature = signatureFor(authToken, url, parameters)
        val validator = TwilioWebhookSignatureValidator(authToken)

        assertTrue(validator.isValid(url, parameters, signature))
        assertFalse(validator.isValid(url, parameters + ("Body" to "tampered"), signature))
        assertFalse(validator.isValid(url, parameters, ""))
    }

    private fun signatureFor(authToken: String, url: String, parameters: Map<String, String>): String {
        val signedContent = buildString {
            append(url)
            parameters.toSortedMap().forEach { (name, value) ->
                append(name)
                append(value)
            }
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(signedContent.toByteArray(Charsets.UTF_8)))
    }
}
