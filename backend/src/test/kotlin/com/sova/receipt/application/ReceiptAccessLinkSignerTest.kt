package com.sova.receipt.application

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptAccessLinkSignerTest {
    private val receiptId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `creates scoped temporary link and rejects tampering or expiration`() {
        val signer = ReceiptAccessLinkSigner(
            "https://sova.example.com",
            "a-secure-test-key-with-enough-length",
            Duration.ofMinutes(10),
            clock,
        )
        val link = signer.create(receiptId)
        val expires = Regex("expires=([0-9]+)").find(link)?.groupValues?.get(1)?.toLong() ?: error("missing expires")
        val signature = Regex("signature=([^&]+)").find(link)?.groupValues?.get(1) ?: error("missing signature")

        assertTrue(link.startsWith("https://sova.example.com/media/receipts/$receiptId"))
        assertTrue(signer.verify(receiptId, expires, signature))
        assertFalse(signer.verify(receiptId, expires, "tampered"))
        val expiredSigner = ReceiptAccessLinkSigner(
            "https://sova.example.com",
            "a-secure-test-key-with-enough-length",
            Duration.ofMinutes(10),
            Clock.fixed(Instant.parse("2026-08-30T12:11:00Z"), ZoneOffset.UTC),
        )
        assertFalse(expiredSigner.verify(receiptId, expires, signature))
    }
}
