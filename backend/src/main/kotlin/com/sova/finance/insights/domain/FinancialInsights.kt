package com.sova.finance.insights.domain

import com.sova.finance.domain.Currency
import com.sova.finance.domain.ExpenseNature
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

data class InsightPeriod(
    val from: LocalDate,
    val to: LocalDate,
) {
    init {
        require(!from.isAfter(to)) { "Insight period start must not be after its end" }
    }
}

data class PeriodTotals(
    val totalIncome: BigDecimal,
    val totalExpenses: BigDecimal,
    val transactionCount: Int,
) {
    val netCashFlow: BigDecimal = (totalIncome - totalExpenses).money()
}

data class CategoryMetric(
    val categoryId: UUID,
    val category: String,
    val nature: ExpenseNature,
    val total: BigDecimal,
    val transactionCount: Int,
)

data class LargestExpenseMetric(
    val transactionId: UUID,
    val occurredOn: LocalDate,
    val description: String,
    val category: String,
    val amount: BigDecimal,
    val currency: Currency,
)

data class FinancialSnapshot(
    val period: InsightPeriod,
    val totals: PeriodTotals,
    val topExpenseCategories: List<CategoryMetric>,
    val topIncomeCategories: List<CategoryMetric>,
    val largestExpenses: List<LargestExpenseMetric>,
    val averageDailyExpense: BigDecimal,
)

data class MetricChange(
    val current: BigDecimal,
    val comparison: BigDecimal,
    val absoluteChange: BigDecimal,
    val percentageChange: BigDecimal?,
)

data class CategoryChange(
    val category: String,
    val nature: ExpenseNature,
    val currentTotal: BigDecimal,
    val comparisonTotal: BigDecimal,
    val absoluteChange: BigDecimal,
    val percentageChange: BigDecimal?,
)

data class PeriodComparison(
    val currentPeriod: InsightPeriod,
    val comparisonPeriod: InsightPeriod,
    val income: MetricChange,
    val expenses: MetricChange,
    val categoryChanges: List<CategoryChange>,
)

enum class TrendGrouping {
    MONTH,
    WEEK,
}

enum class TrendDirection {
    UP,
    DOWN,
    STABLE,
}

data class TrendPoint(
    val periodStart: LocalDate,
    val total: BigDecimal,
)

data class ExpenseTrend(
    val period: InsightPeriod,
    val grouping: TrendGrouping,
    val category: String?,
    val points: List<TrendPoint>,
    val absoluteChange: BigDecimal?,
    val percentageChange: BigDecimal?,
    val direction: TrendDirection?,
)

data class RecurringExpenseOccurrence(
    val normalizedDescription: String,
    val description: String,
    val category: String,
    val amount: BigDecimal,
    val occurredOn: LocalDate,
)

enum class RecurrenceConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

data class RecurringExpense(
    val description: String,
    val category: String,
    val averageAmount: BigDecimal,
    val occurrenceCount: Int,
    val estimatedFrequency: String,
    val lastOccurrence: LocalDate,
    val confidence: RecurrenceConfidence,
)

enum class DeviationSeverity {
    LOW,
    MEDIUM,
    HIGH,
}

data class BudgetDeviation(
    val category: String,
    val nature: ExpenseNature,
    val currentAmount: BigDecimal,
    val baselineAverage: BigDecimal,
    val absoluteDeviation: BigDecimal,
    val percentageDeviation: BigDecimal?,
    val severity: DeviationSeverity,
)

data class SavingsSuggestion(
    val category: String,
    val availableExpense: BigDecimal,
    val suggestedCut: BigDecimal,
    val maximumCutRatio: BigDecimal,
)

data class SavingsPlan(
    val targetAmount: BigDecimal,
    val plannedAmount: BigDecimal,
    val remainingAmount: BigDecimal,
    val suggestions: List<SavingsSuggestion>,
)

data class BudgetDeviationSnapshot(
    val currentPeriod: InsightPeriod,
    val baselinePeriods: List<InsightPeriod>,
    val deviations: List<BudgetDeviation>,
    val savingsPlan: SavingsPlan?,
)

data class MonthEndProjection(
    val asOf: LocalDate,
    val actualExpensesSoFar: BigDecimal,
    val projectedExpenses: BigDecimal?,
    val projectedEndBalance: BigDecimal?,
    val projectionMethod: String,
    val daysObserved: Int,
    val daysInPeriod: Int,
    val minimumObservedDays: Int,
)

