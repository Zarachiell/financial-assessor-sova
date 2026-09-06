package com.sova.dashboard.application

import com.sova.finance.domain.FinancialContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class DashboardUser(
    val name: String,
    val context: FinancialContext,
    val password: String,
)

data class DashboardPrincipal(
    val name: String,
    val context: FinancialContext,
)

class DashboardSessionAuthenticator(
    users: List<DashboardUser>,
    signingSecret: String,
    private val sessionDuration: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val usersByName = users.associateBy { it.name.lowercase() }
    private val usersById = users.associateBy { it.context.userId }
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(
            "sova-dashboard:$signingSecret".toByteArray(StandardCharsets.UTF_8),
        ),
        HMAC_ALGORITHM,
    )

    init {
        require(users.isNotEmpty()) { "Dashboard requires at least one user" }
        require(usersByName.size == users.size) { "Dashboard user names must be distinct" }
        require(!sessionDuration.isZero && !sessionDuration.isNegative) { "Dashboard session duration must be positive" }
        require(signingSecret.isNotBlank()) { "Dashboard signing secret must not be blank" }
    }

    fun login(name: String, password: String): String? {
        val user = usersByName[name.trim().lowercase()] ?: return null
        if (!constantTimeEquals(user.password, password)) return null
        val expiresAt = clock.instant().plus(sessionDuration).epochSecond
        val payload = "${user.context.userId}:${user.context.householdId}:$expiresAt"
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
        )
        return "$encodedPayload.${sign(encodedPayload)}"
    }

    fun authenticate(token: String?): DashboardPrincipal? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) return null
        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val fields = payload.split(':')
        if (fields.size != 3) return null
        val userId = fields[0].toUuidOrNull() ?: return null
        val householdId = fields[1].toUuidOrNull() ?: return null
        val expiresAt = fields[2].toLongOrNull() ?: return null
        if (expiresAt < clock.instant().epochSecond) return null
        val user = usersById[userId] ?: return null
        if (user.context.householdId != householdId) return null
        return DashboardPrincipal(user.name, user.context)
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8),
    )

    private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
