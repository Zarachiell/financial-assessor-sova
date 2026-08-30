package com.sova.finance.application

import com.sova.finance.domain.AccountName
import com.sova.finance.domain.AccountType
import com.sova.finance.domain.CategoryName
import com.sova.finance.domain.ExpenseNature
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialEntryType

object DefaultFinancialCatalog {
    const val ACCOUNT_NAME = "Conta principal"

    val expenseCategories = linkedMapOf(
        "Alimentação" to ExpenseNature.UNCLASSIFIED,
        "Restaurante" to ExpenseNature.DISCRETIONARY,
        "Moradia" to ExpenseNature.FIXED,
        "Transporte" to ExpenseNature.UNCLASSIFIED,
        "Saúde" to ExpenseNature.FIXED,
        "Lazer" to ExpenseNature.DISCRETIONARY,
        "Compras" to ExpenseNature.DISCRETIONARY,
        "Outros" to ExpenseNature.UNCLASSIFIED,
    )

    val incomeCategories = listOf(
        "Salário",
        "Bônus",
        "VR/VA",
        "Reembolso",
        "Renda extra",
        "Outros",
    )
}

class DefaultFinancialCatalogProvisioner(
    private val service: FinancialApplicationService,
) {
    suspend fun provision(context: FinancialContext) {
        val accounts = service.listAccounts(context).requiredValue()
        if (accounts.none { it.active }) {
            service.createAccount(
                context,
                CreateAccountCommand(
                    name = AccountName.of(DefaultFinancialCatalog.ACCOUNT_NAME),
                    type = AccountType.CHECKING,
                ),
            ).requireCreatedOrDuplicate(FinancialRejection.DUPLICATE_ACCOUNT_NAME)
            check(service.listAccounts(context).requiredValue().any { it.active }) {
                "Default financial catalog requires at least one active account"
            }
        }

        val categories = service.listCategories(context).requiredValue()
        DefaultFinancialCatalog.expenseCategories.forEach { (name, nature) ->
            if (categories.none { it.type == FinancialEntryType.EXPENSE && it.name.value == name }) {
                createCategory(context, name, FinancialEntryType.EXPENSE, nature)
            }
        }
        requireActiveCategory(context, FinancialEntryType.EXPENSE)
        DefaultFinancialCatalog.incomeCategories.forEach { name ->
            if (categories.none { it.type == FinancialEntryType.INCOME && it.name.value == name }) {
                createCategory(context, name, FinancialEntryType.INCOME)
            }
        }
        requireActiveCategory(context, FinancialEntryType.INCOME)
    }

    private suspend fun createCategory(
        context: FinancialContext,
        name: String,
        type: FinancialEntryType,
        expenseNature: ExpenseNature = ExpenseNature.UNCLASSIFIED,
    ) {
        service.createCategory(
            context,
            CreateCategoryCommand(CategoryName.of(name), type, expenseNature),
        ).requireCreatedOrDuplicate(FinancialRejection.DUPLICATE_CATEGORY_NAME)
    }

    private suspend fun requireActiveCategory(context: FinancialContext, type: FinancialEntryType) {
        check(service.listCategories(context).requiredValue().any { it.active && it.type == type }) {
            "Default financial catalog requires an active ${type.name.lowercase()} category"
        }
    }
}

private fun <T> FinancialResult<T>.requiredValue(): T = when (this) {
    is FinancialResult.Success -> value
    is FinancialResult.Rejected -> error("Default financial catalog provisioning was rejected: ${reason.name}")
}

private fun FinancialResult<*>.requireCreatedOrDuplicate(acceptedDuplicate: FinancialRejection) {
    if (this is FinancialResult.Rejected && reason != acceptedDuplicate) {
        error("Default financial catalog provisioning was rejected: ${reason.name}")
    }
}
