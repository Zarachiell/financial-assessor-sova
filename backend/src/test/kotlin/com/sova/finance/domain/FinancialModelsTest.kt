package com.sova.finance.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FinancialModelsTest {
    @Test
    fun `money normalizes positive BRL values to two decimal places`() {
        assertEquals(BigDecimal("1.00"), Money.of(BigDecimal("1")).amount)
        assertEquals(BigDecimal("19.90"), Money.of(BigDecimal("19.9")).amount)
        assertEquals(BigDecimal("123.45"), Money.of(BigDecimal("123.45")).amount)
    }

    @Test
    fun `money rejects invalid monetary values without rounding`() {
        listOf(
            BigDecimal.ZERO,
            BigDecimal("-0.01"),
            BigDecimal("1.001"),
            BigDecimal("100000000000000000.00"),
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value.toPlainString()) {
                Money.of(value)
            }
        }
    }

    @Test
    fun `financial text value objects trim valid content and reject invalid content`() {
        assertEquals("Conta conjunta", AccountName.of("  Conta conjunta  ").value)
        assertEquals("Mercado", CategoryName.of(" Mercado ").value)
        assertEquals("Compra do mês", FinancialDescription.of(" Compra do mês ").value)
        assertEquals("SM-123", SourceReferenceId.of(" SM-123 ").value)

        assertFailsWith<IllegalArgumentException> { AccountName.of(" ") }
        assertFailsWith<IllegalArgumentException> { CategoryName.of("a".repeat(101)) }
        assertFailsWith<IllegalArgumentException> { FinancialDescription.of("a".repeat(501)) }
        assertFailsWith<IllegalArgumentException> { SourceReferenceId.of("a".repeat(201)) }
    }

    @Test
    fun `cash flow entry is punctual and preserves civil and technical dates`() {
        val householdId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-08-24T20:00:00Z")
        val startDate = LocalDate.parse("2026-09-01")
        val entry = CashFlowEntry(
            id = UUID.randomUUID(),
            householdId = householdId,
            createdByUserId = userId,
            type = FinancialEntryType.EXPENSE,
            money = Money.of(BigDecimal("200.00")),
            currency = Currency.BRL,
            description = FinancialDescription.of("Seguro"),
            categoryId = UUID.randomUUID(),
            recurrence = CashFlowRecurrence.ONCE,
            startDate = startDate,
            endDate = null,
            active = true,
            source = FinancialSource.DASHBOARD,
            sourceReferenceId = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        assertEquals(householdId, entry.householdId)
        assertEquals(userId, entry.createdByUserId)
        assertEquals(startDate, entry.startDate)
        assertEquals(createdAt, entry.createdAt)
        assertEquals(CashFlowRecurrence.ONCE, entry.recurrence)
        assertNull(entry.endDate)

        assertFailsWith<IllegalArgumentException> {
            entry.copy(endDate = startDate)
        }
    }
}
