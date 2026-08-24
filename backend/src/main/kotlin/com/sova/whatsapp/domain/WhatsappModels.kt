package com.sova.whatsapp.domain

import java.util.UUID

@JvmInline
value class WhatsappAddress(val value: String) {
    init {
        require(FORMAT.matches(value)) { "WhatsApp address must use the format whatsapp:+<E.164 number>" }
    }

    val identityKey: String
        get() = BRAZILIAN_MOBILE_WITHOUT_NINTH_DIGIT.matchEntire(value)?.let { match ->
            "${match.groupValues[1]}9${match.groupValues[2]}"
        } ?: value

    private companion object {
        val FORMAT = Regex("^whatsapp:\\+[1-9]\\d{6,14}$")
        val BRAZILIAN_MOBILE_WITHOUT_NINTH_DIGIT = Regex("^(whatsapp:\\+55[1-9]\\d)([6-9]\\d{7})$")
    }
}

data class AuthorizedWhatsappUser(
    val userId: UUID,
    val householdId: UUID,
    val address: WhatsappAddress,
)

enum class WhatsappMessageDirection {
    INBOUND,
    OUTBOUND,
}
