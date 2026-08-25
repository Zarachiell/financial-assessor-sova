package com.sova.finance.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CashFlowTest {
    private val calculator = CashFlowOccurrenceCalculator()

    @Test
    fun `monthly occurrence respects start date and includes the target date`() {
        val entry = entry(
            recurrence = CashFlowRecurrence.MONTHLY,
            startDate = date("2026-01-15"),
            dayOfMonth = 10,
        )

        assertEquals(
            listOf(date("2026-02-10"), date("2026-03-10")),
            calculator.occurrences(entry, date("2026-01-01"), date("2026-03-10")).map { it.date },
        )
    }

    @Test
    fun `day 31 uses the last day in short and leap-year months`() {
        val entry = entry(
            recurrence = CashFlowRecurrence.MONTHLY,
            startDate = date("2027-01-01"),
            dayOfMonth = 31,
        )

        assertEquals(
            listOf(date("2027-02-28")),
            calculator.occurrences(entry, date("2027-02-01"), date("2027-02-28")).map { it.date },
        )
        assertEquals(
            listOf(date("2028-02-29")),
            calculator.occurrences(entry, date("2028-02-01"), date("2028-02-29")).map { it.date },
        )
        assertEquals(
            listOf(date("2027-04-30")),
            calculator.occurrences(entry, date("2027-04-01"), date("2027-04-30")).map { it.date },
        )
    }

    @Test
    fun `active period and deactivation bound generated occurrences`() {
        val entry = entry(
            recurrence = CashFlowRecurrence.MONTHLY,
            startDate = date("2026-01-01"),
            endDate = date("2026-03-15"),
            dayOfMonth = 20,
        )

        assertEquals(
            listOf(date("2026-01-20"), date("2026-02-20")),
            calculator.occurrences(entry, date("2026-01-20"), date("2026-04-20")).map { it.date },
        )
        assertTrue(
            calculator.occurrences(
                entry.copy(active = false),
                date("2026-01-01"),
                date("2026-12-31"),
            ).isEmpty(),
        )
    }

    @Test
    fun `one-time occurrence is calculated once without materialization`() {
        val entry = entry(
            recurrence = CashFlowRecurrence.ONCE,
            startDate = date("2026-09-15"),
        )

        assertEquals(
            listOf(date("2026-09-15")),
            calculator.occurrences(entry, date("2026-09-01"), date("2026-09-15")).map { it.date },
        )
        assertTrue(calculator.occurrences(entry, date("2026-09-16"), date("2026-10-01")).isEmpty())
    }

    @Test
    fun `projection combines current balance with deterministic expected totals`() {
        val income = entry(
            type = FinancialEntryType.INCOME,
            amount = "100.00",
            recurrence = CashFlowRecurrence.MONTHLY,
            startDate = date("2026-01-01"),
            dayOfMonth = 5,
        )
        val expense = entry(
            type = FinancialEntryType.EXPENSE,
            amount = "30.00",
            recurrence = CashFlowRecurrence.ONCE,
            startDate = date("2026-02-10"),
        )
        val ignored = expense.copy(id = UUID.randomUUID(), active = false, money = Money.of(BigDecimal("999.00")))

        val projection = CashFlowProjectionCalculator().calculate(
            currentBalance = BigDecimal("50.00"),
            entries = listOf(income, expense, ignored),
            fromDate = date("2026-01-01"),
            targetDate = date("2026-03-31"),
        )

        assertEquals(BigDecimal("50.00"), projection.currentBalance)
        assertEquals(BigDecimal("300.00"), projection.expectedIncome)
        assertEquals(BigDecimal("30.00"), projection.expectedExpenses)
        assertEquals(BigDecimal("320.00"), projection.projectedBalance)
        assertEquals(4, projection.occurrenceCount)
    }

    private fun entry(
        type: FinancialEntryType = FinancialEntryType.EXPENSE,
        amount: String = "10.00",
        recurrence: CashFlowRecurrence,
        startDate: LocalDate,
        endDate: LocalDate? = null,
        dayOfMonth: Int? = null,
    ): CashFlowEntry = CashFlowEntry(
        id = UUID.randomUUID(),
        householdId = UUID.randomUUID(),
        createdByUserId = UUID.randomUUID(),
        type = type,
        money = Money.of(BigDecimal(amount)),
        currency = Currency.BRL,
        description = FinancialDescription.of("Planejamento"),
        recurrence = recurrence,
        startDate = startDate,
        endDate = endDate,
        dayOfMonth = dayOfMonth,
        active = true,
        source = FinancialSource.DASHBOARD,
        sourceReferenceId = null,
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T12:00:00Z"),
    )

    private fun date(value: String): LocalDate = LocalDate.parse(value)
}
