package com.sova.dashboard.application

import com.sova.finance.application.CashFlowEntryFilters
import com.sova.finance.application.CashFlowOccurrenceDetails
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialRejection
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.GetCashFlowOccurrencesQuery
import com.sova.finance.application.GetCashFlowProjectionQuery
import com.sova.finance.application.GetCashFlowSettlementsQuery
import com.sova.finance.application.TransactionFilters
import com.sova.finance.domain.Account
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.CashFlowSettlementStatus
import com.sova.finance.domain.Category
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class DashboardPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        require(!from.isAfter(to)) { "Dashboard period start must not be after its end" }
    }
}

data class DashboardOverview(
    val period: DashboardPeriod,
    val currentBalance: BigDecimal,
    val projectedBalance: BigDecimal,
    val realizedIncome: BigDecimal,
    val realizedExpense: BigDecimal,
    val realizedResult: BigDecimal,
    val plannedIncome: BigDecimal,
    val plannedExpense: BigDecimal,
    val pendingIncome: BigDecimal,
    val pendingExpense: BigDecimal,
    val freeMoney: BigDecimal,
    val investedMoney: BigDecimal,
    val debt: BigDecimal,
    val netWorth: BigDecimal,
    val investedPercentage: BigDecimal,
    val accounts: List<DashboardAccountBalance>,
    val categories: List<DashboardCategorySummary>,
    val upcoming: List<CashFlowOccurrenceDetails>,
)

data class DashboardAccountBalance(
    val account: Account,
    val balance: BigDecimal,
)

data class DashboardCategorySummary(
    val category: Category,
    val realized: BigDecimal,
    val percentageOfExpenses: BigDecimal,
)

enum class DashboardTimelineStatus {
    PENDING,
    SETTLED,
    OVERDUE,
}

data class DashboardTimelineFilters(
    val type: FinancialEntryType? = null,
    val status: DashboardTimelineStatus? = null,
    val categoryId: UUID? = null,
    val recurring: Boolean? = null,
)

data class DashboardTimelineItem(
    val occurrence: CashFlowOccurrenceDetails,
    val category: Category?,
    val status: DashboardTimelineStatus,
    val settledAmount: BigDecimal?,
    val settledAt: java.time.Instant?,
)

class DashboardQueryRejected(val rejection: FinancialRejection) : RuntimeException(rejection.name)

