package com.sova.finance.infrastructure.persistence

import com.sova.finance.application.CashFlowEntryFilters
import com.sova.finance.application.FinancialRepository
import com.sova.finance.application.TransactionFilters
import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.Category
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.Currency
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.domain.Transaction
import com.sova.shared.infrastructure.database.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class PostgresFinancialRepository(
    private val database: PostgresDatabase,
) : FinancialRepository {
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

    override suspend fun findAccount(householdId: UUID, accountId: UUID): Account? = query { sql ->
        sql.fetchOne(
            "SELECT * FROM accounts WHERE household_id = ? AND id = ?",
            householdId,
            accountId,
        )?.toAccount()
    }

    override suspend fun findAccountByName(householdId: UUID, name: AccountName): Account? = query { sql ->
        sql.fetchOne(
            "SELECT * FROM accounts WHERE household_id = ? AND lower(name) = lower(?)",
            householdId,
            name.value,
        )?.toAccount()
    }

    override suspend fun insertAccount(account: Account): Boolean = query { sql ->
        sql.execute(
            """
            INSERT INTO accounts
                (id, household_id, created_by_user_id, name, type, currency, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ))
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            account.id,
            account.householdId,
            account.createdByUserId,
            account.name.value,
            account.type.name,
            account.currency.name,
            account.active,
            account.createdAt.toString(),
            account.updatedAt.toString(),
        ) == 1
    }

    override suspend fun listAccounts(householdId: UUID): List<Account> = query { sql ->
        sql.fetch(
            "SELECT * FROM accounts WHERE household_id = ? ORDER BY active DESC, lower(name), id",
            householdId,
        ).map { it.toAccount() }
    }

    override suspend fun findCategory(householdId: UUID, categoryId: UUID): Category? = query { sql ->
        sql.fetchOne(
            "SELECT * FROM categories WHERE household_id = ? AND id = ?",
            householdId,
            categoryId,
        )?.toCategory()
    }

    override suspend fun findCategoryByName(
        householdId: UUID,
        type: FinancialEntryType,
        name: CategoryName,
    ): Category? = query { sql ->
        sql.fetchOne(
            """
            SELECT *
            FROM categories
            WHERE household_id = ? AND type = ? AND lower(name) = lower(?)
            """.trimIndent(),
            householdId,
            type.name,
            name.value,
        )?.toCategory()
    }

    override suspend fun insertCategory(category: Category): Boolean = query { sql ->
        sql.execute(
            """
            INSERT INTO categories
                (id, household_id, created_by_user_id, name, type, active, created_at, updated_at, expense_nature)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), ?)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            category.id,
            category.householdId,
            category.createdByUserId,
            category.name.value,
            category.type.name,
            category.active,
            category.createdAt.toString(),
            category.updatedAt.toString(),
            category.expenseNature.name,
        ) == 1
    }

    override suspend fun listCategories(householdId: UUID): List<Category> = query { sql ->
        sql.fetch(
            """
            SELECT *
            FROM categories
            WHERE household_id = ?
            ORDER BY active DESC, type, lower(name), id
            """.trimIndent(),
            householdId,
        ).map { it.toCategory() }
    }

    override suspend fun findTransactionBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): Transaction? = query { sql ->
        sql.fetchOne(
            """
            SELECT *
            FROM transactions
            WHERE household_id = ? AND source = ? AND source_reference_id = ?
            """.trimIndent(),
            householdId,
            source.name,
            sourceReferenceId.value,
        )?.toTransaction()
    }

    override suspend fun insertTransaction(transaction: Transaction): Boolean = query { sql ->
        sql.execute(
            """
            INSERT INTO transactions
                (id, household_id, created_by_user_id, account_id, type, amount, currency, description,
                 category_id, occurred_on, source, source_reference_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            transaction.id,
            transaction.householdId,
            transaction.createdByUserId,
            transaction.accountId,
            transaction.type.name,
            transaction.money.amount,
            transaction.currency.name,
            transaction.description.value,
            transaction.categoryId,
            transaction.occurredOn,
            transaction.source.name,
            transaction.sourceReferenceId?.value,
            transaction.createdAt.toString(),
        ) == 1
    }

    override suspend fun listTransactions(
        householdId: UUID,
        filters: TransactionFilters,
    ): List<Transaction> = query { sql ->
        val conditions = mutableListOf("household_id = ?")
        val parameters = mutableListOf<Any>(householdId)
        filters.occurredFrom?.let {
            conditions += "occurred_on >= ?"
            parameters += it
        }
        filters.occurredTo?.let {
            conditions += "occurred_on <= ?"
            parameters += it
        }
        filters.type?.let {
            conditions += "type = ?"
            parameters += it.name
        }
        filters.accountId?.let {
            conditions += "account_id = ?"
            parameters += it
        }
        filters.categoryId?.let {
            conditions += "category_id = ?"
            parameters += it
        }

        sql.fetch(
            """
            SELECT *
            FROM transactions
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY occurred_on DESC, created_at DESC, id DESC
            """.trimIndent(),
            *parameters.toTypedArray(),
        ).map { it.toTransaction() }
    }

    override suspend fun findCashFlowEntryBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): CashFlowEntry? = query { sql ->
        sql.fetchOne(
            """
            SELECT *
            FROM cash_flow_entries
            WHERE household_id = ? AND source = ? AND source_reference_id = ?
            """.trimIndent(),
            householdId,
            source.name,
            sourceReferenceId.value,
        )?.toCashFlowEntry()
    }

    override suspend fun insertCashFlowEntry(entry: CashFlowEntry): Boolean = query { sql ->
        sql.execute(
            """
            INSERT INTO cash_flow_entries
                (id, household_id, created_by_user_id, account_id, category_id, type, amount, currency,
                 description, recurrence, start_date, end_date, day_of_month, active, source,
                 source_reference_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ))
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            entry.id,
            entry.householdId,
            entry.createdByUserId,
            entry.accountId,
            entry.categoryId,
            entry.type.name,
            entry.money.amount,
            entry.currency.name,
            entry.description.value,
            entry.recurrence.name,
            entry.startDate,
            entry.endDate,
            entry.dayOfMonth,
            entry.active,
            entry.source.name,
            entry.sourceReferenceId?.value,
            entry.createdAt.toString(),
            entry.updatedAt.toString(),
        ) == 1
    }

    override suspend fun findCashFlowEntry(householdId: UUID, entryId: UUID): CashFlowEntry? = query { sql ->
        sql.fetchOne(
            "SELECT * FROM cash_flow_entries WHERE household_id = ? AND id = ?",
            householdId,
            entryId,
        )?.toCashFlowEntry()
    }

    override suspend fun deactivateCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        updatedAt: Instant,
    ): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE cash_flow_entries
            SET active = FALSE, updated_at = CAST(? AS TIMESTAMPTZ)
            WHERE household_id = ? AND id = ? AND active
            """.trimIndent(),
            updatedAt.toString(),
            householdId,
            entryId,
        ) == 1
    }

    override suspend fun listCashFlowEntries(
        householdId: UUID,
        filters: CashFlowEntryFilters,
    ): List<CashFlowEntry> = query { sql ->
        val conditions = mutableListOf("household_id = ?")
        val parameters = mutableListOf<Any>(householdId)
        filters.type?.let {
            conditions += "type = ?"
            parameters += it.name
        }
        filters.active?.let {
            conditions += "active = ?"
            parameters += it
        }
        filters.from?.let {
            conditions +=
                "((recurrence = 'ONCE' AND start_date >= ?) OR " +
                "(recurrence = 'MONTHLY' AND (end_date IS NULL OR end_date >= ?)))"
            parameters += it
            parameters += it
        }
        filters.to?.let {
            conditions += "start_date <= ?"
            parameters += it
        }
        filters.accountId?.let {
            conditions += "account_id = ?"
            parameters += it
        }

        sql.fetch(
            """
            SELECT *
            FROM cash_flow_entries
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY start_date, created_at, id
            """.trimIndent(),
            *parameters.toTypedArray(),
        ).map { it.toCashFlowEntry() }
    }

    private suspend fun <T> query(block: (DSLContext) -> T): T = withContext(Dispatchers.IO) {
        database.withConnection { connection -> block(DSL.using(connection, SQLDialect.POSTGRES)) }
    }
}

private fun Record.toAccount(): Account = Account(
    id = required("id", UUID::class.java),
    householdId = required("household_id", UUID::class.java),
    createdByUserId = required("created_by_user_id", UUID::class.java),
    name = AccountName.of(required("name", String::class.java)),
    type = AccountType.valueOf(required("type", String::class.java)),
    currency = Currency.valueOf(required("currency", String::class.java)),
    active = required("active", Boolean::class.java),
    createdAt = required("created_at", OffsetDateTime::class.java).toInstant(),
    updatedAt = required("updated_at", OffsetDateTime::class.java).toInstant(),
)

private fun Record.toCategory(): Category = Category(
    id = required("id", UUID::class.java),
    householdId = required("household_id", UUID::class.java),
    createdByUserId = required("created_by_user_id", UUID::class.java),
    name = CategoryName.of(required("name", String::class.java)),
    type = FinancialEntryType.valueOf(required("type", String::class.java)),
    active = required("active", Boolean::class.java),
    createdAt = required("created_at", OffsetDateTime::class.java).toInstant(),
    updatedAt = required("updated_at", OffsetDateTime::class.java).toInstant(),
    expenseNature = ExpenseNature.valueOf(required("expense_nature", String::class.java)),
)

private fun Record.toTransaction(): Transaction = Transaction(
    id = required("id", UUID::class.java),
    householdId = required("household_id", UUID::class.java),
    createdByUserId = required("created_by_user_id", UUID::class.java),
    accountId = required("account_id", UUID::class.java),
    type = FinancialEntryType.valueOf(required("type", String::class.java)),
    money = Money.of(required("amount", BigDecimal::class.java)),
    currency = Currency.valueOf(required("currency", String::class.java)),
    description = FinancialDescription.of(required("description", String::class.java)),
    categoryId = required("category_id", UUID::class.java),
    occurredOn = required("occurred_on", LocalDate::class.java),
    source = FinancialSource.valueOf(required("source", String::class.java)),
    sourceReferenceId = get("source_reference_id", String::class.java)?.let(SourceReferenceId::of),
    createdAt = required("created_at", OffsetDateTime::class.java).toInstant(),
)

private fun Record.toCashFlowEntry(): CashFlowEntry = CashFlowEntry(
    id = required("id", UUID::class.java),
    householdId = required("household_id", UUID::class.java),
    createdByUserId = required("created_by_user_id", UUID::class.java),
    type = FinancialEntryType.valueOf(required("type", String::class.java)),
    money = Money.of(required("amount", BigDecimal::class.java)),
    currency = Currency.valueOf(required("currency", String::class.java)),
    description = FinancialDescription.of(required("description", String::class.java)),
    accountId = get("account_id", UUID::class.java),
    categoryId = get("category_id", UUID::class.java),
    recurrence = CashFlowRecurrence.valueOf(required("recurrence", String::class.java)),
    startDate = required("start_date", LocalDate::class.java),
    endDate = get("end_date", LocalDate::class.java),
    dayOfMonth = get("day_of_month", Int::class.javaObjectType),
    active = required("active", Boolean::class.java),
    source = FinancialSource.valueOf(required("source", String::class.java)),
    sourceReferenceId = get("source_reference_id", String::class.java)?.let(SourceReferenceId::of),
    createdAt = required("created_at", OffsetDateTime::class.java).toInstant(),
    updatedAt = required("updated_at", OffsetDateTime::class.java).toInstant(),
)

private fun <T : Any> Record.required(field: String, type: Class<T>): T =
    requireNotNull(get(field, type)) { "Database field $field must not be null" }
