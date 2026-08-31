package com.sova.finance.infrastructure.persistence

import com.sova.finance.application.CashFlowEntryFilters
import com.sova.finance.application.FinancialRepository
import com.sova.finance.application.TransactionFilters
import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.CashFlowSettlement
import com.sova.finance.domain.CashFlowSettlementReceipt
import com.sova.finance.domain.CashFlowSettlementStatus
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
                 category_id, occurred_on, source, source_reference_id, created_at, updated_at, updated_by_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ),
                    CAST(? AS TIMESTAMPTZ), ?)
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
            transaction.updatedAt.toString(),
            transaction.updatedByUserId,
        ) == 1
    }

    override suspend fun findTransaction(householdId: UUID, transactionId: UUID): Transaction? = query { sql ->
        sql.fetchOne(
            "SELECT * FROM transactions WHERE household_id = ? AND id = ? AND deleted_at IS NULL",
            householdId,
            transactionId,
        )?.toTransaction()
    }

    override suspend fun updateTransaction(transaction: Transaction): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE transactions
            SET account_id = ?, type = ?, amount = ?, currency = ?, description = ?, category_id = ?,
                occurred_on = ?, updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?
            WHERE household_id = ? AND id = ? AND deleted_at IS NULL AND source <> 'CASH_FLOW_SETTLEMENT'
            """.trimIndent(),
            transaction.accountId,
            transaction.type.name,
            transaction.money.amount,
            transaction.currency.name,
            transaction.description.value,
            transaction.categoryId,
            transaction.occurredOn,
            transaction.updatedAt.toString(),
            transaction.updatedByUserId,
            transaction.householdId,
            transaction.id,
        ) == 1
    }

    override suspend fun deleteTransaction(
        householdId: UUID,
        transactionId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE transactions
            SET deleted_at = CAST(? AS TIMESTAMPTZ), deleted_by_user_id = ?,
                updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?
            WHERE household_id = ? AND id = ? AND deleted_at IS NULL AND source <> 'CASH_FLOW_SETTLEMENT'
            """.trimIndent(),
            deletedAt.toString(),
            deletedByUserId,
            deletedAt.toString(),
            deletedByUserId,
            householdId,
            transactionId,
        ) == 1
    }

    override suspend fun listTransactions(
        householdId: UUID,
        filters: TransactionFilters,
    ): List<Transaction> = query { sql ->
        val conditions = mutableListOf("household_id = ?", "deleted_at IS NULL")
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
            "SELECT * FROM cash_flow_entries WHERE household_id = ? AND id = ? AND deleted_at IS NULL",
            householdId,
            entryId,
        )?.toCashFlowEntry()
    }

    override suspend fun deactivateCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        updatedByUserId: UUID,
        updatedAt: Instant,
    ): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE cash_flow_entries
            SET active = FALSE, updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?
            WHERE household_id = ? AND id = ? AND active AND deleted_at IS NULL
            """.trimIndent(),
            updatedAt.toString(),
            updatedByUserId,
            householdId,
            entryId,
        ) == 1
    }

    override suspend fun updateCashFlowEntry(entry: CashFlowEntry): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE cash_flow_entries
            SET account_id = ?, category_id = ?, type = ?, amount = ?, currency = ?, description = ?,
                recurrence = ?, start_date = ?, end_date = ?, day_of_month = ?, active = ?,
                updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?
            WHERE household_id = ? AND id = ? AND deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM cash_flow_settlements
                  WHERE household_id = ? AND cash_flow_entry_id = ?
              )
            """.trimIndent(),
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
            entry.updatedAt.toString(),
            entry.updatedByUserId,
            entry.householdId,
            entry.id,
            entry.householdId,
            entry.id,
        ) == 1
    }

    override suspend fun deleteCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean = query { sql ->
        sql.execute(
            """
            UPDATE cash_flow_entries
            SET deleted_at = CAST(? AS TIMESTAMPTZ), deleted_by_user_id = ?,
                updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?, active = FALSE
            WHERE household_id = ? AND id = ? AND deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM cash_flow_settlements
                  WHERE household_id = ? AND cash_flow_entry_id = ?
              )
            """.trimIndent(),
            deletedAt.toString(),
            deletedByUserId,
            deletedAt.toString(),
            deletedByUserId,
            householdId,
            entryId,
            householdId,
            entryId,
        ) == 1
    }

    override suspend fun listCashFlowEntries(
        householdId: UUID,
        filters: CashFlowEntryFilters,
    ): List<CashFlowEntry> = query { sql ->
        val conditions = mutableListOf("household_id = ?", "deleted_at IS NULL")
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

    override suspend fun findCashFlowSettlement(
        householdId: UUID,
        entryId: UUID,
        scheduledOn: LocalDate,
    ): CashFlowSettlementReceipt? = query { sql ->
        sql.fetchOne(
            settlementReceiptSelect(
                "s.household_id = ? AND s.cash_flow_entry_id = ? AND s.scheduled_on = ? AND s.status = 'ACTIVE'",
            ),
            householdId,
            entryId,
            scheduledOn,
        )?.toCashFlowSettlementReceipt()
    }

    override suspend fun findCashFlowSettlementById(
        householdId: UUID,
        settlementId: UUID,
    ): CashFlowSettlementReceipt? = query { sql ->
        sql.fetchOne(
            settlementReceiptSelect("s.household_id = ? AND s.id = ?"),
            householdId,
            settlementId,
        )?.toCashFlowSettlementReceipt()
    }

    override suspend fun listCashFlowSettlements(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CashFlowSettlementReceipt> = query { sql ->
        sql.fetch(
            settlementReceiptSelect(
                "s.household_id = ? AND s.status = 'ACTIVE' AND s.scheduled_on >= ? AND s.scheduled_on <= ? " +
                    "ORDER BY s.scheduled_on, s.settled_at, s.id",
            ),
            householdId,
            from,
            to,
        ).map { it.toCashFlowSettlementReceipt() }
    }

    override suspend fun listCashFlowSettlementHistory(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CashFlowSettlementReceipt> = query { sql ->
        sql.fetch(
            settlementReceiptSelect(
                "s.household_id = ? AND s.scheduled_on >= ? AND s.scheduled_on <= ? " +
                    "ORDER BY s.scheduled_on, s.settled_at, s.id",
            ),
            householdId,
            from,
            to,
        ).map { it.toCashFlowSettlementReceipt() }
    }

    override suspend fun hasCashFlowSettlements(householdId: UUID, entryId: UUID): Boolean = query { sql ->
        sql.fetchOne(
            "SELECT 1 FROM cash_flow_settlements WHERE household_id = ? AND cash_flow_entry_id = ? LIMIT 1",
            householdId,
            entryId,
        ) != null
    }

    override suspend fun insertCashFlowSettlement(receipt: CashFlowSettlementReceipt): Boolean =
        withContext(Dispatchers.IO) {
            try {
                database.inTransaction { connection ->
                    val sql = DSL.using(connection, SQLDialect.POSTGRES)
                    val transaction = receipt.transaction
                    val entryAvailable = sql.fetchOne(
                        """
                        SELECT 1
                        FROM cash_flow_entries
                        WHERE household_id = ? AND id = ? AND active AND deleted_at IS NULL
                        FOR UPDATE
                        """.trimIndent(),
                        receipt.settlement.householdId,
                        receipt.settlement.cashFlowEntryId,
                    ) != null
                    if (!entryAvailable) return@inTransaction false
                    val transactionInserted = sql.execute(
                        """
                        INSERT INTO transactions
                            (id, household_id, created_by_user_id, account_id, type, amount, currency, description,
                             category_id, occurred_on, source, source_reference_id, created_at, updated_at,
                             updated_by_user_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ),
                                CAST(? AS TIMESTAMPTZ), ?)
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
                        transaction.updatedAt.toString(),
                        transaction.updatedByUserId,
                    ) == 1
                    if (!transactionInserted) return@inTransaction false

                    val settlement = receipt.settlement
                    val settlementInserted = sql.execute(
                        """
                        INSERT INTO cash_flow_settlements
                            (id, household_id, cash_flow_entry_id, scheduled_on, transaction_id,
                             settled_by_user_id, settled_at, status, note)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                        settlement.id,
                        settlement.householdId,
                        settlement.cashFlowEntryId,
                        settlement.scheduledOn,
                        settlement.transactionId,
                        settlement.settledByUserId,
                        settlement.settledAt.toString(),
                        settlement.status.name,
                        settlement.note,
                    ) == 1
                    if (!settlementInserted) throw SettlementConflictException()
                    true
                }
            } catch (_: SettlementConflictException) {
                false
            }
        }

    override suspend fun cancelCashFlowSettlement(
        householdId: UUID,
        settlementId: UUID,
        cancelledByUserId: UUID,
        cancelledAt: Instant,
        reason: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            database.inTransaction { connection ->
                val sql = DSL.using(connection, SQLDialect.POSTGRES)
                val transactionId = sql.fetchOne(
                    """
                    SELECT transaction_id
                    FROM cash_flow_settlements
                    WHERE household_id = ? AND id = ? AND status = 'ACTIVE'
                    FOR UPDATE
                    """.trimIndent(),
                    householdId,
                    settlementId,
                )?.get("transaction_id", UUID::class.java) ?: return@inTransaction false
                val transactionCancelled = sql.execute(
                    """
                    UPDATE transactions
                    SET deleted_at = CAST(? AS TIMESTAMPTZ), deleted_by_user_id = ?,
                        updated_at = CAST(? AS TIMESTAMPTZ), updated_by_user_id = ?
                    WHERE household_id = ? AND id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    cancelledAt.toString(),
                    cancelledByUserId,
                    cancelledAt.toString(),
                    cancelledByUserId,
                    householdId,
                    transactionId,
                ) == 1
                if (!transactionCancelled) return@inTransaction false
                val settlementCancelled = sql.execute(
                    """
                    UPDATE cash_flow_settlements
                    SET status = 'CANCELLED', cancelled_at = CAST(? AS TIMESTAMPTZ),
                        cancelled_by_user_id = ?, cancellation_reason = ?
                    WHERE household_id = ? AND id = ? AND status = 'ACTIVE'
                    """.trimIndent(),
                    cancelledAt.toString(),
                    cancelledByUserId,
                    reason,
                    householdId,
                    settlementId,
                ) == 1
                if (!settlementCancelled) throw SettlementCancellationConflictException()
                true
            }
        } catch (_: SettlementCancellationConflictException) {
            false
        }
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
    updatedAt = required("updated_at", OffsetDateTime::class.java).toInstant(),
    updatedByUserId = get("updated_by_user_id", UUID::class.java),
    deletedAt = get("deleted_at", OffsetDateTime::class.java)?.toInstant(),
    deletedByUserId = get("deleted_by_user_id", UUID::class.java),
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
    updatedByUserId = get("updated_by_user_id", UUID::class.java),
    deletedAt = get("deleted_at", OffsetDateTime::class.java)?.toInstant(),
    deletedByUserId = get("deleted_by_user_id", UUID::class.java),
)

private fun settlementReceiptSelect(whereClause: String): String =
    """
    SELECT
        s.id AS settlement_id,
        s.household_id AS settlement_household_id,
        s.cash_flow_entry_id AS settlement_entry_id,
        s.scheduled_on AS settlement_scheduled_on,
        s.transaction_id AS settlement_transaction_id,
        s.settled_by_user_id AS settlement_user_id,
        s.settled_at AS settlement_at,
        s.status AS settlement_status,
        s.note AS settlement_note,
        s.cancelled_at AS settlement_cancelled_at,
        s.cancelled_by_user_id AS settlement_cancelled_by_user_id,
        s.cancellation_reason AS settlement_cancellation_reason,
        t.id AS tx_id,
        t.household_id AS tx_household_id,
        t.created_by_user_id AS tx_created_by_user_id,
        t.account_id AS tx_account_id,
        t.type AS tx_type,
        t.amount AS tx_amount,
        t.currency AS tx_currency,
        t.description AS tx_description,
        t.category_id AS tx_category_id,
        t.occurred_on AS tx_occurred_on,
        t.source AS tx_source,
        t.source_reference_id AS tx_source_reference_id,
        t.created_at AS tx_created_at,
        t.updated_at AS tx_updated_at,
        t.updated_by_user_id AS tx_updated_by_user_id,
        t.deleted_at AS tx_deleted_at,
        t.deleted_by_user_id AS tx_deleted_by_user_id
    FROM cash_flow_settlements s
    JOIN transactions t
      ON t.household_id = s.household_id AND t.id = s.transaction_id
    WHERE $whereClause
    """.trimIndent()

private fun Record.toCashFlowSettlementReceipt(): CashFlowSettlementReceipt {
    val settlement = CashFlowSettlement(
        id = required("settlement_id", UUID::class.java),
        householdId = required("settlement_household_id", UUID::class.java),
        cashFlowEntryId = required("settlement_entry_id", UUID::class.java),
        scheduledOn = required("settlement_scheduled_on", LocalDate::class.java),
        transactionId = required("settlement_transaction_id", UUID::class.java),
        settledByUserId = required("settlement_user_id", UUID::class.java),
        settledAt = required("settlement_at", OffsetDateTime::class.java).toInstant(),
        status = CashFlowSettlementStatus.valueOf(required("settlement_status", String::class.java)),
        note = get("settlement_note", String::class.java),
        cancelledAt = get("settlement_cancelled_at", OffsetDateTime::class.java)?.toInstant(),
        cancelledByUserId = get("settlement_cancelled_by_user_id", UUID::class.java),
        cancellationReason = get("settlement_cancellation_reason", String::class.java),
    )
    val transaction = Transaction(
        id = required("tx_id", UUID::class.java),
        householdId = required("tx_household_id", UUID::class.java),
        createdByUserId = required("tx_created_by_user_id", UUID::class.java),
        accountId = required("tx_account_id", UUID::class.java),
        type = FinancialEntryType.valueOf(required("tx_type", String::class.java)),
        money = Money.of(required("tx_amount", BigDecimal::class.java)),
        currency = Currency.valueOf(required("tx_currency", String::class.java)),
        description = FinancialDescription.of(required("tx_description", String::class.java)),
        categoryId = required("tx_category_id", UUID::class.java),
        occurredOn = required("tx_occurred_on", LocalDate::class.java),
        source = FinancialSource.valueOf(required("tx_source", String::class.java)),
        sourceReferenceId = get("tx_source_reference_id", String::class.java)?.let(SourceReferenceId::of),
        createdAt = required("tx_created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = required("tx_updated_at", OffsetDateTime::class.java).toInstant(),
        updatedByUserId = get("tx_updated_by_user_id", UUID::class.java),
        deletedAt = get("tx_deleted_at", OffsetDateTime::class.java)?.toInstant(),
        deletedByUserId = get("tx_deleted_by_user_id", UUID::class.java),
    )
    return CashFlowSettlementReceipt(settlement, transaction)
}

private fun <T : Any> Record.required(field: String, type: Class<T>): T =
    requireNotNull(get(field, type)) { "Database field $field must not be null" }

private class SettlementConflictException : RuntimeException()
private class SettlementCancellationConflictException : RuntimeException()