class DashboardQueryService(
    private val financialService: FinancialApplicationService,
    private val clock: Clock = Clock.systemUTC(),
    private val householdZone: ZoneId = ZoneId.of("America/Fortaleza"),
) {
    suspend fun overview(context: FinancialContext, period: DashboardPeriod): DashboardOverview {
        val accounts = financialService.listAccounts(context).valueOrThrow().filter(Account::active)
        val categories = financialService.listCategories(context).valueOrThrow().associateBy(Category::id)
        val allTransactions = financialService.listTransactions(context).valueOrThrow()
        val periodTransactions = allTransactions.filter { it.occurredOn in period.from..period.to }
        val occurrences = financialService.getCashFlowOccurrences(
            context,
            GetCashFlowOccurrencesQuery(period.from, period.to),
        ).valueOrThrow()
        val pending = occurrences.filter { it.receipt == null }
        val today = LocalDate.now(clock.withZone(householdZone))
        val currentBalance = allTransactions.signedTotal()
        val projectedBalance = if (period.to.isBefore(today)) {
            allTransactions.filter { !it.occurredOn.isAfter(period.to) }.signedTotal()
        } else {
            financialService.getCashFlowProjection(context, GetCashFlowProjectionQuery(period.to))
                .valueOrThrow().projectedBalance
        }
        val accountBalances = accounts.map { account ->
            DashboardAccountBalance(
                account,
                allTransactions.filter { it.accountId == account.id }.signedTotal(),
            )
        }
        val freeMoney = accountBalances
            .filter { it.account.type in LIQUID_ACCOUNT_TYPES }
            .sumPositiveBalances()
        val investedMoney = accountBalances
            .filter { it.account.type == AccountType.INVESTMENT }
            .sumPositiveBalances()
        val debt = accountBalances
            .filter { it.account.type == AccountType.CREDIT_CARD || it.balance < BigDecimal.ZERO }
            .fold(BigDecimal.ZERO) { total, account -> total + account.balance.min(BigDecimal.ZERO).abs() }
            .moneyScale()
        val realizedExpense = periodTransactions.total(FinancialEntryType.EXPENSE)
        val realizedIncome = periodTransactions.total(FinancialEntryType.INCOME)
        val categorySummaries = periodTransactions
            .filter { it.type == FinancialEntryType.EXPENSE }
            .groupBy(Transaction::categoryId)
            .mapNotNull { (categoryId, transactions) ->
                categories[categoryId]?.let { category ->
                    val total = transactions.fold(BigDecimal.ZERO) { sum, transaction ->
                        sum + transaction.money.amount
                    }.moneyScale()
                    DashboardCategorySummary(
                        category,
                        total,
                        if (realizedExpense.signum() == 0) BigDecimal.ZERO.setScale(2) else {
                            total.multiply(HUNDRED).divide(realizedExpense, 2, RoundingMode.HALF_UP)
                        },
                    )
                }
            }
            .sortedByDescending(DashboardCategorySummary::realized)
        return DashboardOverview(
            period = period,
            currentBalance = currentBalance,
            projectedBalance = projectedBalance,
            realizedIncome = realizedIncome,
            realizedExpense = realizedExpense,
            realizedResult = (realizedIncome - realizedExpense).moneyScale(),
            plannedIncome = occurrences.plannedTotal(FinancialEntryType.INCOME),
            plannedExpense = occurrences.plannedTotal(FinancialEntryType.EXPENSE),
            pendingIncome = pending.plannedTotal(FinancialEntryType.INCOME),
            pendingExpense = pending.plannedTotal(FinancialEntryType.EXPENSE),
            freeMoney = freeMoney,
            investedMoney = investedMoney,
            debt = debt,
            netWorth = (freeMoney + investedMoney - debt).moneyScale(),
            investedPercentage = investedMoney.percentageOf(freeMoney + investedMoney),
            accounts = accountBalances,
            categories = categorySummaries,
            upcoming = pending.filter { !it.scheduledOn.isBefore(today) }.take(8),
        )
    }

    suspend fun transactions(context: FinancialContext, filters: TransactionFilters): List<Transaction> =
        financialService.listTransactions(context, filters).valueOrThrow()

    suspend fun cashFlowEntries(context: FinancialContext, filters: CashFlowEntryFilters): List<CashFlowEntry> =
        financialService.listCashFlowEntries(context, filters).valueOrThrow()

    suspend fun occurrences(
        context: FinancialContext,
        query: GetCashFlowOccurrencesQuery,
    ): List<CashFlowOccurrenceDetails> = financialService.getCashFlowOccurrences(context, query).valueOrThrow()

    suspend fun timeline(
        context: FinancialContext,
        period: DashboardPeriod,
        filters: DashboardTimelineFilters,
    ): List<DashboardTimelineItem> {
        val today = LocalDate.now(clock.withZone(householdZone))
        val categories = financialService.listCategories(context).valueOrThrow().associateBy(Category::id)
        return occurrences(context, GetCashFlowOccurrencesQuery(period.from, period.to, type = filters.type))
            .asSequence()
            .filter { filters.categoryId == null || it.entry.categoryId == filters.categoryId }
            .filter {
                filters.recurring == null ||
                    (it.entry.recurrence != CashFlowRecurrence.ONCE) == filters.recurring
            }
            .map { occurrence ->
                val status = when {
                    occurrence.receipt != null -> DashboardTimelineStatus.SETTLED
                    occurrence.scheduledOn.isBefore(today) -> DashboardTimelineStatus.OVERDUE
                    else -> DashboardTimelineStatus.PENDING
                }
                DashboardTimelineItem(
                    occurrence = occurrence,
                    category = occurrence.entry.categoryId?.let(categories::get),
                    status = status,
                    settledAmount = occurrence.receipt?.transaction?.money?.amount,
                    settledAt = occurrence.receipt?.settlement?.settledAt,
                )
            }
            .filter { filters.status == null || it.status == filters.status }
            .sortedBy { it.occurrence.scheduledOn }
            .toList()
    }

    suspend fun settlements(context: FinancialContext, period: DashboardPeriod) =
        financialService.listCashFlowSettlements(
            context,
            GetCashFlowSettlementsQuery(period.from, period.to, CashFlowSettlementStatus.ACTIVE),
        ).valueOrThrow()

    private fun List<Transaction>.signedTotal(): BigDecimal = fold(BigDecimal.ZERO) { total, transaction ->
        when (transaction.type) {
            FinancialEntryType.INCOME -> total + transaction.money.amount
            FinancialEntryType.EXPENSE -> total - transaction.money.amount
        }
    }.moneyScale()

    private fun List<Transaction>.total(type: FinancialEntryType): BigDecimal =
        filter { it.type == type }.fold(BigDecimal.ZERO) { total, transaction -> total + transaction.money.amount }
            .moneyScale()

    private fun List<CashFlowOccurrenceDetails>.plannedTotal(type: FinancialEntryType): BigDecimal =
        filter { it.entry.type == type }.fold(BigDecimal.ZERO) { total, occurrence ->
            total + occurrence.entry.money.amount
        }.moneyScale()

    private fun List<DashboardAccountBalance>.sumPositiveBalances(): BigDecimal =
        fold(BigDecimal.ZERO) { total, account -> total + account.balance.max(BigDecimal.ZERO) }.moneyScale()

    private fun BigDecimal.moneyScale(): BigDecimal = setScale(2, RoundingMode.UNNECESSARY)

    private fun BigDecimal.percentageOf(total: BigDecimal): BigDecimal =
        if (total.signum() == 0) BigDecimal.ZERO.setScale(2) else {
            multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)
        }

    private fun <T> FinancialResult<T>.valueOrThrow(): T = when (this) {
        is FinancialResult.Success -> value
        is FinancialResult.Rejected -> throw DashboardQueryRejected(reason)
    }

    private companion object {
        val LIQUID_ACCOUNT_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.OTHER)
        val HUNDRED = BigDecimal("100")
    }
}
