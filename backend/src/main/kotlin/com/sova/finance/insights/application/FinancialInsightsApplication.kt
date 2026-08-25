package com.sova.finance.insights.application

import com.sova.finance.application.FinancialRejection
import com.sova.finance.application.FinancialResult
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.insights.domain.BudgetDeviationSnapshot
import com.sova.finance.insights.domain.CategoryMetric
import com.sova.finance.insights.domain.ExpenseTrend
import com.sova.finance.insights.domain.FinancialHealthSnapshot
import com.sova.finance.insights.domain.FinancialInsightsCalculator
import com.sova.finance.insights.domain.FinancialSnapshot
import com.sova.finance.insights.domain.InsightPeriod
import com.sova.finance.insights.domain.LargestExpenseMetric
import com.sova.finance.insights.domain.PeriodComparison
import com.sova.finance.insights.domain.PeriodTotals
import com.sova.finance.insights.domain.RecurringExpense
import com.sova.finance.insights.domain.RecurringExpenseOccurrence
import com.sova.finance.insights.domain.TrendGrouping
import com.sova.finance.insights.domain.TrendPoint
import com.sova.finance.insights.domain.overlappingMonthCount
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class GetFinancialSnapshotQuery(val period: InsightPeriod)

data class ComparePeriodsQuery(
    val currentPeriod: InsightPeriod,
    val comparisonPeriod: InsightPeriod,
)

data class GetExpenseTrendQuery(
    val period: InsightPeriod,
    val grouping: TrendGrouping,
    val categoryId: UUID? = null,
    val categoryName: String? = null,
)

data class GetLargestExpensesQuery(
    val period: InsightPeriod,
    val limit: Int = DEFAULT_LARGEST_EXPENSE_LIMIT,
    val categoryId: UUID? = null,
) {
    init {
        require(limit in 1..MAX_LARGEST_EXPENSE_LIMIT) {
            "Largest expense limit must be between 1 and $MAX_LARGEST_EXPENSE_LIMIT"
        }
    }

    companion object {
        const val DEFAULT_LARGEST_EXPENSE_LIMIT = 10
        const val MAX_LARGEST_EXPENSE_LIMIT = 20
    }
}

data class GetRecurringExpensesQuery(val period: InsightPeriod)

data class GetBudgetDeviationsQuery(
    val currentPeriod: InsightPeriod,
    val savingsTarget: BigDecimal? = null,
) {
    init {
        require(savingsTarget == null || savingsTarget > BigDecimal.ZERO) { "Savings target must be positive" }
    }
}

data class GetFinancialHealthSnapshotQuery(val period: InsightPeriod)

interface FinancialInsightsRepository {
    suspend fun isActiveMember(context: FinancialContext): Boolean

    suspend fun periodTotals(householdId: UUID, period: InsightPeriod): PeriodTotals

    suspend fun categoryMetrics(
        householdId: UUID,
        period: InsightPeriod,
        type: FinancialEntryType,
    ): List<CategoryMetric>

    suspend fun largestExpenses(
        householdId: UUID,
        period: InsightPeriod,
        limit: Int,
        categoryId: UUID?,
    ): List<LargestExpenseMetric>

    suspend fun expenseTrend(
        householdId: UUID,
        period: InsightPeriod,
        grouping: TrendGrouping,
        categoryId: UUID?,
    ): List<TrendPoint>

    suspend fun recurringExpenseOccurrences(
        householdId: UUID,
        period: InsightPeriod,
    ): List<RecurringExpenseOccurrence>

    suspend fun currentBalance(householdId: UUID): BigDecimal
}

