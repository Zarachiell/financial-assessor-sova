package com.sova.ai.application

import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.GetCashFlowProjectionQuery
import com.sova.finance.application.TransactionFilters
import com.sova.finance.domain.Account
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.Category
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.domain.Transaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

fun financialLocalTools(service: FinancialApplicationService): List<LocalTool> = listOf(
    CreateTransactionTool(service),
    GetCurrentBalanceTool(service),
    GetExpensesTool(service),
    GetIncomeTool(service),
    GetTransactionsTool(service),
    GetCategorySummaryTool(service),
    CreateCashFlowEntryTool(service),
    GetCashFlowProjectionTool(service),
)

class CreateTransactionTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "create_transaction",
        description = "Registra localmente uma única receita ou despesa confirmada pelo usuário.",
        parameters = AiToolParameters(
            properties = mapOf(
                "type" to stringParameter("Tipo da transação.", "INCOME", "EXPENSE"),
                "amount" to numberParameter("Valor positivo em BRL, sem símbolo de moeda."),
                "description" to stringParameter("Descrição curta da transação."),
                "category" to stringParameter("Nome da categoria financeira."),
                "occurredOn" to stringParameter("Data civil no formato YYYY-MM-DD."),
                "account" to stringParameter("Nome da conta; omita para usar a conta padrão."),
            ),
            required = setOf("type", "amount", "description", "category", "occurredOn"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val type = arguments.requiredEntryType("type")
        val account = resolveAccount(context, arguments.optionalString("account"))
        val category = resolveCategory(context, arguments.requiredString("category"), type)
        val amount = Money.of(arguments.requiredDecimal("amount"))
        val description = FinancialDescription.of(arguments.requiredString("description"))
        val occurredOn = arguments.requiredDate("occurredOn")
        val creation = service.createTransaction(
            context.financialContext,
            CreateTransactionCommand(
                accountId = account.id,
                type = type,
                money = amount,
                description = description,
                categoryId = category.id,
                occurredOn = occurredOn,
                source = FinancialSource.WHATSAPP_TEXT,
                sourceReferenceId = SourceReferenceId.of(context.inboundMessageSid),
            ),
        ).requiredValue()

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("type", JsonPrimitive(creation.value.type.name))
            put("amount", moneyJson(creation.value.money.amount))
            put("currency", JsonPrimitive(creation.value.currency.name))
            put("description", JsonPrimitive(creation.value.description.value))
            put("category", JsonPrimitive(category.name.value))
            put("account", JsonPrimitive(account.name.value))
            put("occurredOn", JsonPrimitive(creation.value.occurredOn.toString()))
        }
    }
}

class GetCurrentBalanceTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "get_current_balance",
        description = "Calcula localmente o saldo registrado: receitas menos despesas.",
        parameters = AiToolParameters(
            properties = mapOf(
                "account" to stringParameter("Nome opcional da conta."),
            ),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val requestedAccount = arguments.optionalString("account")
        val account = requestedAccount?.let { resolveAccount(context, it) }
        val transactions = transactions(
            context,
            TransactionFilters(accountId = account?.id),
        )
        val balance = transactions.fold(BigDecimal.ZERO) { total, transaction ->
            when (transaction.type) {
                FinancialEntryType.INCOME -> total + transaction.money.amount
                FinancialEntryType.EXPENSE -> total - transaction.money.amount
            }
        }
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("balance", moneyJson(balance))
            put("currency", JsonPrimitive("BRL"))
            put("transactionCount", JsonPrimitive(transactions.size))
            account?.let { put("account", JsonPrimitive(it.name.value)) }
        }
    }
}

class GetExpensesTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = periodTotalDefinition(
        name = "get_expenses",
        description = "Consulta e soma localmente despesas registradas em um período inclusivo.",
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject =
        periodTotal(context, arguments, FinancialEntryType.EXPENSE, "total")
}

class GetIncomeTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = periodTotalDefinition(
        name = "get_income",
        description = "Consulta e soma localmente receitas registradas em um período inclusivo.",
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject =
        periodTotal(context, arguments, FinancialEntryType.INCOME, "total")
}

class GetTransactionsTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "get_transactions",
        description = "Lista localmente transações registradas usando filtros opcionais.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to stringParameter("Data inicial inclusiva YYYY-MM-DD."),
                "to" to stringParameter("Data final inclusiva YYYY-MM-DD."),
                "type" to stringParameter("Tipo opcional.", "INCOME", "EXPENSE"),
                "category" to stringParameter("Nome opcional da categoria."),
                "account" to stringParameter("Nome opcional da conta."),
                "limit" to integerParameter("Máximo de itens, entre 1 e 50."),
            ),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val from = arguments.optionalDate("from")
        val to = arguments.optionalDate("to")
        validatePeriod(from, to)
        val type = arguments.optionalEntryType("type")
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
        val limit = arguments["limit"]?.jsonPrimitive?.int ?: DEFAULT_TRANSACTION_LIMIT
        require(limit in 1..MAX_TRANSACTION_LIMIT) { "Transaction limit must be between 1 and 50" }
        val transactions = transactions(
            context,
            TransactionFilters(from, to, type, account?.id, category?.id),
        ).take(limit)
        val accounts = accounts(context).associateBy(Account::id)
        val categories = categories(context).associateBy(Category::id)

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(transactions.size))
            put(
                "transactions",
                buildJsonArray {
                    transactions.forEach { transaction ->
                        add(transaction.toMinimalJson(accounts, categories))
                    }
                },
            )
        }
    }

    private companion object {
        const val DEFAULT_TRANSACTION_LIMIT = 20
        const val MAX_TRANSACTION_LIMIT = 50
    }
}

class GetCategorySummaryTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "get_category_summary",
        description = "Agrupa e soma localmente transações por categoria em um período inclusivo.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to stringParameter("Data inicial inclusiva YYYY-MM-DD."),
                "to" to stringParameter("Data final inclusiva YYYY-MM-DD."),
            ),
            required = setOf("from", "to"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val from = arguments.requiredDate("from")
        val to = arguments.requiredDate("to")
        validatePeriod(from, to)
        val categories = categories(context).associateBy(Category::id)
        val grouped = transactions(context, TransactionFilters(from, to))
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, items) ->
                categories[categoryId]?.let { category -> CategoryTotal(category, items) }
            }
            .sortedWith(compareBy(CategoryTotal::typeName, CategoryTotal::categoryName))

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("from", JsonPrimitive(from.toString()))
            put("to", JsonPrimitive(to.toString()))
            put("currency", JsonPrimitive("BRL"))
            put(
                "categories",
                buildJsonArray {
                    grouped.forEach { total ->
                        add(
                            buildJsonObject {
                                put("category", JsonPrimitive(total.category.name.value))
                                put("type", JsonPrimitive(total.category.type.name))
                                put("total", moneyJson(total.total))
                                put("count", JsonPrimitive(total.transactions.size))
                            },
                        )
                    }
                },
            )
        }
    }
}

class CreateCashFlowEntryTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "create_cash_flow_entry",
        description = "Registra localmente uma receita ou despesa futura, pontual ou mensal.",
        parameters = AiToolParameters(
            properties = mapOf(
                "type" to stringParameter("Tipo da previsão.", "INCOME", "EXPENSE"),
                "amount" to numberParameter("Valor positivo em BRL, sem símbolo de moeda."),
                "description" to stringParameter("Descrição curta da previsão."),
                "recurrence" to stringParameter("Recorrência da previsão.", "ONCE", "MONTHLY"),
                "startDate" to stringParameter("Data inicial ou pontual no formato YYYY-MM-DD."),
                "endDate" to stringParameter("Data final inclusiva YYYY-MM-DD; omita se não houver."),
                "dayOfMonth" to integerParameter("Dia 1..31 obrigatório para MONTHLY; omita para ONCE."),
                "category" to stringParameter("Nome opcional da categoria financeira."),
                "account" to stringParameter("Nome opcional da conta financeira."),
            ),
            required = setOf("type", "amount", "description", "recurrence", "startDate"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val type = arguments.requiredEntryType("type")
        val recurrence = CashFlowRecurrence.valueOf(arguments.requiredString("recurrence"))
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
        val creation = service.createCashFlowEntry(
            context.financialContext,
            CreateCashFlowEntryCommand(
                type = type,
                money = Money.of(arguments.requiredDecimal("amount")),
                description = FinancialDescription.of(arguments.requiredString("description")),
                accountId = account?.id,
                categoryId = category?.id,
                recurrence = recurrence,
                startDate = arguments.requiredDate("startDate"),
                endDate = arguments.optionalDate("endDate"),
                dayOfMonth = arguments.optionalInt("dayOfMonth"),
                source = FinancialSource.WHATSAPP_TEXT,
                sourceReferenceId = SourceReferenceId.of(context.inboundMessageSid),
            ),
        ).requiredValue()

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("type", JsonPrimitive(creation.value.type.name))
            put("amount", moneyJson(creation.value.money.amount))
            put("currency", JsonPrimitive(creation.value.currency.name))
            put("description", JsonPrimitive(creation.value.description.value))
            put("recurrence", JsonPrimitive(creation.value.recurrence.name))
            put("startDate", JsonPrimitive(creation.value.startDate.toString()))
            creation.value.endDate?.let { put("endDate", JsonPrimitive(it.toString())) }
            creation.value.dayOfMonth?.let { put("dayOfMonth", JsonPrimitive(it)) }
            category?.let { put("category", JsonPrimitive(it.name.value)) }
            account?.let { put("account", JsonPrimitive(it.name.value)) }
        }
    }
}

class GetCashFlowProjectionTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "get_cash_flow_projection",
        description = "Calcula localmente o saldo projetado até uma data futura inclusiva.",
        parameters = AiToolParameters(
            properties = mapOf(
                "targetDate" to stringParameter("Data alvo inclusiva no formato YYYY-MM-DD."),
                "account" to stringParameter("Nome opcional da conta financeira."),
            ),
            required = setOf("targetDate"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val projection = service.getCashFlowProjection(
            context.financialContext,
            GetCashFlowProjectionQuery(
                targetDate = arguments.requiredDate("targetDate"),
                accountId = account?.id,
            ),
        ).requiredValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("currentBalance", moneyJson(projection.currentBalance))
            put("expectedIncome", moneyJson(projection.expectedIncome))
            put("expectedExpenses", moneyJson(projection.expectedExpenses))
            put("projectedBalance", moneyJson(projection.projectedBalance))
            put("currency", JsonPrimitive("BRL"))
            put("fromDate", JsonPrimitive(projection.fromDate.toString()))
            put("targetDate", JsonPrimitive(projection.targetDate.toString()))
            put("occurrenceCount", JsonPrimitive(projection.occurrenceCount))
            account?.let { put("account", JsonPrimitive(it.name.value)) }
        }
    }
}

abstract class FinancialLocalTool(
    protected val service: FinancialApplicationService,
) : LocalTool {
    protected suspend fun accounts(context: ToolExecutionContext): List<Account> =
        service.listAccounts(context.financialContext).requiredValue()

    protected suspend fun categories(context: ToolExecutionContext): List<Category> =
        service.listCategories(context.financialContext).requiredValue()

    protected suspend fun transactions(
        context: ToolExecutionContext,
        filters: TransactionFilters,
    ): List<Transaction> = service.listTransactions(context.financialContext, filters).requiredValue()

    protected suspend fun resolveAccount(context: ToolExecutionContext, requestedName: String?): Account {
        val active = accounts(context).filter(Account::active)
        if (requestedName == null) {
            if (active.size == 1) return active.single()
            active.singleOrNull { it.name.value.normalizedName() == DEFAULT_ACCOUNT_NAME.normalizedName() }
                ?.let { return it }
            throw ToolRejectedException("account_ambiguous", available("availableAccounts", active.map { it.name.value }))
        }
        val matching = active.filter { it.name.value.normalizedName() == requestedName.normalizedName() }
        if (matching.size == 1) return matching.single()
        val code = if (matching.isEmpty()) "account_not_found" else "account_ambiguous"
        throw ToolRejectedException(code, available("availableAccounts", active.map { it.name.value }))
    }

    protected suspend fun resolveCategory(
        context: ToolExecutionContext,
        requestedName: String,
        type: FinancialEntryType?,
    ): Category {
        val active = categories(context).filter { category -> category.active && (type == null || category.type == type) }
        val matching = active.filter { it.name.value.normalizedName() == requestedName.normalizedName() }
        if (matching.size == 1) return matching.single()
        val code = if (matching.isEmpty()) "category_not_found" else "category_ambiguous"
        throw ToolRejectedException(code, available("availableCategories", active.map { it.name.value }.distinct()))
    }

    protected suspend fun periodTotal(
        context: ToolExecutionContext,
        arguments: JsonObject,
        type: FinancialEntryType,
        totalField: String,
    ): JsonObject {
        val from = arguments.requiredDate("from")
        val to = arguments.requiredDate("to")
        validatePeriod(from, to)
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
        val transactions = transactions(
            context,
            TransactionFilters(from, to, type, account?.id, category?.id),
        )
        val total = transactions.fold(BigDecimal.ZERO) { sum, transaction -> sum + transaction.money.amount }
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put(totalField, moneyJson(total))
            put("currency", JsonPrimitive("BRL"))
            put("count", JsonPrimitive(transactions.size))
            put("from", JsonPrimitive(from.toString()))
            put("to", JsonPrimitive(to.toString()))
            category?.let { put("category", JsonPrimitive(it.name.value)) }
            account?.let { put("account", JsonPrimitive(it.name.value)) }
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_NAME = "Conta principal"
    }
}

