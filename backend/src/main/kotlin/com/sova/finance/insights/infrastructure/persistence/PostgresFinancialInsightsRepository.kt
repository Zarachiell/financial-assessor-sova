package com.sova.finance.insights.infrastructure.persistence

import com.sova.finance.domain.Currency
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.insights.application.FinancialInsightsRepository
import com.sova.finance.insights.domain.CategoryMetric
import com.sova.finance.insights.domain.InsightPeriod
import com.sova.finance.insights.domain.LargestExpenseMetric
import com.sova.finance.insights.domain.PeriodTotals
import com.sova.finance.insights.domain.RecurringExpenseOccurrence
import com.sova.finance.insights.domain.TrendGrouping
import com.sova.finance.insights.domain.TrendPoint
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

class PostgresFinancialInsightsRepository(
    private val database: PostgresDatabase,
) : FinancialInsightsRepository {
    override suspend fun isActiveMember(context: FinancialContext): Boolean = query { sql ->
        sql.fetchOne(
            """
            SELECT 1
            FROM users
            WHERE id = ? AND household_id = ? AND active
            """.trimIndent(),
            context.userId,
            context.householdId,
        ) != null
    }

    override suspend fun periodTotals(householdId: UUID, period: InsightPeriod): PeriodTotals = query { sql ->
        val record = requireNotNull(
            sql.fetchOne(
                """
                SELECT
                    COALESCE(SUM(amount) FILTER (WHERE type = 'INCOME'), 0) AS total_income,
                    COALESCE(SUM(amount) FILTER (WHERE type = 'EXPENSE'), 0) AS total_expenses,
                    COUNT(*) AS transaction_count
                FROM transactions
                WHERE household_id = ? AND deleted_at IS NULL AND occurred_on BETWEEN ? AND ?
                """.trimIndent(),
                householdId,
                period.from,
                period.to,
            ),
        )
        PeriodTotals(
            totalIncome = record.requiredMoney("total_income"),
            totalExpenses = record.requiredMoney("total_expenses"),
            transactionCount = record.required("transaction_count", Long::class.java).toInt(),
        )
    }

    override suspend fun categoryMetrics(
        householdId: UUID,
        period: InsightPeriod,
        type: FinancialEntryType,
    ): List<CategoryMetric> = query { sql ->
        sql.fetch(
            """
            SELECT
                c.id AS category_id,
                c.name AS category_name,
                c.expense_nature,
                SUM(t.amount) AS total,
                COUNT(*) AS transaction_count
            FROM transactions t
            JOIN categories c ON c.household_id = t.household_id AND c.id = t.category_id
            WHERE t.household_id = ?
              AND t.deleted_at IS NULL
              AND t.type = ?
              AND t.occurred_on BETWEEN ? AND ?
            GROUP BY c.id, c.name, c.expense_nature
            ORDER BY total DESC, lower(c.name), c.id
            """.trimIndent(),
            householdId,
            type.name,
            period.from,
            period.to,
        ).map(Record::toCategoryMetric)
    }

    override suspend fun largestExpenses(
        householdId: UUID,
        period: InsightPeriod,
        limit: Int,
        categoryId: UUID?,
    ): List<LargestExpenseMetric> = query { sql ->
        val categoryCondition = if (categoryId == null) "" else "AND t.category_id = ?"
        val parameters = mutableListOf<Any>(householdId, period.from, period.to)
        categoryId?.let(parameters::add)
        parameters += limit
        sql.fetch(
            """
            SELECT
                t.id AS transaction_id,
                t.occurred_on,
                t.description,
                c.name AS category_name,
                t.amount,
                t.currency
            FROM transactions t
            JOIN categories c ON c.household_id = t.household_id AND c.id = t.category_id
            WHERE t.household_id = ?
              AND t.deleted_at IS NULL
              AND t.type = 'EXPENSE'
              AND t.occurred_on BETWEEN ? AND ?
              $categoryCondition
            ORDER BY t.amount DESC, t.occurred_on DESC, t.id
            LIMIT ?
            """.trimIndent(),
            *parameters.toTypedArray(),
        ).map(Record::toLargestExpense)
    }

    override suspend fun expenseTrend(
        householdId: UUID,
        period: InsightPeriod,
        grouping: TrendGrouping,
        categoryId: UUID?,
    ): List<TrendPoint> = query { sql ->
        val dateUnit = when (grouping) {
            TrendGrouping.MONTH -> "month"
            TrendGrouping.WEEK -> "week"
        }
        val categoryCondition = if (categoryId == null) "" else "AND category_id = ?"
        val parameters = mutableListOf<Any>(householdId, period.from, period.to)
        categoryId?.let(parameters::add)
        sql.fetch(
            """
            SELECT date_trunc('$dateUnit', occurred_on::timestamp)::date AS period_start,
                   SUM(amount) AS total
            FROM transactions
            WHERE household_id = ?
              AND deleted_at IS NULL
              AND type = 'EXPENSE'
              AND occurred_on BETWEEN ? AND ?
              $categoryCondition
            GROUP BY period_start
            ORDER BY period_start
            """.trimIndent(),
            *parameters.toTypedArray(),
        ).map { record ->
            TrendPoint(
                periodStart = record.required("period_start", LocalDate::class.java),
                total = record.requiredMoney("total"),
            )
        }
    }

    override suspend fun recurringExpenseOccurrences(
        householdId: UUID,
        period: InsightPeriod,
    ): List<RecurringExpenseOccurrence> = query { sql ->
        sql.fetch(
            """
            WITH normalized AS (
                SELECT
                    t.category_id,
                    c.name AS category_name,
                    trim(
                        regexp_replace(
                            regexp_replace(
                                translate(
                                    lower(t.description),
                                    'áàãâäéèêëíìîïóòõôöúùûüç',
                                    'aaaaaeeeeiiiiooooouuuuc'
                                ),
                                '[0-9]+', '#', 'g'
                            ),
                            '[^a-z0-9#]+', ' ', 'g'
                        )
                    ) AS normalized_description,
                    t.description,
                    t.amount,
                    t.occurred_on
                FROM transactions t
                JOIN categories c ON c.household_id = t.household_id AND c.id = t.category_id
                WHERE t.household_id = ?
                  AND t.deleted_at IS NULL
                  AND t.type = 'EXPENSE'
                  AND t.occurred_on BETWEEN ? AND ?
            ), candidate_keys AS (
                SELECT category_id, normalized_description
                FROM normalized
                WHERE char_length(normalized_description) >= 2
                GROUP BY category_id, normalized_description
                HAVING COUNT(DISTINCT occurred_on) >= 2
                ORDER BY COUNT(*) DESC, normalized_description
                LIMIT 50
            )
            SELECT
                n.normalized_description,
                n.description,
                n.category_name,
                n.amount,
                n.occurred_on
            FROM normalized n
            JOIN candidate_keys k
              ON k.category_id = n.category_id
             AND k.normalized_description = n.normalized_description
            ORDER BY n.normalized_description, n.category_name, n.occurred_on, n.amount
            LIMIT 1000
            """.trimIndent(),
            householdId,
            period.from,
            period.to,
        ).map { record ->
            RecurringExpenseOccurrence(
                normalizedDescription = record.required("normalized_description", String::class.java),
                description = record.required("description", String::class.java),
                category = record.required("category_name", String::class.java),
                amount = record.requiredMoney("amount"),
                occurredOn = record.required("occurred_on", LocalDate::class.java),
            )
        }
    }

    override suspend fun currentBalance(householdId: UUID): BigDecimal = query { sql ->
        requireNotNull(
            sql.fetchOne(
                """
                SELECT COALESCE(
                    SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END),
                    0
                ) AS balance
                FROM transactions
                WHERE household_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                householdId,
            ),
        ).requiredMoney("balance")
    }

    private suspend fun <T> query(block: (DSLContext) -> T): T = withContext(Dispatchers.IO) {
        database.withConnection { connection -> block(DSL.using(connection, SQLDialect.POSTGRES)) }
    }
}

private fun Record.toCategoryMetric(): CategoryMetric = CategoryMetric(
    categoryId = required("category_id", UUID::class.java),
    category = required("category_name", String::class.java),
    nature = ExpenseNature.valueOf(required("expense_nature", String::class.java)),
    total = requiredMoney("total"),
    transactionCount = required("transaction_count", Long::class.java).toInt(),
)

private fun Record.toLargestExpense(): LargestExpenseMetric = LargestExpenseMetric(
    transactionId = required("transaction_id", UUID::class.java),
    occurredOn = required("occurred_on", LocalDate::class.java),
    description = required("description", String::class.java),
    category = required("category_name", String::class.java),
    amount = requiredMoney("amount"),
    currency = Currency.valueOf(required("currency", String::class.java)),
)

private fun Record.requiredMoney(field: String): BigDecimal =
    required(field, BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP)

private fun <T : Any> Record.required(field: String, type: Class<T>): T =
    requireNotNull(get(field, type)) { "Database field $field must not be null" }
