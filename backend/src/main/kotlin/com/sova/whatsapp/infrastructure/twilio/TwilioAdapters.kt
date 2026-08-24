package com.sova.whatsapp.infrastructure.twilio

import com.sova.shared.config.TwilioConfig
import com.sova.whatsapp.application.SendMessageResult
import com.sova.whatsapp.application.WhatsappDeliveryException
import com.sova.whatsapp.application.WhatsappMessageSender
import com.sova.whatsapp.application.WhatsappWebhookSignatureValidator
import com.sova.whatsapp.domain.WhatsappAddress
import com.twilio.http.TwilioRestClient
import com.twilio.rest.api.v2010.account.Message
import com.twilio.security.RequestValidator
import com.twilio.type.PhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TwilioWebhookSignatureValidator(authToken: String) : WhatsappWebhookSignatureValidator {
    private val validator = RequestValidator(authToken)

    override fun isValid(url: String, parameters: Map<String, String>, signature: String): Boolean =
        signature.isNotBlank() && validator.validate(url, parameters, signature)
}

class TwilioWhatsappMessageSender(
    private val senderAddress: WhatsappAddress,
    config: TwilioConfig,
) : WhatsappMessageSender {
    private val client: TwilioRestClient = TwilioRestClient.Builder(config.accountSid, config.authToken).build()

    override suspend fun send(to: WhatsappAddress, body: String): SendMessageResult = withContext(Dispatchers.IO) {
        try {
            val message = Message.creator(
                PhoneNumber(to.value),
                PhoneNumber(senderAddress.value),
                body,
            ).create(client)
            SendMessageResult(requireNotNull(message.sid) { "Twilio response did not contain a message SID" })
        } catch (cause: Exception) {
            throw WhatsappDeliveryException("Twilio could not deliver the WhatsApp response", cause)
        }
    }
}
