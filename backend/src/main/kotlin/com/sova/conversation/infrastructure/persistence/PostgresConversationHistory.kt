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
                SELECT role, body
                FROM (
                    SELECT
                        CASE direction WHEN 'INBOUND' THEN 'USER' ELSE 'ASSISTANT' END AS role,
                        body,
                        received_at AS happened_at,
                        id AS sort_id
                    FROM whatsapp_messages
                    WHERE user_id = ? AND message_sid <> ?
                    UNION ALL
                    SELECT role, content AS body, created_at AS happened_at, id AS sort_id
                    FROM dashboard_chat_messages
                    WHERE user_id = ? AND exchange_id::text <> ?
                ) AS conversation_history
                ORDER BY happened_at DESC, sort_id DESC
                LIMIT ?
                """.trimIndent(),
                userId,
                excludingMessageSid,
                userId,
                excludingMessageSid,
                limit,
            ).map { record ->
                ConversationMessage(
                    role = ConversationMessageRole.valueOf(record.get("role", String::class.java)),
                    content = record.get("body", String::class.java).orEmpty(),
                )
            }.asReversed()
        }
    }

    private companion object {
        const val MAX_HISTORY_LIMIT = 20
    }
}