class FinancialInsightsApplicationService(
    private val repository: FinancialInsightsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val householdZone: ZoneId = ZoneId.of(DEFAULT_HOUSEHOLD_TIME_ZONE),
    private val calculator: FinancialInsightsCalculator = FinancialInsightsCalculator(),
    private val baselineMonthCount: Int = DEFAULT_BASELINE_MONTH_COUNT,
) {
    init {
        require(baselineMonthCount > 0) { "Baseline month count must be positive" }
    }

    suspend fun getFinancialSnapshot(
        context: FinancialContext,
        query: GetFinancialSnapshotQuery,
    ): FinancialResult<FinancialSnapshot> = authorized(context) {
        validatePeriod(query.period, MAX_GENERAL_INSIGHT_DAYS)
        val totals = repository.periodTotals(context.householdId, query.period)
        val inclusiveDays = ChronoUnit.DAYS.between(query.period.from, query.period.to) + 1
        FinancialSnapshot(
            period = query.period,
            totals = totals,
            topExpenseCategories = repository.categoryMetrics(
                context.householdId,
                query.period,
                FinancialEntryType.EXPENSE,
            ).take(TOP_CATEGORY_LIMIT),
            topIncomeCategories = repository.categoryMetrics(
                context.householdId,
                query.period,
                FinancialEntryType.INCOME,
            ).take(TOP_CATEGORY_LIMIT),
            largestExpenses = repository.largestExpenses(
                context.householdId,
                query.period,
                SNAPSHOT_LARGEST_EXPENSE_LIMIT,
                null,
            ),
            averageDailyExpense = totals.totalExpenses
                .divide(BigDecimal(inclusiveDays), MONEY_SCALE, RoundingMode.HALF_UP),
        )
    }

    suspend fun comparePeriods(
        context: FinancialContext,
        query: ComparePeriodsQuery,
    ): FinancialResult<PeriodComparison> = authorized(context) {
        validatePeriod(query.currentPeriod, MAX_GENERAL_INSIGHT_DAYS)
        validatePeriod(query.comparisonPeriod, MAX_GENERAL_INSIGHT_DAYS)
        val currentTotals = repository.periodTotals(context.householdId, query.currentPeriod)
        val comparisonTotals = repository.periodTotals(context.householdId, query.comparisonPeriod)
        val currentCategories = repository.categoryMetrics(
            context.householdId,
            query.currentPeriod,
            FinancialEntryType.EXPENSE,
        )
        val comparisonCategories = repository.categoryMetrics(
            context.householdId,
            query.comparisonPeriod,
            FinancialEntryType.EXPENSE,
        )
        PeriodComparison(
            currentPeriod = query.currentPeriod,
            comparisonPeriod = query.comparisonPeriod,
            income = calculator.change(currentTotals.totalIncome, comparisonTotals.totalIncome),
            expenses = calculator.change(currentTotals.totalExpenses, comparisonTotals.totalExpenses),
            categoryChanges = calculator.categoryChanges(currentCategories, comparisonCategories)
                .take(MAX_CATEGORY_RESULTS),
        )
    }

    suspend fun getExpenseTrend(
        context: FinancialContext,
        query: GetExpenseTrendQuery,
    ): FinancialResult<ExpenseTrend> = authorized(context) {
        validatePeriod(query.period, MAX_TREND_DAYS)
        calculator.trend(
            period = query.period,
            grouping = query.grouping,
            category = query.categoryName,
            aggregatedPoints = repository.expenseTrend(
                context.householdId,
                query.period,
                query.grouping,
                query.categoryId,
            ),
        )
    }

    suspend fun getLargestExpenses(
        context: FinancialContext,
        query: GetLargestExpensesQuery,
    ): FinancialResult<List<LargestExpenseMetric>> = authorized(context) {
        validatePeriod(query.period, MAX_GENERAL_INSIGHT_DAYS)
        repository.largestExpenses(context.householdId, query.period, query.limit, query.categoryId)
    }

    suspend fun getRecurringExpenses(
        context: FinancialContext,
        query: GetRecurringExpensesQuery,
    ): FinancialResult<List<RecurringExpense>> = authorized(context) {
        validatePeriod(query.period, MAX_RECURRING_INSIGHT_DAYS)
        calculator.recurringExpenses(
            repository.recurringExpenseOccurrences(context.householdId, query.period),
        ).take(MAX_RECURRING_RESULTS)
    }

    suspend fun getBudgetDeviations(
        context: FinancialContext,
        query: GetBudgetDeviationsQuery,
    ): FinancialResult<BudgetDeviationSnapshot> = authorized(context) {
        validatePeriod(query.currentPeriod, MAX_CURRENT_BUDGET_DAYS)
        val baselinePeriods = baselinePeriods(query.currentPeriod.from)
        val current = repository.categoryMetrics(
            context.householdId,
            query.currentPeriod,
            FinancialEntryType.EXPENSE,
        )
        val baseline = baselinePeriods.map { period ->
            repository.categoryMetrics(context.householdId, period, FinancialEntryType.EXPENSE)
        }
        val deviations = calculator.budgetDeviations(current, baseline).take(MAX_CATEGORY_RESULTS)
        BudgetDeviationSnapshot(
            currentPeriod = query.currentPeriod,
            baselinePeriods = baselinePeriods,
            deviations = deviations,
            savingsPlan = query.savingsTarget?.let { target -> calculator.savingsPlan(target, deviations) },
        )
    }

    suspend fun getFinancialHealthSnapshot(
        context: FinancialContext,
        query: GetFinancialHealthSnapshotQuery,
    ): FinancialResult<FinancialHealthSnapshot> = authorized(context) {
        validatePeriod(query.period, MAX_GENERAL_INSIGHT_DAYS)
        val totals = repository.periodTotals(context.householdId, query.period)
        val expenseCategories = repository.categoryMetrics(
            context.householdId,
            query.period,
            FinancialEntryType.EXPENSE,
        )
        val monthCount = BigDecimal(overlappingMonthCount(query.period))
        val averageIncome = totals.totalIncome.divide(monthCount, MONEY_SCALE, RoundingMode.HALF_UP)
        val averageExpenses = totals.totalExpenses.divide(monthCount, MONEY_SCALE, RoundingMode.HALF_UP)
        val averageNet = (averageIncome - averageExpenses).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val trend = calculator.trend(
            query.period,
            TrendGrouping.MONTH,
            null,
            repository.expenseTrend(context.householdId, query.period, TrendGrouping.MONTH, null),
        )
        val currentBalance = repository.currentBalance(context.householdId).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val fixedExpenses = expenseCategories
            .filter { it.nature == ExpenseNature.FIXED }
            .fold(BigDecimal.ZERO) { total, metric -> total + metric.total }
        val discretionaryExpenses = expenseCategories
            .filter { it.nature == ExpenseNature.DISCRETIONARY }
            .fold(BigDecimal.ZERO) { total, metric -> total + metric.total }
        val today = LocalDate.now(clock.withZone(householdZone))
        val currentMonth = InsightPeriod(YearMonth.from(today).atDay(1), today)
        val currentMonthTotals = repository.periodTotals(context.householdId, currentMonth)

        FinancialHealthSnapshot(
            period = query.period,
            averageMonthlyIncome = averageIncome,
            averageMonthlyExpenses = averageExpenses,
            averageMonthlyNetCashFlow = averageNet,
            savingsRate = calculator.savingsRate(totals.totalIncome, totals.totalExpenses),
            fixedExpenseRatio = calculator.expenseRatio(fixedExpenses, totals.totalExpenses),
            discretionaryExpenseRatio = calculator.expenseRatio(discretionaryExpenses, totals.totalExpenses),
            expenseTrend = trend.direction,
            cashReserveMonths = if (averageExpenses.signum() == 0) null else {
                currentBalance.max(BigDecimal.ZERO)
                    .divide(averageExpenses, RATIO_SCALE, RoundingMode.HALF_UP)
            },
            currentAvailableBalance = currentBalance,
            monthEndProjection = calculator.monthEndProjection(
                today,
                currentMonthTotals.totalExpenses,
                currentBalance,
            ),
        )
    }

    private suspend fun <T> authorized(
        context: FinancialContext,
        block: suspend () -> T,
    ): FinancialResult<T> = if (repository.isActiveMember(context)) {
        FinancialResult.Success(block())
    } else {
        FinancialResult.Rejected(FinancialRejection.UNAUTHORIZED_CONTEXT)
    }

    private fun validatePeriod(period: InsightPeriod, maximumDays: Long) {
        val days = ChronoUnit.DAYS.between(period.from, period.to) + 1
        require(days <= maximumDays) { "Insight period exceeds the maximum of $maximumDays days" }
    }

    private fun baselinePeriods(currentFrom: LocalDate): List<InsightPeriod> {
        val previousMonth = YearMonth.from(currentFrom).minusMonths(1)
        return (baselineMonthCount - 1 downTo 0).map { offset ->
            val month = previousMonth.minusMonths(offset.toLong())
            InsightPeriod(month.atDay(1), month.atEndOfMonth())
        }
    }

    private companion object {
        const val DEFAULT_HOUSEHOLD_TIME_ZONE = "America/Fortaleza"
        const val DEFAULT_BASELINE_MONTH_COUNT = 3
        const val TOP_CATEGORY_LIMIT = 5
        const val SNAPSHOT_LARGEST_EXPENSE_LIMIT = 5
        const val MAX_CATEGORY_RESULTS = 20
        const val MAX_RECURRING_RESULTS = 20
        const val MAX_GENERAL_INSIGHT_DAYS = 3_660L
        const val MAX_TREND_DAYS = 730L
        const val MAX_RECURRING_INSIGHT_DAYS = 730L
        const val MAX_CURRENT_BUDGET_DAYS = 366L
        const val MONEY_SCALE = 2
        const val RATIO_SCALE = 2
    }
}
