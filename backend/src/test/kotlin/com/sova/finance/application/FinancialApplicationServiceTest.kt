package com.sova.finance.application

import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.Category
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.domain.Transaction
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FinancialApplicationServiceTest {
    private val householdId = id("10000000-0000-0000-0000-000000000000")
    private val otherHouseholdId = id("10000000-0000-0000-0000-000000000001")
    private val userId = id("20000000-0000-0000-0000-000000000000")
    private val otherUserId = id("20000000-0000-0000-0000-000000000001")
    private val context = FinancialContext(householdId, userId)
    private val otherContext = FinancialContext(otherHouseholdId, otherUserId)
    private val now = Instant.parse("2026-08-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `active member creates and lists isolated financial records with authorship`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context, otherContext))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Checking"), AccountType.CHECKING),
        ).createdValue()
        val category = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Food"), FinancialEntryType.EXPENSE),
        ).createdValue()
        val otherAccount = service.createAccount(
            otherContext,
            CreateAccountCommand(AccountName.of("Other"), AccountType.CASH),
        ).createdValue()

        val recent = service.createTransaction(
            context,
            transactionCommand(account.id, category.id, "source-1", LocalDate.parse("2026-08-23")),
        ).createdValue()
        service.createTransaction(
            context,
            transactionCommand(account.id, category.id, "source-2", LocalDate.parse("2026-08-20")),
        ).createdValue()

        assertEquals(householdId, recent.householdId)
        assertEquals(userId, recent.createdByUserId)
        assertEquals(BigDecimal("42.30"), recent.money.amount)
        assertEquals(now, recent.createdAt)
        assertEquals(listOf(account), service.listAccounts(context).value())
        assertEquals(listOf(category), service.listCategories(context).value())
        assertEquals(listOf(otherAccount), service.listAccounts(otherContext).value())

        val filtered = service.listTransactions(
            context,
            TransactionFilters(
                occurredFrom = LocalDate.parse("2026-08-22"),
                type = FinancialEntryType.EXPENSE,
                accountId = account.id,
                categoryId = category.id,
            ),
        ).value()
        assertEquals(listOf(recent), filtered)
        assertTrue(service.listTransactions(otherContext).value().isEmpty())
    }

    @Test
    fun `application rejects invalid context duplicate names and incompatible references`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Checking"), AccountType.CHECKING),
        ).createdValue()
        val expense = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Food"), FinancialEntryType.EXPENSE),
        ).createdValue()

        assertRejected(FinancialRejection.UNAUTHORIZED_CONTEXT, service.listAccounts(otherContext))
        assertRejected(
            FinancialRejection.DUPLICATE_ACCOUNT_NAME,
            service.createAccount(context, CreateAccountCommand(AccountName.of("checking"), AccountType.CASH)),
        )
        assertRejected(
            FinancialRejection.DUPLICATE_CATEGORY_NAME,
            service.createCategory(
                context,
                CreateCategoryCommand(CategoryName.of("food"), FinancialEntryType.EXPENSE),
            ),
        )
        assertRejected(
            FinancialRejection.ACCOUNT_NOT_FOUND,
            service.createTransaction(
                context,
                transactionCommand(UUID.randomUUID(), expense.id, "missing-account"),
            ),
        )
        assertRejected(
            FinancialRejection.CATEGORY_NOT_FOUND,
            service.createTransaction(
                context,
                transactionCommand(account.id, UUID.randomUUID(), "missing-category"),
            ),
        )
        assertRejected(
            FinancialRejection.CATEGORY_TYPE_MISMATCH,
            service.createTransaction(
                context,
                transactionCommand(account.id, expense.id, "income", type = FinancialEntryType.INCOME),
            ),
        )
        assertRejected(
            FinancialRejection.CATEGORY_TYPE_MISMATCH,
            service.createCashFlowEntry(
                context,
                CreateCashFlowEntryCommand(
                    type = FinancialEntryType.INCOME,
                    money = Money.of(BigDecimal("1.00")),
                    description = FinancialDescription.of("Invalid cash flow"),
                    categoryId = expense.id,
                    startDate = LocalDate.parse("2026-09-01"),
                    source = FinancialSource.DASHBOARD,
                ),
            ),
        )

        repository.accounts[account.id] = account.copy(active = false)
        assertRejected(
            FinancialRejection.ACCOUNT_INACTIVE,
            service.createTransaction(context, transactionCommand(account.id, expense.id, "inactive-account")),
        )
        repository.accounts[account.id] = account
        repository.categories[expense.id] = expense.copy(active = false)
        assertRejected(
            FinancialRejection.CATEGORY_INACTIVE,
            service.createTransaction(context, transactionCommand(account.id, expense.id, "inactive-category")),
        )
    }

    @Test
    fun `source references are idempotent and cash flow remains one-time`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Cash"), AccountType.CASH),
        ).createdValue()
        val category = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Salary"), FinancialEntryType.INCOME),
        ).createdValue()
        val transactionCommand = transactionCommand(
            accountId = account.id,
            categoryId = category.id,
            reference = "same-transaction",
            type = FinancialEntryType.INCOME,
        )

        val first = service.createTransaction(context, transactionCommand).creation()
        val duplicate = service.createTransaction(context, transactionCommand).creation()
        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertSame(first.value, duplicate.value)
        assertEquals(1, repository.transactions.size)

        val cashFlowCommand = CreateCashFlowEntryCommand(
            type = FinancialEntryType.INCOME,
            money = Money.of(BigDecimal("5000")),
            description = FinancialDescription.of("Salary"),
            categoryId = category.id,
            startDate = LocalDate.parse("2026-09-01"),
            source = FinancialSource.DASHBOARD,
            sourceReferenceId = SourceReferenceId.of("same-cash-flow"),
        )
        val cashFlow = service.createCashFlowEntry(context, cashFlowCommand).creation()
        val cashFlowDuplicate = service.createCashFlowEntry(context, cashFlowCommand).creation()

        assertTrue(cashFlow.created)
        assertFalse(cashFlowDuplicate.created)
        assertEquals(null, cashFlow.value.endDate)
        assertEquals("ONCE", cashFlow.value.recurrence.name)
        assertEquals(listOf(cashFlow.value), service.listCashFlowEntries(context).value())
        assertEquals(1, repository.cashFlowEntries.size)
    }

    private fun service(repository: InMemoryFinancialRepository): FinancialApplicationService {
        val ids = (1..20).map {
            id("30000000-0000-0000-0000-${it.toString().padStart(12, '0')}")
        }.iterator()
        return FinancialApplicationService(repository, clock) { ids.next() }
    }

    private fun transactionCommand(
        accountId: UUID,
        categoryId: UUID,
        reference: String,
        occurredOn: LocalDate = LocalDate.parse("2026-08-24"),
        type: FinancialEntryType = FinancialEntryType.EXPENSE,
    ) = CreateTransactionCommand(
        accountId = accountId,
        type = type,
        money = Money.of(BigDecimal("42.30")),
        description = FinancialDescription.of("Lunch"),
        categoryId = categoryId,
        occurredOn = occurredOn,
        source = FinancialSource.DASHBOARD,
        sourceReferenceId = SourceReferenceId.of(reference),
    )
}

