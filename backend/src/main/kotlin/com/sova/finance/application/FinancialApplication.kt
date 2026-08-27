package com.sova.finance.application

import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowOccurrenceCalculator
import com.sova.finance.domain.CashFlowOccurrenceKey
import com.sova.finance.domain.CashFlowProjection
import com.sova.finance.domain.CashFlowProjectionCalculator
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.CashFlowSettlement
import com.sova.finance.domain.CashFlowSettlementReceipt
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CreateAccountCommand(
    val name: AccountName,
    val type: AccountType,
)

data class CreateCategoryCommand(
    val name: CategoryName,
    val type: FinancialEntryType,
    val expenseNature: ExpenseNature = ExpenseNature.UNCLASSIFIED,
)

data class CreateTransactionCommand(
    val accountId: UUID,
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency = Currency.BRL,
    val description: FinancialDescription,
    val categoryId: UUID,
    val occurredOn: LocalDate,
    val source: FinancialSource,
    val sourceReferenceId: SourceReferenceId? = null,
)

data class UpdateTransactionCommand(
    val accountId: UUID,
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency = Currency.BRL,
    val description: FinancialDescription,
    val categoryId: UUID,
    val occurredOn: LocalDate,
)

data class TransactionFilters(
    val occurredFrom: LocalDate? = null,
    val occurredTo: LocalDate? = null,
    val type: FinancialEntryType? = null,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
) {
    init {
        require(occurredFrom == null || occurredTo == null || !occurredFrom.isAfter(occurredTo)) {
            "Transaction period start must not be after its end"
        }
    }
}

data class CreateCashFlowEntryCommand(
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency = Currency.BRL,
    val description: FinancialDescription,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
    val recurrence: CashFlowRecurrence = CashFlowRecurrence.ONCE,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val dayOfMonth: Int? = null,
    val source: FinancialSource,
    val sourceReferenceId: SourceReferenceId? = null,
) {
    init {
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Cash flow end date must not be before its start date"
        }
        when (recurrence) {
            CashFlowRecurrence.ONCE -> require(dayOfMonth == null) {
                "One-time cash flow entries must not define a day of month"
            }
            CashFlowRecurrence.MONTHLY -> require(dayOfMonth in 1..31) {
                "Monthly cash flow entries require a day of month between 1 and 31"
            }
        }
    }
}

data class UpdateCashFlowEntryCommand(
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency = Currency.BRL,
    val description: FinancialDescription,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
    val recurrence: CashFlowRecurrence,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val dayOfMonth: Int? = null,
    val active: Boolean,
) {
    init {
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Cash flow end date must not be before its start date"
        }
        when (recurrence) {
            CashFlowRecurrence.ONCE -> require(dayOfMonth == null) {
                "One-time cash flow entries must not define a day of month"
            }
            CashFlowRecurrence.MONTHLY -> require(dayOfMonth in 1..31) {
                "Monthly cash flow entries require a day of month between 1 and 31"
            }
        }
    }
}

data class SettleCashFlowOccurrenceCommand(
    val scheduledOn: LocalDate,
    val paidOn: LocalDate,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
)

enum class CashFlowOccurrenceStatus {
    PENDING,
    PAID,
}

data class CashFlowOccurrenceDetails(
    val entry: CashFlowEntry,
    val scheduledOn: LocalDate,
    val status: CashFlowOccurrenceStatus,
    val receipt: CashFlowSettlementReceipt? = null,
)

data class GetCashFlowOccurrencesQuery(
    val from: LocalDate,
    val to: LocalDate,
    val type: FinancialEntryType? = null,
    val accountId: UUID? = null,
    val status: CashFlowOccurrenceStatus? = null,
) {
    init {
        require(!from.isAfter(to)) { "Cash flow period start must not be after its end" }
    }
}

data class CashFlowEntryFilters(
    val type: FinancialEntryType? = null,
    val active: Boolean? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val accountId: UUID? = null,
) {
    init {
        require(from == null || to == null || !from.isAfter(to)) {
            "Cash flow period start must not be after its end"
        }
    }
}

