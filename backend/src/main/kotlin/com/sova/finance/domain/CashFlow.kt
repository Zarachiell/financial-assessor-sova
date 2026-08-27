package com.sova.finance.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class CashFlowOccurrence(
    val entryId: UUID,
    val date: LocalDate,
    val type: FinancialEntryType,
    val money: Money,
    val accountId: UUID?,
)

data class CashFlowOccurrenceKey(
    val entryId: UUID,
    val scheduledOn: LocalDate,
)

data class CashFlowProjection(
    val currentBalance: BigDecimal,
    val expectedIncome: BigDecimal,
    val expectedExpenses: BigDecimal,
    val projectedBalance: BigDecimal,
    val fromDate: LocalDate,
    val targetDate: LocalDate,
    val occurrenceCount: Int,
    val incomeOccurrenceCount: Int,
    val expenseOccurrenceCount: Int,
)

class CashFlowOccurrenceCalculator {
    fun occurrences(
        entry: CashFlowEntry,
        fromDate: LocalDate,
        targetDate: LocalDate,
    ): List<CashFlowOccurrence> {
        require(!fromDate.isAfter(targetDate)) { "Cash flow period start must not be after its target" }
        if (!entry.active) return emptyList()

        val effectiveFrom = maxOf(fromDate, entry.startDate)
        val effectiveTo = minOf(targetDate, entry.endDate ?: targetDate)
        if (effectiveFrom.isAfter(effectiveTo)) return emptyList()

        val dates = when (entry.recurrence) {
            CashFlowRecurrence.ONCE -> listOf(entry.startDate).filter { date ->
                !date.isBefore(effectiveFrom) && !date.isAfter(effectiveTo)
            }
            CashFlowRecurrence.MONTHLY -> monthlyDates(entry, effectiveFrom, effectiveTo)
        }
        return dates.map { date ->
            CashFlowOccurrence(entry.id, date, entry.type, entry.money, entry.accountId)
        }
    }

    private fun monthlyDates(
        entry: CashFlowEntry,
        effectiveFrom: LocalDate,
        effectiveTo: LocalDate,
    ): List<LocalDate> {
        val dayOfMonth = requireNotNull(entry.dayOfMonth)
        val firstMonth = YearMonth.from(effectiveFrom)
        val lastMonth = YearMonth.from(effectiveTo)
        return generateSequence(firstMonth) { month -> month.plusMonths(1) }
            .takeWhile { month -> month <= lastMonth }
            .map { month -> month.atDay(minOf(dayOfMonth, month.lengthOfMonth())) }
            .filter { date -> !date.isBefore(effectiveFrom) && !date.isAfter(effectiveTo) }
            .toList()
    }
}

class CashFlowProjectionCalculator(
    private val occurrenceCalculator: CashFlowOccurrenceCalculator = CashFlowOccurrenceCalculator(),
) {
    fun calculate(
        currentBalance: BigDecimal,
        entries: List<CashFlowEntry>,
        fromDate: LocalDate,
        targetDate: LocalDate,
        settledOccurrences: Set<CashFlowOccurrenceKey> = emptySet(),
    ): CashFlowProjection {
        val occurrences = entries.flatMap { entry ->
            occurrenceCalculator.occurrences(entry, fromDate, targetDate)
        }.filterNot { occurrence ->
            CashFlowOccurrenceKey(occurrence.entryId, occurrence.date) in settledOccurrences
        }
        val expectedIncome = occurrences
            .filter { it.type == FinancialEntryType.INCOME }
            .fold(BigDecimal.ZERO) { total, occurrence -> total + occurrence.money.amount }
        val expectedExpenses = occurrences
            .filter { it.type == FinancialEntryType.EXPENSE }
            .fold(BigDecimal.ZERO) { total, occurrence -> total + occurrence.money.amount }
        val balance = currentBalance.moneyScale()
        val income = expectedIncome.moneyScale()
        val expenses = expectedExpenses.moneyScale()
        return CashFlowProjection(
            currentBalance = balance,
            expectedIncome = income,
            expectedExpenses = expenses,
            projectedBalance = (balance + income - expenses).moneyScale(),
            fromDate = fromDate,
            targetDate = targetDate,
            occurrenceCount = occurrences.size,
            incomeOccurrenceCount = occurrences.count { it.type == FinancialEntryType.INCOME },
            expenseOccurrenceCount = occurrences.count { it.type == FinancialEntryType.EXPENSE },
        )
    }
}

private fun BigDecimal.moneyScale(): BigDecimal = setScale(2, RoundingMode.UNNECESSARY)
