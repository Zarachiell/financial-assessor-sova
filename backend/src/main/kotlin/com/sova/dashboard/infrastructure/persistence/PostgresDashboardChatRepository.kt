package com.sova.dashboard.infrastructure.persistence

import com.sova.dashboard.application.DashboardChatMessage
import com.sova.dashboard.application.DashboardChatRepository
import com.sova.dashboard.application.DashboardChatRole
import com.sova.finance.domain.FinancialContext
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

class PostgresDashboardChatRepository(private val database: PostgresDatabase) : DashboardChatRepository {
    override suspend fun save(context: FinancialContext, message: DashboardChatMessage): Unit = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).execute(
                """
                INSERT INTO dashboard_chat_messages
                    (id, household_id, user_id, exchange_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))
                """.trimIndent(),
                message.id, context.householdId, context.userId, message.exchangeId,
                message.role.name, message.content, message.createdAt.toString(),
            )
        }
    }

    override suspend fun recent(context: FinancialContext, limit: Int): List<DashboardChatMessage> =
        withContext(Dispatchers.IO) {
            database.withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetch(
                    """
                    SELECT id, exchange_id, role, content, created_at
                    FROM dashboard_chat_messages
                    WHERE household_id = ? AND user_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """.trimIndent(),
                    context.householdId, context.userId, limit,
                ).map { record ->
                    DashboardChatMessage(
                        id = record.get("id", UUID::class.java),
                        exchangeId = record.get("exchange_id", UUID::class.java),
                        role = DashboardChatRole.valueOf(record.get("role", String::class.java)),
                        content = record.get("content", String::class.java),
                        createdAt = record.get("created_at", OffsetDateTime::class.java).toInstant(),
                    )
                }.asReversed()
            }
        }
}
