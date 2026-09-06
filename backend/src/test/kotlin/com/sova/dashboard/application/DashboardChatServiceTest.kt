package com.sova.dashboard.application

import com.sova.conversation.application.AiConversationContext
import com.sova.conversation.application.AiTextConversation
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DashboardChatServiceTest {
    private val context = FinancialContext(UUID.randomUUID(), UUID.randomUUID())
    private val repository = InMemoryDashboardChatRepository()
    private var receivedContext: AiConversationContext? = null
    private val service = DashboardChatService(
        conversation = AiTextConversation { conversationContext ->
            receivedContext = conversationContext
            "Seu saldo está positivo."
        },
        repository = repository,
        clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `persists exchange and derives AI context from authenticated principal`() = runTest {
        val messages = service.send(context, "  Como estão minhas finanças?  ")

        assertEquals(listOf(DashboardChatRole.USER, DashboardChatRole.ASSISTANT), messages.map { it.role })
        assertEquals("Como estão minhas finanças?", messages.first().content)
        assertEquals("Seu saldo está positivo.", messages.last().content)
        assertEquals(messages.first().exchangeId, messages.last().exchangeId)
        assertEquals(context.householdId, receivedContext?.householdId)
        assertEquals(context.userId, receivedContext?.userId)
        assertEquals(FinancialSource.DASHBOARD, receivedContext?.source)
        assertEquals(messages, repository.recent(context, 50))
    }

    @Test
    fun `rejects empty and oversized messages before calling AI`() {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { service.send(context, "  ") } }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { service.send(context, "a".repeat(4001)) }
        }
    }
}

private class InMemoryDashboardChatRepository : DashboardChatRepository {
    private val messages = mutableListOf<DashboardChatMessage>()

    override suspend fun save(context: FinancialContext, message: DashboardChatMessage) {
        messages += message
    }

    override suspend fun recent(context: FinancialContext, limit: Int): List<DashboardChatMessage> =
        messages.takeLast(limit)
}
