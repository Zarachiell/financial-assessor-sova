package com.sova.conversation.infrastructure.persistence

import com.sova.conversation.application.ConversationHistory
import com.sova.conversation.application.ConversationMessage
import com.sova.conversation.application.ConversationMessageRole
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.util.UUID

class PostgresConversationHistory(
    private val database: PostgresDatabase,
) : ConversationHistory {
    override suspend fun recentMessages(
        userId: UUID,
        excludingMessageSid: String,
        limit: Int,
    ): List<ConversationMessage> = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_HISTORY_LIMIT) { "Conversation history limit must be between 1 and 20" }
        database.withConnection { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).fetch(
                """
                SELECT direction, body
                FROM whatsapp_messages
                WHERE user_id = ? AND message_sid <> ?
                ORDER BY received_at DESC, created_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
                userId,
                excludingMessageSid,
                limit,
            ).map { record ->
                ConversationMessage(
                    role = when (record.get("direction", String::class.java)) {
                        "INBOUND" -> ConversationMessageRole.USER
                        "OUTBOUND" -> ConversationMessageRole.ASSISTANT
                        else -> error("Unsupported WhatsApp message direction")
                    },
                    content = record.get("body", String::class.java).orEmpty(),
                )
            }.asReversed()
        }
    }

    private companion object {
        const val MAX_HISTORY_LIMIT = 20
    }
}
