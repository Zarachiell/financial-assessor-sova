package com.sova.finance.insights.domain

import com.sova.finance.domain.ExpenseNature
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinancialInsightsTest {
    private val calculator = FinancialInsightsCalculator()

    @Test
    fun `savings rate supports positive negative and zero income`() {
        assertEquals(decimal("0.2500"), calculator.savingsRate(decimal("1000"), decimal("750")))
        assertEquals(decimal("-0.2000"), calculator.savingsRate(decimal("1000"), decimal("1200")))
        assertNull(calculator.savingsRate(BigDecimal.ZERO, decimal("100")))
    }

    @Test
    fun `percentage change returns null for a zero baseline`() {
        assertNull(percentageChange(decimal("100"), BigDecimal.ZERO))
        assertEquals(decimal("25.00"), percentageChange(decimal("125"), decimal("100")))
        assertEquals(decimal("-25.00"), percentageChange(decimal("75"), decimal("100")))
    }

    @Test
    fun `monthly trend fills missing periods and detects growth`() {
        val period = period("2026-01-01", "2026-03-31")
        val trend = calculator.trend(
            period,
            TrendGrouping.MONTH,
            null,
            listOf(
                TrendPoint(date("2026-01-01"), decimal("100")),
                TrendPoint(date("2026-03-01"), decimal("200")),
            ),
        )

        assertEquals(listOf(decimal("100.00"), decimal("0.00"), decimal("200.00")), trend.points.map { it.total })
        assertEquals(TrendDirection.UP, trend.direction)
        assertEquals(decimal("100.00"), trend.percentageChange)
    }

    @Test
    fun `weekly trend detects decline and stable movement`() {
        val period = period("2026-08-03", "2026-08-16")
        val down = calculator.trend(
            period,
            TrendGrouping.WEEK,
            null,
            listOf(
                TrendPoint(date("2026-08-03"), decimal("200")),
                TrendPoint(date("2026-08-10"), decimal("100")),
            ),
        )
        val stable = calculator.trend(
            period,
            TrendGrouping.WEEK,
            null,
            listOf(
                TrendPoint(date("2026-08-03"), decimal("100")),
                TrendPoint(date("2026-08-10"), decimal("104.99")),
            ),
        )

        assertEquals(TrendDirection.DOWN, down.direction)
        assertEquals(TrendDirection.STABLE, stable.direction)
    }

    @Test
    fun `single trend period has no inferred direction and empty data stays factual`() {
        val single = calculator.trend(
            period("2026-08-01", "2026-08-31"),
            TrendGrouping.MONTH,
            null,
            emptyList(),
        )
        val emptyQuarter = calculator.trend(
            period("2026-06-01", "2026-08-31"),
            TrendGrouping.MONTH,
            null,
            emptyList(),
        )

        assertNull(single.direction)
        assertEquals(listOf(decimal("0.00")), single.points.map { it.total })
        assertEquals(TrendDirection.STABLE, emptyQuarter.direction)
        assertTrue(emptyQuarter.points.all { it.total == decimal("0.00") })
    }

    @Test
    fun `recurring heuristic assigns high medium and low confidence`() {
        val high = recurring("netflix", "Netflix", listOf("2026-01-10", "2026-02-10", "2026-03-10", "2026-04-10"))
        val medium = recurring("academia", "Academia", listOf("2026-01-05", "2026-02-05", "2026-03-05"))
        val low = recurring("seguro", "Seguro", listOf("2026-01-20", "2026-02-20"))

        val detected = calculator.recurringExpenses(high + medium + low).associateBy { it.description }

        assertEquals(RecurrenceConfidence.HIGH, detected.getValue("Netflix").confidence)
        assertEquals(RecurrenceConfidence.MEDIUM, detected.getValue("Academia").confidence)
        assertEquals(RecurrenceConfidence.LOW, detected.getValue("Seguro").confidence)
    }

    @Test
    fun `recurring heuristic accepts small amount changes and avoids false positives`() {
        val changing = listOf(
            occurrence("internet", "Internet", "2026-01-10", "99.90"),
            occurrence("internet", "Internet 02-2026", "2026-02-10", "104.90"),
            occurrence("internet", "Internet", "2026-03-10", "101.90"),
        )
        val falsePositive = listOf(
            occurrence("mercado", "Mercado", "2026-01-01", "100"),
            occurrence("mercado", "Mercado", "2026-01-12", "105"),
            occurrence("mercado", "Mercado", "2026-01-25", "102"),
        )

        val detected = calculator.recurringExpenses(changing + falsePositive)

        assertEquals(listOf("Internet"), detected.map { it.description })
        assertEquals(decimal("102.23"), detected.single().averageAmount)
    }

    @Test
    fun `budget deviations use three month average and never emit infinity`() {
        val categoryId = UUID.randomUUID()
        val current = listOf(metric(categoryId, "Restaurante", "500", ExpenseNature.DISCRETIONARY))
        val baseline = listOf(
            listOf(metric(categoryId, "Restaurante", "200", ExpenseNature.DISCRETIONARY)),
            listOf(metric(categoryId, "Restaurante", "300", ExpenseNature.DISCRETIONARY)),
            emptyList(),
        )

        val deviation = calculator.budgetDeviations(current, baseline).single()

        assertEquals(decimal("166.67"), deviation.baselineAverage)
        assertEquals(decimal("333.33"), deviation.absoluteDeviation)
        assertEquals(decimal("199.99"), deviation.percentageDeviation)
        assertEquals(DeviationSeverity.HIGH, deviation.severity)

        val newSpend = calculator.budgetDeviations(current, listOf(emptyList(), emptyList(), emptyList())).single()
        assertNull(newSpend.percentageDeviation)
        assertEquals(DeviationSeverity.HIGH, newSpend.severity)
    }

    @Test
    fun `savings plan only cuts discretionary spending and caps each cut at thirty percent`() {
        val deviations = listOf(
            deviation("Restaurante", "2000", ExpenseNature.DISCRETIONARY),
            deviation("Lazer", "1000", ExpenseNature.DISCRETIONARY),
            deviation("Moradia", "5000", ExpenseNature.FIXED),
        )

        val plan = calculator.savingsPlan(decimal("1000"), deviations)

        assertEquals(decimal("900.00"), plan.plannedAmount)
        assertEquals(decimal("100.00"), plan.remainingAmount)
        assertEquals(listOf("Restaurante", "Lazer"), plan.suggestions.map { it.category })
        assertEquals(listOf(decimal("600.00"), decimal("300.00")), plan.suggestions.map { it.suggestedCut })
        assertTrue(plan.suggestions.all { it.suggestedCut <= it.availableExpense })
    }

    @Test
    fun `month projection requires five observed days`() {
        val unavailable = calculator.monthEndProjection(date("2026-08-04"), decimal("400"))
        val available = calculator.monthEndProjection(date("2026-08-10"), decimal("1000"))

        assertNull(unavailable.projectedExpenses)
        assertEquals(decimal("3100.00"), available.projectedExpenses)
        assertEquals(10, available.daysObserved)
        assertEquals(31, available.daysInPeriod)
    }

    @Test
    fun `fixed and discretionary ratios remain local and null without expenses`() {
        assertEquals(decimal("0.4000"), calculator.expenseRatio(decimal("400"), decimal("1000")))
        assertEquals(decimal("0.2500"), calculator.expenseRatio(decimal("250"), decimal("1000")))
        assertNull(calculator.expenseRatio(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    private fun recurring(
        key: String,
        description: String,
        dates: List<String>,
    ): List<RecurringExpenseOccurrence> = dates.map { occurredOn ->
        occurrence(key, description, occurredOn, "100")
    }

    private fun occurrence(
        key: String,
        description: String,
        occurredOn: String,
        amount: String,
    ) = RecurringExpenseOccurrence(key, description, "Serviços", decimal(amount), date(occurredOn))

    private fun metric(
        id: UUID,
        name: String,
        total: String,
        nature: ExpenseNature,
    ) = CategoryMetric(id, name, nature, decimal(total), 1)

    private fun deviation(
        name: String,
        current: String,
        nature: ExpenseNature,
    ) = BudgetDeviation(
        category = name,
        nature = nature,
        currentAmount = decimal(current).setScale(2),
        baselineAverage = BigDecimal.ZERO.setScale(2),
        absoluteDeviation = decimal(current).setScale(2),
        percentageDeviation = null,
        severity = DeviationSeverity.HIGH,
    )
}

private fun period(from: String, to: String) = InsightPeriod(date(from), date(to))

private fun date(value: String): LocalDate = LocalDate.parse(value)

private fun decimal(value: String): BigDecimal = value.toBigDecimal()
