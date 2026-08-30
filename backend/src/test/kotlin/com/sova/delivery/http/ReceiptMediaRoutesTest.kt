package com.sova.delivery.http

import com.sova.DatabaseHealth
import com.sova.finance.domain.FinancialContext
import com.sova.media.application.MediaStorage
import com.sova.media.application.StoredMedia
import com.sova.media.application.StoredMediaContent
import com.sova.receipt.application.ReceiptAccessLinkSigner
import com.sova.receipt.application.ReceiptCreation
import com.sova.receipt.application.ReceiptMediaAccess
import com.sova.receipt.application.SettlementReceipt
import com.sova.receipt.application.SettlementReceiptMedia
import com.sova.receipt.application.SettlementReceiptRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReceiptMediaRoutesTest {
    @Test
    fun `serves a receipt only through a valid expiring signed URL`() = testApplication {
        val receiptId = UUID.randomUUID()
        val signer = ReceiptAccessLinkSigner("http://localhost", "test-secret", clock = CLOCK)
        val repository = FakeReceiptRepository(receiptId)
        val storage = FakeMediaStorage()
        application {
            configureHttp(
                DatabaseHealth { true },
                receiptMedia = ReceiptMediaHttpDependencies(ReceiptMediaAccess(repository, storage, signer)),
            )
        }

        val valid = client.get(signer.create(receiptId).removePrefix("http://localhost"))
        val invalid = client.get("/media/receipts/$receiptId?expires=1&signature=invalid")

        assertEquals(HttpStatusCode.OK, valid.status)
        assertEquals("no-store", valid.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", valid.headers["X-Content-Type-Options"])
        assertContentEquals(IMAGE, valid.bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, invalid.status)
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)
        val IMAGE = byteArrayOf(1, 2, 3)
    }
}

private class FakeReceiptRepository(private val receiptId: UUID) : SettlementReceiptRepository {
    override suspend fun link(
        context: FinancialContext,
        receiptId: UUID,
        settlementId: UUID,
        attachmentId: UUID,
        caption: String?,
        linkedAt: Instant,
    ): ReceiptCreation? = error("not used")

    override suspend fun list(context: FinancialContext, settlementId: UUID): List<SettlementReceipt> = emptyList()

    override suspend fun findMedia(receiptId: UUID): SettlementReceiptMedia? =
        receiptId.takeIf { it == this.receiptId }?.let {
            SettlementReceiptMedia(it, "receipt.png", "image/png", "a".repeat(64))
        }
}

private class FakeMediaStorage : MediaStorage {
    override suspend fun store(
        householdId: UUID,
        attachmentId: UUID,
        contentType: String,
        bytes: ByteArray,
    ): StoredMedia = error("not used")

    override suspend fun read(
        storageKey: String,
        expectedSha256Hex: String,
        contentType: String,
    ): StoredMediaContent = StoredMediaContent(byteArrayOf(1, 2, 3), contentType, expectedSha256Hex)
}
