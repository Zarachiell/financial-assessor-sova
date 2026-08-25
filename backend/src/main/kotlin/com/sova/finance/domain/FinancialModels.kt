package com.sova.finance.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class Currency {
    BRL,
}

@JvmInline
value class Money private constructor(val amount: BigDecimal) {
    companion object {
        fun of(amount: BigDecimal): Money {
            val normalized = try {
                amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
            } catch (cause: ArithmeticException) {
                throw IllegalArgumentException("Money must not contain fractions smaller than one cent", cause)
            }
            require(normalized > BigDecimal.ZERO) { "Money must be greater than zero" }
            require(normalized.precision() <= MONEY_PRECISION) { "Money exceeds NUMERIC(19, 2) precision" }
            return Money(normalized)
        }

        private const val MONEY_SCALE = 2
        private const val MONEY_PRECISION = 19
    }
}

@JvmInline
value class AccountName private constructor(val value: String) {
    companion object {
        fun of(value: String): AccountName = AccountName(requiredText(value, "Account name", MAX_NAME_LENGTH))
    }
}

@JvmInline
value class CategoryName private constructor(val value: String) {
    companion object {
        fun of(value: String): CategoryName = CategoryName(requiredText(value, "Category name", MAX_NAME_LENGTH))
    }
}

@JvmInline
value class FinancialDescription private constructor(val value: String) {
    companion object {
        fun of(value: String): FinancialDescription =
            FinancialDescription(requiredText(value, "Financial description", MAX_DESCRIPTION_LENGTH))
    }
}

@JvmInline
value class SourceReferenceId private constructor(val value: String) {
    companion object {
        fun of(value: String): SourceReferenceId =
            SourceReferenceId(requiredText(value, "Source reference", MAX_SOURCE_REFERENCE_LENGTH))
    }
}

enum class FinancialEntryType {
    INCOME,
    EXPENSE,
}

enum class ExpenseNature {
    FIXED,
    DISCRETIONARY,
    UNCLASSIFIED,
}

enum class AccountType {
    CHECKING,
    SAVINGS,
    CASH,
    CREDIT_CARD,
    INVESTMENT,
    OTHER,
}

enum class FinancialSource {
    WHATSAPP_TEXT,
    WHATSAPP_AUDIO,
    WHATSAPP_IMAGE,
    DASHBOARD,
    STATEMENT_IMPORT,
}

enum class CashFlowRecurrence {
    ONCE,
    MONTHLY,
}

data class FinancialContext(
    val householdId: UUID,
    val userId: UUID,
)

data class Account(
    val id: UUID,
    val householdId: UUID,
    val createdByUserId: UUID,
    val name: AccountName,
    val type: AccountType,
    val currency: Currency,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Category(
    val id: UUID,
    val householdId: UUID,
    val createdByUserId: UUID,
    val name: CategoryName,
    val type: FinancialEntryType,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expenseNature: ExpenseNature = ExpenseNature.UNCLASSIFIED,
)

data class Transaction(
    val id: UUID,
    val householdId: UUID,
    val createdByUserId: UUID,
    val accountId: UUID,
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency,
    val description: FinancialDescription,
    val categoryId: UUID,
    val occurredOn: LocalDate,
    val source: FinancialSource,
    val sourceReferenceId: SourceReferenceId?,
    val createdAt: Instant,
) {
    init {
        require(currency == Currency.BRL) { "Only BRL is supported" }
    }
}

data class CashFlowEntry(
    val id: UUID,
    val householdId: UUID,
    val createdByUserId: UUID,
    val type: FinancialEntryType,
    val money: Money,
    val currency: Currency,
    val description: FinancialDescription,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
    val recurrence: CashFlowRecurrence,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val dayOfMonth: Int? = null,
    val active: Boolean,
    val source: FinancialSource,
    val sourceReferenceId: SourceReferenceId?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(currency == Currency.BRL) { "Only BRL is supported" }
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Cash flow end date must not be before its start date"
        }
        when (recurrence) {
            CashFlowRecurrence.ONCE -> require(dayOfMonth == null) {
                "One-time cash flow entries must not define a day of month"
            }
            CashFlowRecurrence.MONTHLY -> require(dayOfMonth in 1..31) {
                "Monthly cash flow entries require a day of month between 1 and 31"
            }
        }
    }
}

private fun requiredText(value: String, field: String, maxLength: Int): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$field must not be blank" }
    require(normalized.length <= maxLength) { "$field must have at most $maxLength characters" }
    return normalized
}

private const val MAX_NAME_LENGTH = 100
private const val MAX_DESCRIPTION_LENGTH = 500
private const val MAX_SOURCE_REFERENCE_LENGTH = 200