data class GetCashFlowProjectionQuery(
    val targetDate: LocalDate,
    val accountId: UUID? = null,
)

data class Creation<T>(
    val value: T,
    val created: Boolean,
)

sealed interface FinancialResult<out T> {
    data class Success<T>(val value: T) : FinancialResult<T>

    data class Rejected(val reason: FinancialRejection) : FinancialResult<Nothing>
}

enum class FinancialRejection {
    UNAUTHORIZED_CONTEXT,
    DUPLICATE_ACCOUNT_NAME,
    DUPLICATE_CATEGORY_NAME,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE,
    CATEGORY_NOT_FOUND,
    CATEGORY_INACTIVE,
    CATEGORY_TYPE_MISMATCH,
    CURRENCY_MISMATCH,
    TRANSACTION_NOT_FOUND,
    RECORD_MANAGED_BY_CASH_FLOW,
    CASH_FLOW_ENTRY_NOT_FOUND,
    CASH_FLOW_ENTRY_HAS_SETTLEMENTS,
    CASH_FLOW_OCCURRENCE_NOT_FOUND,
    SETTLEMENT_ACCOUNT_REQUIRED,
    SETTLEMENT_CATEGORY_REQUIRED,
    FUTURE_PAYMENT_DATE,
    INVALID_PROJECTION_TARGET,
    PROJECTION_HORIZON_EXCEEDED,
    PERSISTENCE_CONFLICT,
}

interface FinancialRepository {
    suspend fun isActiveMember(context: FinancialContext): Boolean

    suspend fun findAccount(householdId: UUID, accountId: UUID): Account?

    suspend fun findAccountByName(householdId: UUID, name: AccountName): Account?

    suspend fun insertAccount(account: Account): Boolean

    suspend fun listAccounts(householdId: UUID): List<Account>

    suspend fun findCategory(householdId: UUID, categoryId: UUID): Category?

    suspend fun findCategoryByName(
        householdId: UUID,
        type: FinancialEntryType,
        name: CategoryName,
    ): Category?

    suspend fun insertCategory(category: Category): Boolean

    suspend fun listCategories(householdId: UUID): List<Category>

    suspend fun findTransactionBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): Transaction?

    suspend fun insertTransaction(transaction: Transaction): Boolean

    suspend fun findTransaction(householdId: UUID, transactionId: UUID): Transaction?

    suspend fun updateTransaction(transaction: Transaction): Boolean

    suspend fun deleteTransaction(
        householdId: UUID,
        transactionId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean

    suspend fun listTransactions(householdId: UUID, filters: TransactionFilters): List<Transaction>

    suspend fun findCashFlowEntryBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): CashFlowEntry?

    suspend fun insertCashFlowEntry(entry: CashFlowEntry): Boolean

    suspend fun findCashFlowEntry(householdId: UUID, entryId: UUID): CashFlowEntry?

    suspend fun deactivateCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        updatedByUserId: UUID,
        updatedAt: Instant,
    ): Boolean

    suspend fun updateCashFlowEntry(entry: CashFlowEntry): Boolean

    suspend fun deleteCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean

    suspend fun listCashFlowEntries(householdId: UUID, filters: CashFlowEntryFilters): List<CashFlowEntry>

    suspend fun findCashFlowSettlement(
        householdId: UUID,
        entryId: UUID,
        scheduledOn: LocalDate,
    ): CashFlowSettlementReceipt?

    suspend fun listCashFlowSettlements(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CashFlowSettlementReceipt>

    suspend fun hasCashFlowSettlements(householdId: UUID, entryId: UUID): Boolean

    suspend fun insertCashFlowSettlement(receipt: CashFlowSettlementReceipt): Boolean
}