data class FinancialHealthSnapshot(
    val period: InsightPeriod,
    val averageMonthlyIncome: BigDecimal,
    val averageMonthlyExpenses: BigDecimal,
    val averageMonthlyNetCashFlow: BigDecimal,
    val savingsRate: BigDecimal?,
    val fixedExpenseRatio: BigDecimal?,
    val discretionaryExpenseRatio: BigDecimal?,
    val expenseTrend: TrendDirection?,
    val cashReserveMonths: BigDecimal?,
    val currentAvailableBalance: BigDecimal,
    val monthEndProjection: MonthEndProjection,
)

class FinancialInsightsCalculator(
    private val stableTrendThresholdPercentage: BigDecimal = DEFAULT_STABLE_TREND_THRESHOLD,
    private val minimumProjectionDays: Int = DEFAULT_MINIMUM_PROJECTION_DAYS,
    private val maximumSavingsCutRatio: BigDecimal = DEFAULT_MAXIMUM_SAVINGS_CUT_RATIO,
) {
    init {
        require(stableTrendThresholdPercentage >= BigDecimal.ZERO)
        require(minimumProjectionDays > 0)
        require(maximumSavingsCutRatio > BigDecimal.ZERO && maximumSavingsCutRatio <= BigDecimal.ONE)
    }

    fun change(current: BigDecimal, comparison: BigDecimal): MetricChange {
        val currentMoney = current.money()
        val comparisonMoney = comparison.money()
        return MetricChange(
            current = currentMoney,
            comparison = comparisonMoney,
            absoluteChange = (currentMoney - comparisonMoney).money(),
            percentageChange = percentageChange(currentMoney, comparisonMoney),
        )
    }

    fun categoryChanges(
        current: List<CategoryMetric>,
        comparison: List<CategoryMetric>,
    ): List<CategoryChange> {
        val currentById = current.associateBy(CategoryMetric::categoryId)
        val comparisonById = comparison.associateBy(CategoryMetric::categoryId)
        return (currentById.keys + comparisonById.keys).map { categoryId ->
            val currentMetric = currentById[categoryId]
            val comparisonMetric = comparisonById[categoryId]
            val reference = requireNotNull(currentMetric ?: comparisonMetric)
            val metricChange = change(
                currentMetric?.total ?: BigDecimal.ZERO,
                comparisonMetric?.total ?: BigDecimal.ZERO,
            )
            CategoryChange(
                category = reference.category,
                nature = reference.nature,
                currentTotal = metricChange.current,
                comparisonTotal = metricChange.comparison,
                absoluteChange = metricChange.absoluteChange,
                percentageChange = metricChange.percentageChange,
            )
        }.sortedWith(compareByDescending<CategoryChange> { it.absoluteChange.abs() }.thenBy { it.category })
    }

    fun trend(
        period: InsightPeriod,
        grouping: TrendGrouping,
        category: String?,
        aggregatedPoints: List<TrendPoint>,
    ): ExpenseTrend {
        val totalsByPeriod = aggregatedPoints.associate { it.periodStart to it.total.money() }
        val points = generatePeriodStarts(period, grouping).map { start ->
            TrendPoint(start, totalsByPeriod[start] ?: ZERO_MONEY)
        }
        if (points.size < 2) return ExpenseTrend(period, grouping, category, points, null, null, null)

        val metricChange = change(points.last().total, points.first().total)
        val direction = when {
            metricChange.percentageChange == null -> if (metricChange.current == ZERO_MONEY) {
                TrendDirection.STABLE
            } else {
                TrendDirection.UP
            }
            metricChange.percentageChange.abs() < stableTrendThresholdPercentage -> TrendDirection.STABLE
            metricChange.absoluteChange.signum() > 0 -> TrendDirection.UP
            else -> TrendDirection.DOWN
        }
        return ExpenseTrend(
            period = period,
            grouping = grouping,
            category = category,
            points = points,
            absoluteChange = metricChange.absoluteChange,
            percentageChange = metricChange.percentageChange,
            direction = direction,
        )
    }

    fun recurringExpenses(occurrences: List<RecurringExpenseOccurrence>): List<RecurringExpense> = occurrences
        .groupBy { it.normalizedDescription to it.category }
        .mapNotNull { (_, grouped) -> recurringExpense(grouped) }
        .sortedWith(
            compareByDescending<RecurringExpense> { it.confidence }
                .thenByDescending { it.averageAmount }
                .thenBy { it.description },
        )

    fun budgetDeviations(
        current: List<CategoryMetric>,
        baselineMonths: List<List<CategoryMetric>>,
    ): List<BudgetDeviation> {
        require(baselineMonths.isNotEmpty()) { "Budget deviations require at least one baseline month" }
        val baselineByCategory = baselineMonths
            .flatMap { month -> month.map { it.categoryId to it } }
            .groupBy({ it.first }, { it.second })
        val currentByCategory = current.associateBy(CategoryMetric::categoryId)
        return (currentByCategory.keys + baselineByCategory.keys).map { categoryId ->
            val currentMetric = currentByCategory[categoryId]
            val baselineMetrics = baselineByCategory[categoryId].orEmpty()
            val reference = requireNotNull(currentMetric ?: baselineMetrics.firstOrNull())
            val currentAmount = currentMetric?.total?.money() ?: ZERO_MONEY
            val baselineTotal = baselineMetrics.fold(BigDecimal.ZERO) { total, metric -> total + metric.total }
            val baselineAverage = baselineTotal
                .divide(BigDecimal(baselineMonths.size), MONEY_SCALE, RoundingMode.HALF_UP)
            val metricChange = change(currentAmount, baselineAverage)
            BudgetDeviation(
                category = reference.category,
                nature = reference.nature,
                currentAmount = currentAmount,
                baselineAverage = baselineAverage,
                absoluteDeviation = metricChange.absoluteChange,
                percentageDeviation = metricChange.percentageChange,
                severity = deviationSeverity(metricChange.percentageChange, currentAmount, baselineAverage),
            )
        }.sortedWith(compareByDescending<BudgetDeviation> { it.absoluteDeviation.abs() }.thenBy { it.category })
    }

    fun savingsPlan(target: BigDecimal, deviations: List<BudgetDeviation>): SavingsPlan {
        require(target > BigDecimal.ZERO) { "Savings target must be positive" }
        val targetMoney = target.money()
        var remaining = targetMoney
        val suggestions = deviations
            .asSequence()
            .filter { it.nature == ExpenseNature.DISCRETIONARY && it.currentAmount > BigDecimal.ZERO }
            .sortedWith(compareByDescending<BudgetDeviation> { it.absoluteDeviation }.thenByDescending { it.currentAmount })
            .mapNotNull { deviation ->
                if (remaining <= BigDecimal.ZERO) return@mapNotNull null
                val availableCut = deviation.currentAmount
                    .multiply(maximumSavingsCutRatio)
                    .setScale(MONEY_SCALE, RoundingMode.DOWN)
                val suggestedCut = minOf(availableCut, remaining).money()
                if (suggestedCut <= BigDecimal.ZERO) return@mapNotNull null
                remaining = (remaining - suggestedCut).money()
                SavingsSuggestion(
                    category = deviation.category,
                    availableExpense = deviation.currentAmount,
                    suggestedCut = suggestedCut,
                    maximumCutRatio = maximumSavingsCutRatio.rate(),
                )
            }.toList()
        return SavingsPlan(
            targetAmount = targetMoney,
            plannedAmount = (targetMoney - remaining).money(),
            remainingAmount = remaining.money(),
            suggestions = suggestions,
        )
    }

    fun savingsRate(income: BigDecimal, expenses: BigDecimal): BigDecimal? = if (income.signum() == 0) {
        null
    } else {
        (income - expenses).divide(income, RATE_SCALE, RoundingMode.HALF_UP)
    }

    fun expenseRatio(expensesOfNature: BigDecimal, totalExpenses: BigDecimal): BigDecimal? =
        if (totalExpenses.signum() == 0) null
        else expensesOfNature.divide(totalExpenses, RATE_SCALE, RoundingMode.HALF_UP)

    fun monthEndProjection(
        asOf: LocalDate,
        actualExpensesSoFar: BigDecimal,
        currentAvailableBalance: BigDecimal? = null,
    ): MonthEndProjection {
        val daysObserved = asOf.dayOfMonth
        val daysInPeriod = YearMonth.from(asOf).lengthOfMonth()
        val actual = actualExpensesSoFar.money()
        val projected = if (daysObserved < minimumProjectionDays) {
            null
        } else {
            actual.divide(BigDecimal(daysObserved), CALCULATION_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal(daysInPeriod))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        }
        return MonthEndProjection(
            asOf = asOf,
            actualExpensesSoFar = actual,
            projectedExpenses = projected,
            projectedEndBalance = projected?.let { projectedExpenses ->
                currentAvailableBalance?.let { balance ->
                    (balance - (projectedExpenses - actual)).money()
                }
            },
            projectionMethod = "DAILY_EXPENSE_RUN_RATE",
            daysObserved = daysObserved,
            daysInPeriod = daysInPeriod,
            minimumObservedDays = minimumProjectionDays,
        )
    }

    private fun recurringExpense(grouped: List<RecurringExpenseOccurrence>): RecurringExpense? {
        if (grouped.size < 2) return null
        val sorted = grouped.sortedBy(RecurringExpenseOccurrence::occurredOn)
        val distinctDates = sorted.map(RecurringExpenseOccurrence::occurredOn).distinct()
        if (distinctDates.size < 2) return null
        val intervals = distinctDates.zipWithNext { first, second -> ChronoUnit.DAYS.between(first, second).toInt() }
        val looselyMonthly = intervals.all { it in LOOSE_MONTHLY_INTERVAL }
        if (!looselyMonthly) return null

        val average = sorted.fold(BigDecimal.ZERO) { total, item -> total + item.amount }
            .divide(BigDecimal(sorted.size), MONEY_SCALE, RoundingMode.HALF_UP)
        val amountRange = sorted.maxOf { it.amount } - sorted.minOf { it.amount }
        val variation = if (average.signum() == 0) BigDecimal.ZERO else {
            amountRange.divide(average, RATE_SCALE, RoundingMode.HALF_UP)
        }
        if (variation > MAX_RECURRING_AMOUNT_VARIATION) return null

        val stronglyMonthly = intervals.all { it in STRONG_MONTHLY_INTERVAL }
        val stableAmount = variation <= STRONG_RECURRING_AMOUNT_VARIATION
        val confidence = when {
            distinctDates.size >= 4 && stronglyMonthly && stableAmount -> RecurrenceConfidence.HIGH
            distinctDates.size >= 3 && stronglyMonthly && stableAmount -> RecurrenceConfidence.MEDIUM
            distinctDates.size >= 4 && stableAmount -> RecurrenceConfidence.MEDIUM
            else -> RecurrenceConfidence.LOW
        }
        return RecurringExpense(
            description = sorted.groupingBy(RecurringExpenseOccurrence::description)
                .eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?.key
                ?: sorted.first().description,
            category = sorted.first().category,
            averageAmount = average,
            occurrenceCount = distinctDates.size,
            estimatedFrequency = "MONTHLY",
            lastOccurrence = distinctDates.last(),
            confidence = confidence,
        )
    }

    private fun deviationSeverity(
        percentage: BigDecimal?,
        current: BigDecimal,
        baseline: BigDecimal,
    ): DeviationSeverity {
        if (percentage == null) return if (current > baseline) DeviationSeverity.HIGH else DeviationSeverity.LOW
        return when {
            percentage.abs() < LOW_DEVIATION_THRESHOLD -> DeviationSeverity.LOW
            percentage.abs() <= HIGH_DEVIATION_THRESHOLD -> DeviationSeverity.MEDIUM
            else -> DeviationSeverity.HIGH
        }
    }

    private fun generatePeriodStarts(period: InsightPeriod, grouping: TrendGrouping): List<LocalDate> {
        val first = when (grouping) {
            TrendGrouping.MONTH -> period.from.withDayOfMonth(1)
            TrendGrouping.WEEK -> period.from.minusDays((period.from.dayOfWeek.value - 1).toLong())
        }
        return generateSequence(first) { current ->
            when (grouping) {
                TrendGrouping.MONTH -> current.plusMonths(1)
                TrendGrouping.WEEK -> current.plusWeeks(1)
            }
        }.takeWhile { it <= period.to }.toList()
    }

    private companion object {
        val DEFAULT_STABLE_TREND_THRESHOLD = BigDecimal("5.00")
        const val DEFAULT_MINIMUM_PROJECTION_DAYS = 5
        val DEFAULT_MAXIMUM_SAVINGS_CUT_RATIO = BigDecimal("0.3000")
        val LOW_DEVIATION_THRESHOLD = BigDecimal("10.00")
        val HIGH_DEVIATION_THRESHOLD = BigDecimal("25.00")
        val STRONG_RECURRING_AMOUNT_VARIATION = BigDecimal("0.1000")
        val MAX_RECURRING_AMOUNT_VARIATION = BigDecimal("0.2000")
        val STRONG_MONTHLY_INTERVAL = 25..35
        val LOOSE_MONTHLY_INTERVAL = 21..40
        val ZERO_MONEY = BigDecimal("0.00")
        const val MONEY_SCALE = 2
        const val RATE_SCALE = 4
        const val CALCULATION_SCALE = 8
    }
}

fun percentageChange(current: BigDecimal, baseline: BigDecimal): BigDecimal? = if (baseline.signum() == 0) {
    null
} else {
    (current - baseline)
        .divide(baseline.abs(), PERCENTAGE_CALCULATION_SCALE, RoundingMode.HALF_UP)
        .multiply(BigDecimal("100"))
        .setScale(PERCENTAGE_SCALE, RoundingMode.HALF_UP)
}

fun overlappingMonthCount(period: InsightPeriod): Int {
    val from = YearMonth.from(period.from)
    val to = YearMonth.from(period.to)
    return (ChronoUnit.MONTHS.between(from, to) + 1).toInt()
}

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

private fun BigDecimal.rate(): BigDecimal = setScale(4, RoundingMode.HALF_UP)

private const val PERCENTAGE_SCALE = 2
private const val PERCENTAGE_CALCULATION_SCALE = 8