private class InMemoryFinancialRepository(
    private val activeContexts: Set<FinancialContext>,
) : FinancialRepository {
    val accounts = linkedMapOf<UUID, Account>()
    val categories = linkedMapOf<UUID, Category>()
    val transactions = linkedMapOf<UUID, Transaction>()
    val cashFlowEntries = linkedMapOf<UUID, CashFlowEntry>()

    override suspend fun isActiveMember(context: FinancialContext): Boolean = context in activeContexts

    override suspend fun findAccount(householdId: UUID, accountId: UUID): Account? =
        accounts[accountId]?.takeIf { it.householdId == householdId }

    override suspend fun findAccountByName(householdId: UUID, name: AccountName): Account? =
        accounts.values.find { it.householdId == householdId && it.name.value.equals(name.value, ignoreCase = true) }

    override suspend fun insertAccount(account: Account): Boolean {
        if (findAccountByName(account.householdId, account.name) != null) return false
        accounts[account.id] = account
        return true
    }

    override suspend fun listAccounts(householdId: UUID): List<Account> =
        accounts.values.filter { it.householdId == householdId }.sortedBy { it.name.value.lowercase() }

    override suspend fun findCategory(householdId: UUID, categoryId: UUID): Category? =
        categories[categoryId]?.takeIf { it.householdId == householdId }

    override suspend fun findCategoryByName(
        householdId: UUID,
        type: FinancialEntryType,
        name: CategoryName,
    ): Category? = categories.values.find {
        it.householdId == householdId && it.type == type && it.name.value.equals(name.value, ignoreCase = true)
    }

    override suspend fun insertCategory(category: Category): Boolean {
        if (findCategoryByName(category.householdId, category.type, category.name) != null) return false
        categories[category.id] = category
        return true
    }

    override suspend fun listCategories(householdId: UUID): List<Category> =
        categories.values.filter { it.householdId == householdId }.sortedBy { it.name.value.lowercase() }

    override suspend fun findTransactionBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): Transaction? = transactions.values.find {
        it.householdId == householdId && it.source == source && it.sourceReferenceId == sourceReferenceId
    }

    override suspend fun insertTransaction(transaction: Transaction): Boolean {
        val reference = transaction.sourceReferenceId
        if (reference != null && findTransactionBySource(transaction.householdId, transaction.source, reference) != null) {
            return false
        }
        transactions[transaction.id] = transaction
        return true
    }

    override suspend fun listTransactions(householdId: UUID, filters: TransactionFilters): List<Transaction> =
        transactions.values.asSequence()
            .filter { it.householdId == householdId }
            .filter { filters.occurredFrom == null || !it.occurredOn.isBefore(filters.occurredFrom) }
            .filter { filters.occurredTo == null || !it.occurredOn.isAfter(filters.occurredTo) }
            .filter { filters.type == null || it.type == filters.type }
            .filter { filters.accountId == null || it.accountId == filters.accountId }
            .filter { filters.categoryId == null || it.categoryId == filters.categoryId }
            .sortedWith(compareByDescending<Transaction> { it.occurredOn }.thenByDescending { it.createdAt })
            .toList()

    override suspend fun findCashFlowEntryBySource(
        householdId: UUID,
        source: FinancialSource,
        sourceReferenceId: SourceReferenceId,
    ): CashFlowEntry? = cashFlowEntries.values.find {
        it.householdId == householdId && it.source == source && it.sourceReferenceId == sourceReferenceId
    }

    override suspend fun insertCashFlowEntry(entry: CashFlowEntry): Boolean {
        val reference = entry.sourceReferenceId
        if (reference != null && findCashFlowEntryBySource(entry.householdId, entry.source, reference) != null) {
            return false
        }
        cashFlowEntries[entry.id] = entry
        return true
    }

    override suspend fun listCashFlowEntries(householdId: UUID): List<CashFlowEntry> =
        cashFlowEntries.values.filter { it.householdId == householdId }.sortedBy { it.startDate }
}

private fun <T> FinancialResult<T>.value(): T = assertIs<FinancialResult.Success<T>>(this).value

private fun <T> FinancialResult<Creation<T>>.creation(): Creation<T> = value()

private fun <T> FinancialResult<Creation<T>>.createdValue(): T = creation().also { assertTrue(it.created) }.value

private fun assertRejected(expected: FinancialRejection, actual: FinancialResult<*>) {
    assertEquals(expected, assertIs<FinancialResult.Rejected>(actual).reason)
}

private fun id(value: String): UUID = UUID.fromString(value)
