package com.sova.receipt.application

import com.sova.finance.domain.FinancialContext
import com.sova.media.application.MediaStorage
import com.sova.media.application.StoredMediaContent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SettlementReceipt(
    val id: UUID,
    val householdId: UUID,
    val settlementId: UUID,
    val attachmentId: UUID,
    val caption: String?,
    val contentType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val linkedByUserId: UUID,
    val linkedAt: Instant,
)

data class SettlementReceiptMedia(
    val receiptId: UUID,
    val storageKey: String,
    val contentType: String,
    val sha256Hex: String,
)

data class ReceiptCreation(val receipt: SettlementReceipt, val created: Boolean)

interface SettlementReceiptRepository {
    suspend fun link(
        context: FinancialContext,
        receiptId: UUID,
        settlementId: UUID,
        attachmentId: UUID,
        caption: String?,
        linkedAt: Instant,
    ): ReceiptCreation?

    suspend fun list(context: FinancialContext, settlementId: UUID): List<SettlementReceipt>
    suspend fun findMedia(receiptId: UUID): SettlementReceiptMedia?
}

class SettlementReceiptService(
    private val repository: SettlementReceiptRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun link(
        context: FinancialContext,
        settlementId: UUID,
        attachmentId: UUID,
        caption: String? = null,
    ): ReceiptCreation? = repository.link(
        context,
        UUID.randomUUID(),
        settlementId,
        attachmentId,
        caption?.trim()?.takeIf(String::isNotEmpty),
        clock.instant(),
    )

    suspend fun list(context: FinancialContext, settlementId: UUID): List<SettlementReceipt> =
        repository.list(context, settlementId)
}

class ReceiptAccessLinkSigner(
    publicBaseUrl: String,
    secret: String,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val publicBaseUrl = publicBaseUrl.trimEnd('/').also {
        require(it.isAllowedReceiptPublicUrl()) {
            "Receipt public base URL must use HTTPS outside localhost or an IP address"
        }
        require('?' !in it && '#' !in it) { "Receipt public base URL must not contain a query or fragment" }
    }
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(
            "sova-receipts:$secret".toByteArray(StandardCharsets.UTF_8),
        ),
        HMAC_ALGORITHM,
    ).also { require(secret.isNotBlank()) { "Receipt signing secret must not be blank" } }

    fun create(receiptId: UUID): String {
        val expires = clock.instant().plus(ttl).epochSecond
        val signature = sign(receiptId, expires)
        return "$publicBaseUrl/media/receipts/$receiptId?expires=$expires&signature=$signature"
    }

    fun verify(receiptId: UUID, expires: Long, signature: String): Boolean {
        if (expires < clock.instant().epochSecond || signature.isBlank()) return false
        val expected = sign(receiptId, expires)
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            signature.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun sign(receiptId: UUID, expires: Long): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal("receipt:$receiptId:$expires".toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

private fun String.isAllowedReceiptPublicUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) return true
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host ?: return false
    return host.equals("localhost", ignoreCase = true) || host.isIpv4Literal()
}

private fun String.isIpv4Literal(): Boolean {
    val octets = split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
    }
}

class ReceiptMediaAccess(
    private val repository: SettlementReceiptRepository,
    private val storage: MediaStorage,
    private val signer: ReceiptAccessLinkSigner,
) {
    suspend fun read(receiptId: UUID, expires: Long, signature: String): StoredMediaContent? {
        if (!signer.verify(receiptId, expires, signature)) return null
        val media = repository.findMedia(receiptId) ?: return null
        return storage.read(media.storageKey, media.sha256Hex, media.contentType)
    }
}
