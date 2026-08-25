package com.sova.finance.application

import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.Category
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.Currency
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.domain.Transaction
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class CreateAccountCommand(
    val name: AccountName,
    val type: AccountType,
)

data class CreateCategoryCommand(
    val name: CategoryName,
    val type: FinancialEntryType,
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
    val categoryId: UUID,
    val startDate: LocalDate,
    val source: FinancialSource,
    val sourceReferenceId: SourceReferenceId? = null,
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

    suspend fun listTransactions(householdId: UUID, filters: TransactionFilters): List<Transaction>

    suspend fun findCashFlowEntryBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): CashFlowEntry?

    suspend fun insertCashFlowEntry(entry: CashFlowEntry): Boolean

    suspend fun listCashFlowEntries(householdId: UUID): List<CashFlowEntry>
}

class FinancialApplicationService(
    private val repository: FinancialRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = UUID::randomUUID,
) {
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

    suspend fun createCashFlowEntry(
        context: FinancialContext,
        command: CreateCashFlowEntryCommand,
    ): FinancialResult<Creation<CashFlowEntry>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        findExistingCashFlowEntry(context, command)?.let { return success(Creation(it, created = false)) }

        val category = repository.findCategory(context.householdId, command.categoryId)
            ?: return rejected(FinancialRejection.CATEGORY_NOT_FOUND)
        if (!category.active) return rejected(FinancialRejection.CATEGORY_INACTIVE)
        if (category.type != command.type) return rejected(FinancialRejection.CATEGORY_TYPE_MISMATCH)

        val now = clock.instant()
        val entry = CashFlowEntry(
            id = idGenerator(),
            householdId = context.householdId,
            createdByUserId = context.userId,
            type = command.type,
            money = command.money,
            currency = command.currency,
            description = command.description,
            categoryId = category.id,
            recurrence = CashFlowRecurrence.ONCE,
            startDate = command.startDate,
            endDate = null,
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

    suspend fun listCashFlowEntries(context: FinancialContext): FinancialResult<List<CashFlowEntry>> {
        if (!repository.isActiveMember(context)) return unauthorized()
        return success(repository.listCashFlowEntries(context.householdId))
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
}
