package com.sova.finance.application

import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.CashFlowSettlementReceipt
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

    @Test
    fun `monthly and one-time entries produce isolated account projections and support deactivation`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context, otherContext))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Joint checking"), AccountType.CHECKING),
        ).createdValue()
        val incomeCategory = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Salary"), FinancialEntryType.INCOME),
        ).createdValue()
        val expenseCategory = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Housing"), FinancialEntryType.EXPENSE),
        ).createdValue()
        val otherCategory = service.createCategory(
            otherContext,
            CreateCategoryCommand(CategoryName.of("Other income"), FinancialEntryType.INCOME),
        ).createdValue()

        service.createTransaction(
            context,
            CreateTransactionCommand(
                accountId = account.id,
                type = FinancialEntryType.INCOME,
                money = Money.of(BigDecimal("1000.00")),
                description = FinancialDescription.of("Opening income"),
                categoryId = incomeCategory.id,
                occurredOn = LocalDate.parse("2026-08-20"),
                source = FinancialSource.DASHBOARD,
                sourceReferenceId = SourceReferenceId.of("opening-income"),
            ),
        ).createdValue()
        service.createTransaction(
            context,
            CreateTransactionCommand(
                accountId = account.id,
                type = FinancialEntryType.EXPENSE,
                money = Money.of(BigDecimal("200.00")),
                description = FinancialDescription.of("Opening expense"),
                categoryId = expenseCategory.id,
                occurredOn = LocalDate.parse("2026-08-21"),
                source = FinancialSource.DASHBOARD,
                sourceReferenceId = SourceReferenceId.of("opening-expense"),
            ),
        ).createdValue()

        val monthlyIncome = service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.INCOME,
                amount = "500.00",
                description = "Monthly income",
                accountId = account.id,
                categoryId = incomeCategory.id,
                recurrence = CashFlowRecurrence.MONTHLY,
                startDate = "2026-08-01",
                dayOfMonth = 5,
                reference = "monthly-income",
            ),
        ).createdValue()
        service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.EXPENSE,
                amount = "200.00",
                description = "Monthly housing",
                accountId = account.id,
                categoryId = expenseCategory.id,
                recurrence = CashFlowRecurrence.MONTHLY,
                startDate = "2026-08-01",
                dayOfMonth = 10,
                reference = "monthly-expense",
            ),
        ).createdValue()
        service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.EXPENSE,
                amount = "50.00",
                description = "One-time insurance",
                accountId = account.id,
                categoryId = expenseCategory.id,
                startDate = "2026-08-25",
                reference = "once-expense",
            ),
        ).createdValue()
        service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.INCOME,
                amount = "100.00",
                description = "Unassigned income",
                categoryId = incomeCategory.id,
                startDate = "2026-09-01",
                reference = "unassigned-income",
            ),
        ).createdValue()
        val inactive = service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.EXPENSE,
                amount = "9999.00",
                description = "Inactive expense",
                accountId = account.id,
                categoryId = expenseCategory.id,
                recurrence = CashFlowRecurrence.MONTHLY,
                startDate = "2026-08-01",
                dayOfMonth = 25,
                reference = "inactive-expense",
            ),
        ).createdValue()
        val deactivated = service.deactivateCashFlowEntry(context, inactive.id).value()
        assertFalse(deactivated.active)
        assertEquals(now, deactivated.updatedAt)

        service.createCashFlowEntry(
            otherContext,
            cashFlowCommand(
                type = FinancialEntryType.INCOME,
                amount = "9000.00",
                description = "Other household income",
                categoryId = otherCategory.id,
                recurrence = CashFlowRecurrence.MONTHLY,
                startDate = "2026-08-01",
                dayOfMonth = 1,
                reference = "other-income",
            ),
        ).createdValue()

        val targetDate = LocalDate.parse("2026-09-10")
        val general = service.getCashFlowProjection(context, GetCashFlowProjectionQuery(targetDate)).value()
        assertEquals(BigDecimal("800.00"), general.currentBalance)
        assertEquals(BigDecimal("600.00"), general.expectedIncome)
        assertEquals(BigDecimal("250.00"), general.expectedExpenses)
        assertEquals(BigDecimal("1150.00"), general.projectedBalance)
        assertEquals(4, general.occurrenceCount)
        assertEquals(2, general.incomeOccurrenceCount)
        assertEquals(2, general.expenseOccurrenceCount)

        val accountProjection = service.getCashFlowProjection(
            context,
            GetCashFlowProjectionQuery(targetDate, account.id),
        ).value()
        assertEquals(BigDecimal("500.00"), accountProjection.expectedIncome)
        assertEquals(BigDecimal("250.00"), accountProjection.expectedExpenses)
        assertEquals(BigDecimal("1050.00"), accountProjection.projectedBalance)
        assertEquals(3, accountProjection.occurrenceCount)
        assertEquals(1, accountProjection.incomeOccurrenceCount)
        assertEquals(2, accountProjection.expenseOccurrenceCount)

        val activeForAccount = service.listCashFlowEntries(
            context,
            CashFlowEntryFilters(active = true, accountId = account.id),
        ).value()
        assertEquals(3, activeForAccount.size)
        assertTrue(activeForAccount.all { it.householdId == householdId && it.createdByUserId == userId })
        assertEquals(
            listOf(deactivated),
            service.listCashFlowEntries(context, CashFlowEntryFilters(active = false)).value(),
        )
        assertTrue(
            service.listCashFlowEntries(otherContext).value().none { it.id == monthlyIncome.id },
        )
        assertRejected(
            FinancialRejection.INVALID_PROJECTION_TARGET,
            service.getCashFlowProjection(
                context,
                GetCashFlowProjectionQuery(LocalDate.parse("2026-08-23")),
            ),
        )
        assertRejected(
            FinancialRejection.PROJECTION_HORIZON_EXCEEDED,
            service.getCashFlowProjection(
                context,
                GetCashFlowProjectionQuery(LocalDate.parse("2028-08-25")),
            ),
        )
    }

    @Test
    fun `updates and soft deletes transactions with authenticated authorship`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Checking"), AccountType.CHECKING),
        ).createdValue()
        val category = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Food"), FinancialEntryType.EXPENSE),
        ).createdValue()
        val created = service.createTransaction(
            context,
            transactionCommand(account.id, category.id, "editable"),
        ).createdValue()

        val updated = service.updateTransaction(
            context,
            created.id,
            UpdateTransactionCommand(
                accountId = account.id,
                type = FinancialEntryType.EXPENSE,
                money = Money.of(BigDecimal("99.90")),
                description = FinancialDescription.of("Corrected lunch"),
                categoryId = category.id,
                occurredOn = LocalDate.parse("2026-08-23"),
            ),
        ).value()

        assertEquals(BigDecimal("99.90"), updated.money.amount)
        assertEquals("Corrected lunch", updated.description.value)
        assertEquals(userId, updated.updatedByUserId)
        assertEquals(listOf(updated), service.listTransactions(context).value())

        val deleted = service.deleteTransaction(context, created.id).value()
        assertEquals(userId, deleted.deletedByUserId)
        assertTrue(service.listTransactions(context).value().isEmpty())
        assertEquals(userId, repository.transactions.getValue(created.id).deletedByUserId)
        assertRejected(FinancialRejection.TRANSACTION_NOT_FOUND, service.deleteTransaction(context, created.id))
    }

    @Test
    fun `settles one planned occurrence once and removes it from future projection`() = runBlocking {
        val repository = InMemoryFinancialRepository(setOf(context))
        val service = service(repository)
        val account = service.createAccount(
            context,
            CreateAccountCommand(AccountName.of("Checking"), AccountType.CHECKING),
        ).createdValue()
        val category = service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of("Housing"), FinancialEntryType.EXPENSE),
        ).createdValue()
        val entry = service.createCashFlowEntry(
            context,
            cashFlowCommand(
                type = FinancialEntryType.EXPENSE,
                amount = "300.00",
                description = "Rent",
                accountId = account.id,
                categoryId = category.id,
                recurrence = CashFlowRecurrence.MONTHLY,
                startDate = "2026-08-01",
                dayOfMonth = 10,
                reference = "rent",
            ),
        ).createdValue()

        val pending = service.getCashFlowOccurrences(
            context,
            GetCashFlowOccurrencesQuery(
                from = LocalDate.parse("2026-09-01"),
                to = LocalDate.parse("2026-09-30"),
                status = CashFlowOccurrenceStatus.PENDING,
            ),
        ).value()
        assertEquals(listOf(LocalDate.parse("2026-09-10")), pending.map { it.scheduledOn })

        val command = SettleCashFlowOccurrenceCommand(
            scheduledOn = LocalDate.parse("2026-09-10"),
            paidOn = LocalDate.parse("2026-08-24"),
        )
        val first = service.settleCashFlowOccurrence(context, entry.id, command).creation()
        val duplicate = service.settleCashFlowOccurrence(context, entry.id, command).creation()

        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertEquals(first.value, duplicate.value)
        assertEquals(FinancialSource.CASH_FLOW_SETTLEMENT, first.value.transaction.source)
        assertEquals(LocalDate.parse("2026-08-24"), first.value.transaction.occurredOn)
        assertEquals(userId, first.value.settlement.settledByUserId)
        assertEquals(1, repository.transactions.size)

        val paid = service.getCashFlowOccurrences(
            context,
            GetCashFlowOccurrencesQuery(
                from = LocalDate.parse("2026-09-01"),
                to = LocalDate.parse("2026-09-30"),
                status = CashFlowOccurrenceStatus.PAID,
            ),
        ).value()
        assertEquals(first.value, paid.single().receipt)

        val projection = service.getCashFlowProjection(
            context,
            GetCashFlowProjectionQuery(LocalDate.parse("2026-09-30")),
        ).value()
        assertEquals(BigDecimal("-300.00"), projection.currentBalance)
        assertEquals(BigDecimal("0.00"), projection.expectedExpenses)
        assertEquals(BigDecimal("-300.00"), projection.projectedBalance)
        assertRejected(
            FinancialRejection.CASH_FLOW_ENTRY_HAS_SETTLEMENTS,
            service.deleteCashFlowEntry(context, entry.id),
        )
        assertRejected(
            FinancialRejection.RECORD_MANAGED_BY_CASH_FLOW,
            service.deleteTransaction(context, first.value.transaction.id),
        )
        assertRejected(
            FinancialRejection.FUTURE_PAYMENT_DATE,
            service.settleCashFlowOccurrence(
                context,
                entry.id,
                command.copy(scheduledOn = LocalDate.parse("2026-10-10"), paidOn = LocalDate.parse("2026-08-25")),
            ),
        )
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

    private fun cashFlowCommand(
        type: FinancialEntryType,
        amount: String,
        description: String,
        categoryId: UUID,
        startDate: String,
        reference: String,
        accountId: UUID? = null,
        recurrence: CashFlowRecurrence = CashFlowRecurrence.ONCE,
        dayOfMonth: Int? = null,
    ) = CreateCashFlowEntryCommand(
        type = type,
        money = Money.of(BigDecimal(amount)),
        description = FinancialDescription.of(description),
        accountId = accountId,
        categoryId = categoryId,
        recurrence = recurrence,
        startDate = LocalDate.parse(startDate),
        dayOfMonth = dayOfMonth,
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
    val cashFlowSettlements = linkedMapOf<Pair<UUID, LocalDate>, CashFlowSettlementReceipt>()

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

    override suspend fun findTransaction(householdId: UUID, transactionId: UUID): Transaction? =
        transactions[transactionId]?.takeIf { it.householdId == householdId && it.deletedAt == null }

    override suspend fun updateTransaction(transaction: Transaction): Boolean {
        if (findTransaction(transaction.householdId, transaction.id) == null) return false
        transactions[transaction.id] = transaction
        return true
    }

    override suspend fun deleteTransaction(
        householdId: UUID,
        transactionId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean {
        val transaction = findTransaction(householdId, transactionId) ?: return false
        transactions[transactionId] = transaction.copy(
            updatedAt = deletedAt,
            updatedByUserId = deletedByUserId,
            deletedAt = deletedAt,
            deletedByUserId = deletedByUserId,
        )
        return true
    }

    override suspend fun listTransactions(householdId: UUID, filters: TransactionFilters): List<Transaction> =
        transactions.values.asSequence()
            .filter { it.householdId == householdId }
            .filter { it.deletedAt == null }
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

    override suspend fun findCashFlowEntry(householdId: UUID, entryId: UUID): CashFlowEntry? =
        cashFlowEntries[entryId]?.takeIf { it.householdId == householdId && it.deletedAt == null }

    override suspend fun deactivateCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        updatedByUserId: UUID,
        updatedAt: Instant,
    ): Boolean {
        val entry = findCashFlowEntry(householdId, entryId)?.takeIf(CashFlowEntry::active) ?: return false
        cashFlowEntries[entryId] = entry.copy(
            active = false,
            updatedAt = updatedAt,
            updatedByUserId = updatedByUserId,
        )
        return true
    }

    override suspend fun updateCashFlowEntry(entry: CashFlowEntry): Boolean {
        if (findCashFlowEntry(entry.householdId, entry.id) == null || hasCashFlowSettlements(entry.householdId, entry.id)) {
            return false
        }
        cashFlowEntries[entry.id] = entry
        return true
    }

    override suspend fun deleteCashFlowEntry(
        householdId: UUID,
        entryId: UUID,
        deletedByUserId: UUID,
        deletedAt: Instant,
    ): Boolean {
        val entry = findCashFlowEntry(householdId, entryId) ?: return false
        if (hasCashFlowSettlements(householdId, entryId)) return false
        cashFlowEntries[entryId] = entry.copy(
            active = false,
            updatedAt = deletedAt,
            updatedByUserId = deletedByUserId,
            deletedAt = deletedAt,
            deletedByUserId = deletedByUserId,
        )
        return true
    }

    override suspend fun listCashFlowEntries(
        householdId: UUID,
        filters: CashFlowEntryFilters,
    ): List<CashFlowEntry> = cashFlowEntries.values.asSequence()
        .filter { it.householdId == householdId }
        .filter { it.deletedAt == null }
        .filter { filters.type == null || it.type == filters.type }
        .filter { filters.active == null || it.active == filters.active }
        .filter { filters.from == null || it.endDate == null || !it.endDate.isBefore(filters.from) }
        .filter { filters.to == null || !it.startDate.isAfter(filters.to) }
        .filter { filters.accountId == null || it.accountId == filters.accountId }
        .sortedBy { it.startDate }
        .toList()

    override suspend fun findCashFlowSettlement(
        householdId: UUID,
        entryId: UUID,
        scheduledOn: LocalDate,
    ): CashFlowSettlementReceipt? = cashFlowSettlements[entryId to scheduledOn]
        ?.takeIf { it.settlement.householdId == householdId }

    override suspend fun listCashFlowSettlements(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CashFlowSettlementReceipt> = cashFlowSettlements.values.filter {
        it.settlement.householdId == householdId &&
            !it.settlement.scheduledOn.isBefore(from) &&
            !it.settlement.scheduledOn.isAfter(to)
    }

    override suspend fun hasCashFlowSettlements(householdId: UUID, entryId: UUID): Boolean =
        cashFlowSettlements.values.any {
            it.settlement.householdId == householdId && it.settlement.cashFlowEntryId == entryId
        }

    override suspend fun insertCashFlowSettlement(receipt: CashFlowSettlementReceipt): Boolean {
        val key = receipt.settlement.cashFlowEntryId to receipt.settlement.scheduledOn
        if (cashFlowSettlements.containsKey(key)) return false
        if (!insertTransaction(receipt.transaction)) return false
        cashFlowSettlements[key] = receipt
        return true
    }
}

private fun <T> FinancialResult<T>.value(): T = assertIs<FinancialResult.Success<T>>(this).value

private fun <T> FinancialResult<Creation<T>>.creation(): Creation<T> = value()

private fun <T> FinancialResult<Creation<T>>.createdValue(): T = creation().also { assertTrue(it.created) }.value

private fun assertRejected(expected: FinancialRejection, actual: FinancialResult<*>) {
    assertEquals(expected, assertIs<FinancialResult.Rejected>(actual).reason)
}

private fun id(value: String): UUID = UUID.fromString(value)