private data class CategoryTotal(
    val category: Category,
    val transactions: List<Transaction>,
) {
    val typeName: String = category.type.name
    val categoryName: String = category.name.value
    val total: BigDecimal = transactions.fold(BigDecimal.ZERO) { sum, transaction -> sum + transaction.money.amount }
}

private fun periodTotalDefinition(name: String, description: String): AiToolDefinition = AiToolDefinition(
    name = name,
    description = description,
    parameters = AiToolParameters(
        properties = mapOf(
            "from" to stringParameter("Data inicial inclusiva YYYY-MM-DD."),
            "to" to stringParameter("Data final inclusiva YYYY-MM-DD."),
            "category" to stringParameter("Nome opcional da categoria."),
            "account" to stringParameter("Nome opcional da conta."),
        ),
        required = setOf("from", "to"),
    ),
)

private fun stringParameter(description: String, vararg enumValues: String) = AiToolParameter(
    type = AiJsonType.STRING,
    description = description,
    enumValues = enumValues.toList(),
)

private fun numberParameter(description: String) = AiToolParameter(AiJsonType.NUMBER, description)

private fun integerParameter(description: String) = AiToolParameter(AiJsonType.INTEGER, description)

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(optionalString(name)) { "Missing argument: $name" }

private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.requiredDecimal(name: String): BigDecimal =
    this[name]?.jsonPrimitive?.content?.toBigDecimalOrNull()
        ?: throw IllegalArgumentException("Argument $name must be a decimal")

private fun JsonObject.requiredDate(name: String): LocalDate =
    LocalDate.parse(requiredString(name))

private fun JsonObject.optionalDate(name: String): LocalDate? = optionalString(name)?.let(LocalDate::parse)

private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.requiredEntryType(name: String): FinancialEntryType =
    FinancialEntryType.valueOf(requiredString(name))

private fun JsonObject.optionalEntryType(name: String): FinancialEntryType? =
    optionalString(name)?.let(FinancialEntryType::valueOf)

private fun validatePeriod(from: LocalDate?, to: LocalDate?) {
    require(from == null || to == null || !from.isAfter(to)) { "Period start must not be after its end" }
}

private fun String.normalizedName(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")
    .lowercase(Locale.ROOT)

private fun available(field: String, values: List<String>): JsonObject = buildJsonObject {
    put(field, JsonArray(values.sorted().map(::JsonPrimitive)))
}

private fun moneyJson(value: BigDecimal): JsonPrimitive =
    JsonPrimitive(value.setScale(2, RoundingMode.UNNECESSARY))

private fun Transaction.toMinimalJson(
    accounts: Map<java.util.UUID, Account>,
    categories: Map<java.util.UUID, Category>,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type.name))
    put("amount", moneyJson(money.amount))
    put("currency", JsonPrimitive(currency.name))
    put("description", JsonPrimitive(description.value))
    put("occurredOn", JsonPrimitive(occurredOn.toString()))
    accounts[accountId]?.let { put("account", JsonPrimitive(it.name.value)) }
    categories[categoryId]?.let { put("category", JsonPrimitive(it.name.value)) }
}

private fun <T> FinancialResult<T>.requiredValue(): T = when (this) {
    is FinancialResult.Success -> value
    is FinancialResult.Rejected -> throw ToolRejectedException(
        "financial_rejection",
        buildJsonObject { put("reason", JsonPrimitive(reason.name.lowercase())) },
    )
}

private val COMBINING_MARKS = Regex("\\p{M}+")