class FinancialApplicationService(
    private val repository: FinancialRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val maxProjectionMonths: Int = DEFAULT_MAX_PROJECTION_MONTHS,
    private val householdZone: ZoneId = ZoneId.of(DEFAULT_HOUSEHOLD_TIME_ZONE),
    private val occurrenceCalculator: CashFlowOccurrenceCalculator = CashFlowOccurrenceCalculator(),
    private val projectionCalculator: CashFlowProjectionCalculator = CashFlowProjectionCalculator(),
    private val idGenerator: () -> UUID = UUID::randomUUID,
) {
    init {
        require(maxProjectionMonths > 0) { "Maximum projection months must be positive" }
    }

    suspend fun createAccount(
        context: FinancialContext,
        command: CreateAccountCommand,
    ): FinancialResult<Creation<Account>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        if (repository.findAccountByName(context.householdId, command.name) != null) {
            return rejected(FinancialRejection.DUPLICATE_ACCOUNT_NAME)
        }

        val now = clock.instant()
        val account = Account(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            name = command.name,
            type = command.type,
            currency = Currency.BRL,
            active = true,
            createdAt = now,
            updatedAt = now,
        )
        if (!repository.insertAccount(account)) {
            return rejected(FinancialRejection.DUPLICATE_ACCOUNT_NAME)
        }
        return success(Creation(account, created = true))
    }

    suspend fun listAccounts(context: FinancialContext): FinancialResult<List<Account>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return success(repository.listAccounts(context.householdId))
    }

    suspend fun createCategory(
        context: FinancialContext,
        command: CreateCategoryCommand,
    ): FinancialResult<Creation<Category>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        require(command.type == FinancialEntryType.EXPENSE || command.expenseNature == ExpenseNature.UNCLASSIFIED) {
            "Income categories must be unclassified"
        }
        if (repository.findCategoryByName(context.householdId, command.type, command.name) != null) {
            return rejected(FinancialRejection.DUPLICATE_CATEGORY_NAME)
        }

        val now = clock.instant()
        val category = Category(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            name = command.name,
            type = command.type,
            active = true,
            createdAt = now,
            updatedAt = now,
            expenseNature = command.expenseNature,
        )
        if (!repository.insertCategory(category)) {
            return rejected(FinancialRejection.DUPLICATE_CATEGORY_NAME)
        }
        return success(Creation(category, created = true))
    }

    suspend fun listCategories(context: FinancialContext): FinancialResult<List<Category>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return success(repository.listCategories(context.householdId))
    }

    suspend fun createTransaction(
        context: FinancialContext,
        command: CreateTransactionCommand,
    ): FinancialResult<Creation<Transaction>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        findExistingTransaction(context, command)?.let { return success(Creation(it, created = false)) }

        val account = repository.findAccount(context.householdId, command.accountId)
            ?: return rejected(FinancialRejection.ACCOUNT_NOT_FOUND)
        if (!account.active) return rejected(FinancialRejection.ACCOUNT_INACTIVE)

        val category = repository.findCategory(context.householdId, command.categoryId)
            ?: return rejected(FinancialRejection.CATEGORY_NOT_FOUND)
        if (!category.active) return rejected(FinancialRejection.CATEGORY_INACTIVE)
        if (category.type != command.type) return rejected(FinancialRejection.CATEGORY_TYPE_MISMATCH)
        if (account.currency != command.currency) return rejected(FinancialRejection.CURRENCY_MISMATCH)

        val transaction = Transaction(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            accountId = account.id,
            type = command.type,
            money = command.money,
            currency = command.currency,
            description = command.description,
            categoryId = category.id,
            occurredOn = command.occurredOn,
            source = command.source,
            sourceReferenceId = command.sourceReferenceId,
            createdAt = clock.instant(),
        )
        if (repository.insertTransaction(transaction)) return success(Creation(transaction, created = true))

        findExistingTransaction(context, command)?.let { return success(Creation(it, created = false)) }
        return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
    }

    suspend fun listTransactions(
        context: FinancialContext,
        filters: TransactionFilters = TransactionFilters(),
    ): FinancialResult<List<Transaction>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return success(repository.listTransactions(context.householdId, filters))
    }

    suspend fun findTransaction(
        context: FinancialContext,
        transactionId: UUID,
    ): FinancialResult<Transaction> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return repository.findTransaction(context.householdId, transactionId)
            ?.let(::success)
            ?: rejected(FinancialRejection.TRANSACTION_NOT_FOUND)
    }

    suspend fun updateTransaction(
        context: FinancialContext,
        transactionId: UUID,
        command: UpdateTransactionCommand,
    ): FinancialResult<Transaction> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val current = repository.findTransaction(context.householdId, transactionId)
            ?: return rejected(FinancialRejection.TRANSACTION_NOT_FOUND)
        if (current.source == FinancialSource.CASH_FLOW_SETTLEMENT) {
            return rejected(FinancialRejection.RECORD_MANAGED_BY_CASH_FLOW)
        }
        validateAccountAndCategory(context, command.accountId, command.categoryId, command.type, command.currency)
            ?.let { return rejected(it) }

        val updated = current.copy(
            accountId = command.accountId,
            type = command.type,
            money = command.money,
            currency = command.currency,
            description = command.description,
            categoryId = command.categoryId,
            occurredOn = command.occurredOn,
            updatedAt = clock.instant(),
            updatedByUserId = context.userId,
        )
        if (!repository.updateTransaction(updated)) return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
        return success(updated)
    }

    suspend fun deleteTransaction(
        context: FinancialContext,
        transactionId: UUID,
    ): FinancialResult<Transaction> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val current = repository.findTransaction(context.householdId, transactionId)
            ?: return rejected(FinancialRejection.TRANSACTION_NOT_FOUND)
        if (current.source == FinancialSource.CASH_FLOW_SETTLEMENT) {
            return rejected(FinancialRejection.RECORD_MANAGED_BY_CASH_FLOW)
        }
        val deletedAt = clock.instant()
        if (!repository.deleteTransaction(context.householdId, transactionId, context.userId, deletedAt)) {
            return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
        }
        return success(current.copy(deletedAt = deletedAt, deletedByUserId = context.userId))
    }

    suspend fun createCashFlowEntry(
        context: FinancialContext,
        command: CreateCashFlowEntryCommand,
    ): FinancialResult<Creation<CashFlowEntry>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        findExistingCashFlowEntry(context, command)?.let { return success(Creation(it, created = false)) }

        val account = command.accountId?.let { accountId ->
            repository.findAccount(context.householdId, accountId)
                ?: return rejected(FinancialRejection.ACCOUNT_NOT_FOUND)
        }
        if (account != null && !account.active) return rejected(FinancialRejection.ACCOUNT_INACTIVE)
        if (account != null && account.currency != command.currency) {
            return rejected(FinancialRejection.CURRENCY_MISMATCH)
        }

        val category = command.categoryId?.let { categoryId ->
            repository.findCategory(context.householdId, categoryId)
                ?: return rejected(FinancialRejection.CATEGORY_NOT_FOUND)
        }
        if (category != null && !category.active) return rejected(FinancialRejection.CATEGORY_INACTIVE)
        if (category != null && category.type != command.type) {
            return rejected(FinancialRejection.CATEGORY_TYPE_MISMATCH)
        }

        val now = clock.instant()
        val entry = CashFlowEntry(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            type = command.type,
            money = command.money,
            currency = command.currency,
            description = command.description,
            accountId = account?.id,
            categoryId = category?.id,
            recurrence = command.recurrence,
            startDate = command.startDate,
            endDate = command.endDate,
            dayOfMonth = command.dayOfMonth,
            active = true,
            source = command.source,
            sourceReferenceId = command.sourceReferenceId,
            createdAt = now,
            updatedAt = now,
        )
        if (repository.insertCashFlowEntry(entry)) return success(Creation(entry, created = true))

        findExistingCashFlowEntry(context, command)?.let { return success(Creation(it, created = false)) }
        return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
    }

    suspend fun deactivateCashFlowEntry(
        context: FinancialContext,
        entryId: UUID,
    ): FinancialResult<CashFlowEntry> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val entry = repository.findCashFlowEntry(context.householdId, entryId)
            ?: return rejected(FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND)
        if (!entry.active) return success(entry)

        val updatedAt = clock.instant()
        if (!repository.deactivateCashFlowEntry(context.householdId, entryId, context.userId, updatedAt)) {
            return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
        }
        return success(entry.copy(active = false, updatedAt = updatedAt, updatedByUserId = context.userId))
    }

    suspend fun updateCashFlowEntry(
        context: FinancialContext,
        entryId: UUID,
        command: UpdateCashFlowEntryCommand,
    ): FinancialResult<CashFlowEntry> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val current = repository.findCashFlowEntry(context.householdId, entryId)
            ?: return rejected(FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND)
        if (repository.hasCashFlowSettlements(context.householdId, entryId)) {
            return rejected(FinancialRejection.CASH_FLOW_ENTRY_HAS_SETTLEMENTS)
        }
        validateOptionalAccountAndCategory(
            context,
            command.accountId,
            command.categoryId,
            command.type,
            command.currency,
        )?.let { return rejected(it) }

        val updated = current.copy(
            type = command.type,
            money = command.money,
            currency = command.currency,
            description = command.description,
            accountId = command.accountId,
            categoryId = command.categoryId,
            recurrence = command.recurrence,
            startDate = command.startDate,
            endDate = command.endDate,
            dayOfMonth = command.dayOfMonth,
            active = command.active,
            updatedAt = clock.instant(),
            updatedByUserId = context.userId,
        )
        if (!repository.updateCashFlowEntry(updated)) return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
        return success(updated)
    }

    suspend fun deleteCashFlowEntry(
        context: FinancialContext,
        entryId: UUID,
    ): FinancialResult<CashFlowEntry> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val current = repository.findCashFlowEntry(context.householdId, entryId)
            ?: return rejected(FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND)
        if (repository.hasCashFlowSettlements(context.householdId, entryId)) {
            return rejected(FinancialRejection.CASH_FLOW_ENTRY_HAS_SETTLEMENTS)
        }
        val deletedAt = clock.instant()
        if (!repository.deleteCashFlowEntry(context.householdId, entryId, context.userId, deletedAt)) {
            return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
        }
        return success(current.copy(deletedAt = deletedAt, deletedByUserId = context.userId))
    }

    suspend fun listCashFlowEntries(
        context: FinancialContext,
        filters: CashFlowEntryFilters = CashFlowEntryFilters(),
    ): FinancialResult<List<CashFlowEntry>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return success(repository.listCashFlowEntries(context.householdId, filters))
    }

    suspend fun findCashFlowEntry(
        context: FinancialContext,
        entryId: UUID,
    ): FinancialResult<CashFlowEntry> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return repository.findCashFlowEntry(context.householdId, entryId)
            ?.let(::success)
            ?: rejected(FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND)
    }

    suspend fun getCashFlowOccurrences(
        context: FinancialContext,
        query: GetCashFlowOccurrencesQuery,
    ): FinancialResult<List<CashFlowOccurrenceDetails>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        if (query.to.isAfter(query.from.plusMonths(maxProjectionMonths.toLong()))) {
            return rejected(FinancialRejection.PROJECTION_HORIZON_EXCEEDED)
        }
        query.accountId?.let { accountId ->
            val account = repository.findAccount(context.householdId, accountId)
                ?: return rejected(FinancialRejection.ACCOUNT_NOT_FOUND)
            if (!account.active) return rejected(FinancialRejection.ACCOUNT_INACTIVE)
        }
        val entries = repository.listCashFlowEntries(
            context.householdId,
            CashFlowEntryFilters(
                type = query.type,
                active = null,
                from = query.from,
                to = query.to,
                accountId = query.accountId,
            ),
        )
        val receipts = repository.listCashFlowSettlements(context.householdId, query.from, query.to)
            .associateBy { CashFlowOccurrenceKey(it.settlement.cashFlowEntryId, it.settlement.scheduledOn) }
        val occurrences = entries.flatMap { entry ->
            occurrenceCalculator.occurrences(entry.copy(active = true), query.from, query.to).mapNotNull { occurrence ->
                val receipt = receipts[CashFlowOccurrenceKey(entry.id, occurrence.date)]
                if (!entry.active && receipt == null) return@mapNotNull null
                CashFlowOccurrenceDetails(
                    entry = entry,
                    scheduledOn = occurrence.date,
                    status = if (receipt == null) CashFlowOccurrenceStatus.PENDING else CashFlowOccurrenceStatus.PAID,
                    receipt = receipt,
                )
            }
        }.filter { query.status == null || it.status == query.status }
            .sortedWith(compareBy(CashFlowOccurrenceDetails::scheduledOn, { it.entry.description.value }, { it.entry.id }))
        return success(occurrences)
    }

    suspend fun settleCashFlowOccurrence(
        context: FinancialContext,
        entryId: UUID,
        command: SettleCashFlowOccurrenceCommand,
    ): FinancialResult<Creation<CashFlowSettlementReceipt>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val entry = repository.findCashFlowEntry(context.householdId, entryId)
            ?: return rejected(FinancialRejection.CASH_FLOW_ENTRY_NOT_FOUND)
        val occurrenceExists = occurrenceCalculator
            .occurrences(entry, command.scheduledOn, command.scheduledOn)
            .singleOrNull() != null
        if (!occurrenceExists) return rejected(FinancialRejection.CASH_FLOW_OCCURRENCE_NOT_FOUND)
        repository.findCashFlowSettlement(context.householdId, entryId, command.scheduledOn)?.let {
            return success(Creation(it, created = false))
        }
        val today = LocalDate.now(clock.withZone(householdZone))
        if (command.paidOn.isAfter(today)) return rejected(FinancialRejection.FUTURE_PAYMENT_DATE)

        val accountId = command.accountId ?: entry.accountId
            ?: return rejected(FinancialRejection.SETTLEMENT_ACCOUNT_REQUIRED)
        val categoryId = command.categoryId ?: entry.categoryId
            ?: return rejected(FinancialRejection.SETTLEMENT_CATEGORY_REQUIRED)
        validateAccountAndCategory(context, accountId, categoryId, entry.type, entry.currency)
            ?.let { return rejected(it) }

        val now = clock.instant()
        val transaction = Transaction(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            accountId = accountId,
            type = entry.type,
            money = entry.money,
            currency = entry.currency,
            description = entry.description,
            categoryId = categoryId,
            occurredOn = command.paidOn,
            source = FinancialSource.CASH_FLOW_SETTLEMENT,
            sourceReferenceId = SourceReferenceId.of("cash-flow:${entry.id}:${command.scheduledOn}"),
            createdAt = now,
        )
        val settlement = CashFlowSettlement(
            id = idGenerator(),
            householdId = context.householdId,
            cashFlowEntryId = entry.id,
            scheduledOn = command.scheduledOn,
            transactionId = transaction.id,
            settledByUserId = context.userId,
            settledAt = now,
        )
        val receipt = CashFlowSettlementReceipt(settlement, transaction)
        if (repository.insertCashFlowSettlement(receipt)) return success(Creation(receipt, created = true))
        repository.findCashFlowSettlement(context.householdId, entryId, command.scheduledOn)?.let {
            return success(Creation(it, created = false))
        }
        return rejected(FinancialRejection.PERSISTENCE_CONFLICT)
    }

    suspend fun getCashFlowProjection(
        context: FinancialContext,
        query: GetCashFlowProjectionQuery,
    ): FinancialResult<CashFlowProjection> {
        if (!repository.isActiveMember(context)) return unauthorized()
        val today = LocalDate.now(clock.withZone(householdZone))
        if (query.targetDate.isBefore(today)) return rejected(FinancialRejection.INVALID_PROJECTION_TARGET)
        if (query.targetDate.isAfter(today.plusMonths(maxProjectionMonths.toLong()))) {
            return rejected(FinancialRejection.PROJECTION_HORIZON_EXCEEDED)
        }

        query.accountId?.let { accountId ->
            val account = repository.findAccount(context.householdId, accountId)
                ?: return rejected(FinancialRejection.ACCOUNT_NOT_FOUND)
            if (!account.active) return rejected(FinancialRejection.ACCOUNT_INACTIVE)
        }
        val currentBalance = repository.listTransactions(
            context.householdId,
            TransactionFilters(accountId = query.accountId),
        ).fold(BigDecimal.ZERO) { balance, transaction ->
            when (transaction.type) {
                FinancialEntryType.INCOME -> balance + transaction.money.amount
                FinancialEntryType.EXPENSE -> balance - transaction.money.amount
            }
        }
        val entries = repository.listCashFlowEntries(
            context.householdId,
            CashFlowEntryFilters(
                active = true,
                from = today,
                to = query.targetDate,
                accountId = query.accountId,
            ),
        )
        val settledOccurrences = repository.listCashFlowSettlements(context.householdId, today, query.targetDate)
            .mapTo(mutableSetOf()) { receipt ->
                CashFlowOccurrenceKey(receipt.settlement.cashFlowEntryId, receipt.settlement.scheduledOn)
            }
        return success(
            projectionCalculator.calculate(
                currentBalance,
                entries,
                today,
                query.targetDate,
                settledOccurrences,
            ),
        )
    }

    private suspend fun validateAccountAndCategory(
        context: FinancialContext,
        accountId: UUID,
        categoryId: UUID,
        type: FinancialEntryType,
        currency: Currency,
    ): FinancialRejection? = validateOptionalAccountAndCategory(context, accountId, categoryId, type, currency)

    private suspend fun validateOptionalAccountAndCategory(
        context: FinancialContext,
        accountId: UUID?,
        categoryId: UUID?,
        type: FinancialEntryType,
        currency: Currency,
    ): FinancialRejection? {
        val account = accountId?.let { repository.findAccount(context.householdId, it) }
        if (accountId != null && account == null) return FinancialRejection.ACCOUNT_NOT_FOUND
        if (account != null && !account.active) return FinancialRejection.ACCOUNT_INACTIVE
        if (account != null && account.currency != currency) return FinancialRejection.CURRENCY_MISMATCH

        val category = categoryId?.let { repository.findCategory(context.householdId, it) }
        if (categoryId != null && category == null) return FinancialRejection.CATEGORY_NOT_FOUND
        if (category != null && !category.active) return FinancialRejection.CATEGORY_INACTIVE
        if (category != null && category.type != type) return FinancialRejection.CATEGORY_TYPE_MISMATCH
        return null
    }

    private suspend fun findExistingTransaction(
        context: FinancialContext,
        command: CreateTransactionCommand,
    ): Transaction? = command.sourceReferenceId?.let { reference ->
        repository.findTransactionBySource(context.householdId, command.source, reference)
    }

    private suspend fun findExistingCashFlowEntry(
        context: FinancialContext,
        command: CreateCashFlowEntryCommand,
    ): CashFlowEntry? = command.sourceReferenceId?.let { reference ->
        repository.findCashFlowEntryBySource(context.householdId, command.source, reference)
    }

    private fun <T> success(value: T): FinancialResult<T> = FinancialResult.Success(value)

    private fun rejected(reason: FinancialRejection): FinancialResult.Rejected = FinancialResult.Rejected(reason)

    private fun unauthorized(): FinancialResult.Rejected = rejected(FinancialRejection.UNAUTHORIZED_CONTEXT)

    private companion object {
        const val DEFAULT_MAX_PROJECTION_MONTHS = 24
        const val DEFAULT_HOUSEHOLD_TIME_ZONE = "America/Fortaleza"
    }
}
