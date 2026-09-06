package com.sova.dashboard.application

import com.sova.finance.domain.FinancialContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DashboardSessionAuthenticatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC)
    private val context = FinancialContext(
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        UUID.fromString("20000000-0000-0000-0000-000000000001"),
    )

    @Test
    fun `creates and validates a household scoped session`() {
        val authenticator = authenticator(clock)

        val token = assertNotNull(authenticator.login("Joao", "correct-password"))
        val principal = assertNotNull(authenticator.authenticate(token))

        assertEquals("Joao", principal.name)
        assertEquals(context, principal.context)
        assertNull(authenticator.login("Joao", "wrong-password"))
        assertNull(authenticator.authenticate("$token-tampered"))
    }

    @Test
    fun `rejects expired sessions`() {
        val token = assertNotNull(authenticator(clock).login("Joao", "correct-password"))
        val later = Clock.fixed(Instant.parse("2026-08-31T14:01:00Z"), ZoneOffset.UTC)

        assertNull(authenticator(later).authenticate(token))
    }

    private fun authenticator(testClock: Clock) = DashboardSessionAuthenticator(
        users = listOf(DashboardUser("Joao", context, "correct-password")),
        signingSecret = "a-secure-signing-secret-with-32-characters",
        sessionDuration = Duration.ofHours(2),
        clock = testClock,
    )
}
