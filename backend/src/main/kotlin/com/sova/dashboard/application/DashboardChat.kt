package com.sova.dashboard.application

import com.sova.conversation.application.AiConversationContext
import com.sova.conversation.application.AiTextConversation
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialSource
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class DashboardChatRole { USER, ASSISTANT }

data class DashboardChatMessage(
    val id: UUID,
    val exchangeId: UUID,
    val role: DashboardChatRole,
    val content: String,
    val createdAt: Instant,
)

interface DashboardChatRepository {
    suspend fun save(context: FinancialContext, message: DashboardChatMessage)
    suspend fun recent(context: FinancialContext, limit: Int): List<DashboardChatMessage>
}

class DashboardChatService(
    private val conversation: AiTextConversation,
    private val repository: DashboardChatRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun send(context: FinancialContext, rawContent: String): List<DashboardChatMessage> {
        val content = rawContent.trim()
        require(content.isNotEmpty()) { "Chat message must not be empty" }
        require(content.length <= MAX_MESSAGE_LENGTH) { "Chat message must have at most $MAX_MESSAGE_LENGTH characters" }
        val exchangeId = UUID.randomUUID()
        val userMessage = DashboardChatMessage(UUID.randomUUID(), exchangeId, DashboardChatRole.USER, content, clock.instant())
        repository.save(context, userMessage)
        val answer = conversation.respond(
            AiConversationContext(
                householdId = context.householdId,
                userId = context.userId,
                messageId = exchangeId.toString(),
                text = content,
                source = FinancialSource.DASHBOARD,
            ),
        )
        val assistantMessage = DashboardChatMessage(
            UUID.randomUUID(),
            exchangeId,
            DashboardChatRole.ASSISTANT,
            answer.take(MAX_MESSAGE_LENGTH),
            clock.instant(),
        )
        repository.save(context, assistantMessage)
        return listOf(userMessage, assistantMessage)
    }

    suspend fun recent(context: FinancialContext, limit: Int = 50): List<DashboardChatMessage> {
        require(limit in 1..100) { "Chat history limit must be between 1 and 100" }
        return repository.recent(context, limit)
    }

    private companion object { const val MAX_MESSAGE_LENGTH = 4000 }
}
