package com.sova.whatsapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WhatsappAddressTest {
    @Test
    fun `Brazilian mobile identity is equivalent with or without the ninth digit`() {
        val withNinthDigit = WhatsappAddress("whatsapp:+5585991234567")
        val withoutNinthDigit = WhatsappAddress("whatsapp:+558591234567")

        assertEquals(withNinthDigit.identityKey, withoutNinthDigit.identityKey)
        assertEquals("whatsapp:+5585991234567", withoutNinthDigit.identityKey)
    }

    @Test
    fun `normalization does not change non-Brazilian or Brazilian landline identities`() {
        val nonBrazilian = WhatsappAddress("whatsapp:+15550000001")
        val brazilianLandline = WhatsappAddress("whatsapp:+558532345678")

        assertEquals(nonBrazilian.value, nonBrazilian.identityKey)
        assertEquals(brazilianLandline.value, brazilianLandline.identityKey)
        assertNotEquals("whatsapp:+5585932345678", brazilianLandline.identityKey)
    }
}
