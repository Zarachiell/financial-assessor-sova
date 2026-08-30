package com.sova.ai.application

import com.sova.finance.application.CreateCashFlowEntryCommand
import com.sova.finance.application.CreateTransactionCommand
import com.sova.finance.application.CashFlowOccurrenceStatus
import com.sova.finance.application.FinancialApplicationService
import com.sova.finance.application.FinancialResult
import com.sova.finance.application.GetCashFlowOccurrencesQuery
import com.sova.finance.application.GetCashFlowProjectionQuery
import com.sova.finance.application.SettleCashFlowOccurrenceCommand
import com.sova.finance.application.TransactionFilters
import com.sova.finance.application.UpdateCashFlowEntryCommand
import com.sova.finance.application.UpdateTransactionCommand
import com.sova.finance.domain.Account
import com.sova.finance.domain.CashFlowEntry
import com.sova.finance.domain.CashFlowRecurrence
import com.sova.finance.domain.Category
import com.sova.finance.domain.FinancialDescription
import com.sova.finance.domain.FinancialEntryType
import com.sova.finance.domain.FinancialSource
import com.sova.finance.domain.Money
import com.sova.finance.domain.SourceReferenceId
import com.sova.finance.domain.Transaction
import com.sova.finance.insights.application.FinancialInsightsApplicationService
import com.sova.image.application.ImageCandidateService
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
import java.time.Clock
import java.util.Locale
import java.util.UUID

fun financialLocalTools(
    service: FinancialApplicationService,
    insightsService: FinancialInsightsApplicationService? = null,
    imageCandidateService: ImageCandidateService? = null,
): List<LocalTool> = listOf(
    CreateTransactionTool(service, imageCandidateService),
    UpdateTransactionTool(service),
    DeleteTransactionTool(service),
    GetCurrentBalanceTool(service),
    GetExpensesTool(service),
    GetIncomeTool(service),
    GetTransactionsTool(service),
    GetCategorySummaryTool(service),
    CreateCashFlowEntryTool(service, imageCandidateService),
    UpdateCashFlowEntryTool(service),
    DeleteCashFlowEntryTool(service),
    GetCashFlowEntriesTool(service),
    SettleCashFlowOccurrenceTool(service),
    GetCashFlowProjectionTool(service),
) + imageCandidateService?.let { listOf(GetImageCandidatesTool(it)) }.orEmpty() +
    insightsService?.let { financialInsightTools(service, it) }.orEmpty()

class CreateTransactionTool(
    service: FinancialApplicationService,
    private val imageCandidateService: ImageCandidateService? = null,
    private val clock: Clock = Clock.systemUTC(),
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "create_transaction",
        description = "Registra localmente uma receita ou despesa confirmada; pode ser chamada várias vezes na mesma mensagem.",
        parameters = AiToolParameters(
            properties = mapOf(
                "type" to stringParameter("Tipo da transação.", "INCOME", "EXPENSE"),
                "amount" to numberParameter("Valor positivo em BRL, sem símbolo de moeda."),
                "description" to stringParameter("Descrição curta da transação."),
                "category" to stringParameter("Nome da categoria financeira."),
                "occurredOn" to stringParameter("Data civil no formato YYYY-MM-DD."),
                "account" to stringParameter("Nome da conta; omita para usar a conta padrão."),
                "itemIndex" to integerParameter(
                    "Posição 1..8 obrigatória quando a mensagem contiver mais de um lançamento; omita para apenas um.",
                ),
                "imageAttachmentId" to stringParameter(
                    "Identificador retornado pela análise de imagem; use apenas para registrar esse candidato confirmado.",
                ),
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
        val itemIndex = arguments.optionalInt("itemIndex")?.also {
            require(it in 1..MAX_TRANSACTION_ITEMS) { "itemIndex must be between 1 and $MAX_TRANSACTION_ITEMS" }
        }
        val imageAttachmentId = arguments.optionalString("imageAttachmentId")?.let(UUID::fromString)
            ?: context.attachmentId
        if (context.attachmentId != null && imageAttachmentId != context.attachmentId) {
            throw ToolRejectedException("image_attachment_mismatch")
        }
        if (imageAttachmentId != null && imageCandidateService?.find(context.financialContext, imageAttachmentId) == null) {
            throw ToolRejectedException("image_candidate_not_found")
        }
        val sourceReference = if (imageAttachmentId != null) {
            "attachment:$imageAttachmentId"
        } else if (itemIndex == null) {
            context.inboundMessageSid
        } else {
            "${context.inboundMessageSid}:$itemIndex"
        }
        val creation = service.createTransaction(
            context.financialContext,
            CreateTransactionCommand(
                accountId = account.id,
                type = type,
                money = amount,
                description = description,
                categoryId = category.id,
                occurredOn = occurredOn,
                source = if (imageAttachmentId == null) context.source else FinancialSource.WHATSAPP_IMAGE,
                sourceReferenceId = SourceReferenceId.of(sourceReference),
            ),
        ).requiredValue()
        imageAttachmentId?.let { imageCandidateService?.markConsumed(context.financialContext, it, clock.instant()) }

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("transactionId", JsonPrimitive(creation.value.id.toString()))
            put("type", JsonPrimitive(creation.value.type.name))
            put("amount", moneyJson(creation.value.money.amount))
            put("currency", JsonPrimitive(creation.value.currency.name))
            put("description", JsonPrimitive(creation.value.description.value))
            put("category", JsonPrimitive(category.name.value))
            put("account", JsonPrimitive(account.name.value))
            put("occurredOn", JsonPrimitive(creation.value.occurredOn.toString()))
        }
    }

    private companion object {
        const val MAX_TRANSACTION_ITEMS = 8
    }
}

class UpdateTransactionTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "update_transaction",
        description = "Corrige uma transação existente identificada por um transactionId retornado pelas tools de consulta.",
        parameters = AiToolParameters(
            properties = mapOf(
                "transactionId" to stringParameter("Identificador exato retornado por get_transactions."),
                "type" to stringParameter("Novo tipo, se precisar corrigir.", "INCOME", "EXPENSE"),
                "amount" to numberParameter("Novo valor positivo em BRL."),
                "description" to stringParameter("Nova descrição."),
                "category" to stringParameter("Nova categoria; obrigatória ao trocar o tipo."),
                "occurredOn" to stringParameter("Nova data civil YYYY-MM-DD."),
                "account" to stringParameter("Nova conta."),
            ),
            required = setOf("transactionId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        require(arguments.keys.any { it != "transactionId" }) { "At least one transaction field must be changed" }
        val transactionId = arguments.requiredUuid("transactionId")
        val current = service.findTransaction(context.financialContext, transactionId).requiredValue()
        val type = arguments.optionalEntryType("type") ?: current.type
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
            ?: accountById(context, current.accountId)
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
            ?: if (type == current.type) {
                categoryById(context, current.categoryId)
            } else {
                throw ToolRejectedException("category_required_for_type_change")
            }
        val updated = service.updateTransaction(
            context.financialContext,
            transactionId,
            UpdateTransactionCommand(
                accountId = account.id,
                type = type,
                money = arguments.optionalDecimal("amount")?.let(Money::of) ?: current.money,
                description = arguments.optionalString("description")?.let(FinancialDescription::of)
                    ?: current.description,
                categoryId = category.id,
                occurredOn = arguments.optionalDate("occurredOn") ?: current.occurredOn,
            ),
        ).requiredValue()
        return updated.toToolJson(account, category, operation = "updated")
    }
}

class DeleteTransactionTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "delete_transaction",
        description = "Exclui logicamente uma transação cadastrada por engano, preservando autoria e auditoria.",
        parameters = AiToolParameters(
            properties = mapOf(
                "transactionId" to stringParameter("Identificador exato retornado por get_transactions."),
            ),
            required = setOf("transactionId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val deleted = service.deleteTransaction(
            context.financialContext,
            arguments.requiredUuid("transactionId"),
        ).requiredValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("deleted", JsonPrimitive(true))
            put("transactionId", JsonPrimitive(deleted.id.toString()))
            put("description", JsonPrimitive(deleted.description.value))
            put("amount", moneyJson(deleted.money.amount))
            put("currency", JsonPrimitive(deleted.currency.name))
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
    private val imageCandidateService: ImageCandidateService? = null,
    private val clock: Clock = Clock.systemUTC(),
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
                "itemIndex" to integerParameter(
                    "Posição 1..8 obrigatória quando a mensagem contiver mais de uma previsão; omita para apenas uma.",
                ),
                "imageAttachmentId" to stringParameter(
                    "Identificador retornado pela análise de imagem; use apenas para registrar esse candidato confirmado.",
                ),
            ),
            required = setOf("type", "amount", "description", "recurrence", "startDate"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val type = arguments.requiredEntryType("type")
        val recurrence = CashFlowRecurrence.valueOf(arguments.requiredString("recurrence"))
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
        val itemIndex = arguments.optionalInt("itemIndex")?.also {
            require(it in 1..MAX_CASH_FLOW_ITEMS) { "itemIndex must be between 1 and $MAX_CASH_FLOW_ITEMS" }
        }
        val imageAttachmentId = arguments.optionalString("imageAttachmentId")?.let(UUID::fromString)
            ?: context.attachmentId
        if (context.attachmentId != null && imageAttachmentId != context.attachmentId) {
            throw ToolRejectedException("image_attachment_mismatch")
        }
        if (imageAttachmentId != null && imageCandidateService?.find(context.financialContext, imageAttachmentId) == null) {
            throw ToolRejectedException("image_candidate_not_found")
        }
        val sourceReference = if (imageAttachmentId != null) {
            "attachment:$imageAttachmentId"
        } else if (itemIndex == null) {
            context.inboundMessageSid
        } else {
            "${context.inboundMessageSid}:$itemIndex"
        }
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
                source = if (imageAttachmentId == null) context.source else FinancialSource.WHATSAPP_IMAGE,
                sourceReferenceId = SourceReferenceId.of(sourceReference),
            ),
        ).requiredValue()
        imageAttachmentId?.let { imageCandidateService?.markConsumed(context.financialContext, it, clock.instant()) }

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("cashFlowEntryId", JsonPrimitive(creation.value.id.toString()))
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

    private companion object {
        const val MAX_CASH_FLOW_ITEMS = 8
    }
}

class UpdateCashFlowEntryTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "update_cash_flow_entry",
        description = "Corrige uma conta, receita ou despesa futura ainda sem pagamentos vinculados.",
        parameters = AiToolParameters(
            properties = mapOf(
                "cashFlowEntryId" to stringParameter("Identificador exato retornado por get_cash_flow_entries."),
                "type" to stringParameter("Novo tipo.", "INCOME", "EXPENSE"),
                "amount" to numberParameter("Novo valor positivo em BRL."),
                "description" to stringParameter("Nova descrição."),
                "recurrence" to stringParameter("Nova recorrência.", "ONCE", "MONTHLY"),
                "startDate" to stringParameter("Nova data inicial YYYY-MM-DD."),
                "endDate" to stringParameter("Nova data final inclusiva YYYY-MM-DD."),
                "dayOfMonth" to integerParameter("Novo dia 1..31 para MONTHLY."),
                "category" to stringParameter("Nova categoria; obrigatória ao trocar o tipo."),
                "account" to stringParameter("Nova conta."),
            ),
            required = setOf("cashFlowEntryId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        require(arguments.keys.any { it != "cashFlowEntryId" }) { "At least one cash flow field must be changed" }
        val entryId = arguments.requiredUuid("cashFlowEntryId")
        val current = service.findCashFlowEntry(context.financialContext, entryId).requiredValue()
        val type = arguments.optionalEntryType("type") ?: current.type
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
            ?: current.accountId?.let { accountById(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, type) }
            ?: if (type == current.type) {
                current.categoryId?.let { categoryById(context, it) }
            } else {
                throw ToolRejectedException("category_required_for_type_change")
            }
        val recurrence = arguments.optionalString("recurrence")?.let(CashFlowRecurrence::valueOf)
            ?: current.recurrence
        val dayOfMonth = when (recurrence) {
            CashFlowRecurrence.ONCE -> null
            CashFlowRecurrence.MONTHLY -> arguments.optionalInt("dayOfMonth") ?: current.dayOfMonth
        }
        val updated = service.updateCashFlowEntry(
            context.financialContext,
            entryId,
            UpdateCashFlowEntryCommand(
                type = type,
                money = arguments.optionalDecimal("amount")?.let(Money::of) ?: current.money,
                description = arguments.optionalString("description")?.let(FinancialDescription::of)
                    ?: current.description,
                accountId = account?.id,
                categoryId = category?.id,
                recurrence = recurrence,
                startDate = arguments.optionalDate("startDate") ?: current.startDate,
                endDate = arguments.optionalDate("endDate") ?: current.endDate,
                dayOfMonth = dayOfMonth,
                active = current.active,
            ),
        ).requiredValue()
        return updated.toToolJson(account, category, operation = "updated")
    }
}

class DeleteCashFlowEntryTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "delete_cash_flow_entry",
        description = "Exclui logicamente uma conta, receita ou despesa futura cadastrada por engano e ainda não paga.",
        parameters = AiToolParameters(
            properties = mapOf(
                "cashFlowEntryId" to stringParameter("Identificador exato retornado por get_cash_flow_entries."),
            ),
            required = setOf("cashFlowEntryId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val deleted = service.deleteCashFlowEntry(
            context.financialContext,
            arguments.requiredUuid("cashFlowEntryId"),
        ).requiredValue()
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("deleted", JsonPrimitive(true))
            put("cashFlowEntryId", JsonPrimitive(deleted.id.toString()))
            put("description", JsonPrimitive(deleted.description.value))
            put("amount", moneyJson(deleted.money.amount))
            put("currency", JsonPrimitive(deleted.currency.name))
        }
    }
}

class GetCashFlowEntriesTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "get_cash_flow_entries",
        description = "Detalha contas, receitas e despesas previstas, informando quais ocorrências estão pendentes ou pagas.",
        parameters = AiToolParameters(
            properties = mapOf(
                "from" to stringParameter("Data inicial inclusiva YYYY-MM-DD."),
                "to" to stringParameter("Data final inclusiva YYYY-MM-DD."),
                "type" to stringParameter("Tipo opcional.", "INCOME", "EXPENSE"),
                "status" to stringParameter("Status opcional.", "PENDING", "PAID"),
                "recurrence" to stringParameter("Recorrência opcional.", "ONCE", "MONTHLY"),
                "account" to stringParameter("Nome opcional da conta."),
                "limit" to integerParameter("Máximo de ocorrências, entre 1 e 100; padrão 50."),
            ),
            required = setOf("from", "to"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val limit = (arguments.optionalInt("limit") ?: DEFAULT_CASH_FLOW_LIMIT).also {
            require(it in 1..MAX_CASH_FLOW_LIMIT) { "limit must be between 1 and $MAX_CASH_FLOW_LIMIT" }
        }
        val occurrences = service.getCashFlowOccurrences(
            context.financialContext,
            GetCashFlowOccurrencesQuery(
                from = arguments.requiredDate("from"),
                to = arguments.requiredDate("to"),
                type = arguments.optionalEntryType("type"),
                accountId = account?.id,
                status = arguments.optionalString("status")?.let(CashFlowOccurrenceStatus::valueOf),
            ),
        ).requiredValue().let { values ->
            val recurrence = arguments.optionalString("recurrence")?.let(CashFlowRecurrence::valueOf)
            if (recurrence == null) values else values.filter { occurrence -> occurrence.entry.recurrence == recurrence }
        }
        val accounts = accounts(context).associateBy(Account::id)
        val categories = categories(context).associateBy(Category::id)
        val totalAmount = occurrences.sumOf { occurrence -> occurrence.entry.money.amount }
        val incomeTotal = occurrences
            .filter { occurrence -> occurrence.entry.type == FinancialEntryType.INCOME }
            .sumOf { occurrence -> occurrence.entry.money.amount }
        val expenseTotal = occurrences
            .filter { occurrence -> occurrence.entry.type == FinancialEntryType.EXPENSE }
            .sumOf { occurrence -> occurrence.entry.money.amount }
        val monthlyExpenses = occurrences.filter { occurrence ->
            occurrence.entry.type == FinancialEntryType.EXPENSE &&
                occurrence.entry.recurrence == CashFlowRecurrence.MONTHLY
        }
        val monthlyExpenseTotal = monthlyExpenses.sumOf { occurrence -> occurrence.entry.money.amount }
        val plannedNetAmount = incomeTotal - expenseTotal
        val incomeAfterMonthlyExpenses = incomeTotal - monthlyExpenseTotal

        fun categoryBreakdown(type: FinancialEntryType) = occurrences
            .filter { occurrence -> occurrence.entry.type == type }
            .groupBy { occurrence ->
                occurrence.entry.categoryId?.let(categories::get)?.name?.value ?: "Sem categoria"
            }
            .toSortedMap()

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(minOf(occurrences.size, limit)))
            put("totalCount", JsonPrimitive(occurrences.size))
            put("truncated", JsonPrimitive(occurrences.size > limit))
            put("totalAmount", moneyJson(totalAmount))
            put("incomeTotal", moneyJson(incomeTotal))
            put("expenseTotal", moneyJson(expenseTotal))
            put("monthlyExpenseCount", JsonPrimitive(monthlyExpenses.size))
            put("monthlyExpenseTotal", moneyJson(monthlyExpenseTotal))
            put("plannedNetAmount", moneyJson(plannedNetAmount))
            put("incomeAfterMonthlyExpenses", moneyJson(incomeAfterMonthlyExpenses))
            put(
                "incomeByCategory",
                buildJsonArray {
                    categoryBreakdown(FinancialEntryType.INCOME).forEach { (category, values) ->
                        add(
                            buildJsonObject {
                                put("category", JsonPrimitive(category))
                                put("total", moneyJson(values.sumOf { it.entry.money.amount }))
                                put("count", JsonPrimitive(values.size))
                            },
                        )
                    }
                },
            )
            put(
                "expenseByCategory",
                buildJsonArray {
                    categoryBreakdown(FinancialEntryType.EXPENSE).forEach { (category, values) ->
                        add(
                            buildJsonObject {
                                put("category", JsonPrimitive(category))
                                put("total", moneyJson(values.sumOf { it.entry.money.amount }))
                                put("count", JsonPrimitive(values.size))
                            },
                        )
                    }
                },
            )
            put(
                "entries",
                buildJsonArray {
                    occurrences.take(limit).forEach { occurrence ->
                        val entry = occurrence.entry
                        add(
                            buildJsonObject {
                                put("cashFlowEntryId", JsonPrimitive(entry.id.toString()))
                                put("scheduledOn", JsonPrimitive(occurrence.scheduledOn.toString()))
                                put("status", JsonPrimitive(occurrence.status.name))
                                put("type", JsonPrimitive(entry.type.name))
                                put("amount", moneyJson(entry.money.amount))
                                put("currency", JsonPrimitive(entry.currency.name))
                                put("description", JsonPrimitive(entry.description.value))
                                put("recurrence", JsonPrimitive(entry.recurrence.name))
                                put("active", JsonPrimitive(entry.active))
                                entry.accountId?.let { accounts[it]?.name?.value }?.let {
                                    put("account", JsonPrimitive(it))
                                }
                                entry.categoryId?.let { categories[it]?.name?.value }?.let {
                                    put("category", JsonPrimitive(it))
                                }
                                occurrence.receipt?.let { receipt ->
                                    put("settlementId", JsonPrimitive(receipt.settlement.id.toString()))
                                    put("transactionId", JsonPrimitive(receipt.transaction.id.toString()))
                                    put("paidOn", JsonPrimitive(receipt.transaction.occurredOn.toString()))
                                    put("settledAt", JsonPrimitive(receipt.settlement.settledAt.toString()))
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val DEFAULT_CASH_FLOW_LIMIT = 50
        const val MAX_CASH_FLOW_LIMIT = 100
    }
}

class SettleCashFlowOccurrenceTool(
    service: FinancialApplicationService,
) : FinancialLocalTool(service) {
    override val definition = AiToolDefinition(
        name = "settle_cash_flow_occurrence",
        description = "Marca uma ocorrência futura como paga ou recebida e cria a transação real na data informada.",
        parameters = AiToolParameters(
            properties = mapOf(
                "cashFlowEntryId" to stringParameter("Identificador retornado por get_cash_flow_entries."),
                "scheduledOn" to stringParameter("Vencimento exato da ocorrência, no formato YYYY-MM-DD."),
                "paidOn" to stringParameter("Data real do pagamento ou recebimento, no formato YYYY-MM-DD."),
                "account" to stringParameter("Conta; obrigatória somente se a previsão não tiver conta."),
                "category" to stringParameter("Categoria; obrigatória somente se a previsão não tiver categoria."),
            ),
            required = setOf("cashFlowEntryId", "scheduledOn", "paidOn"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val entryId = arguments.requiredUuid("cashFlowEntryId")
        val entry = service.findCashFlowEntry(context.financialContext, entryId).requiredValue()
        val account = arguments.optionalString("account")?.let { resolveAccount(context, it) }
        val category = arguments.optionalString("category")?.let { resolveCategory(context, it, entry.type) }
        val creation = service.settleCashFlowOccurrence(
            context.financialContext,
            entryId,
            SettleCashFlowOccurrenceCommand(
                scheduledOn = arguments.requiredDate("scheduledOn"),
                paidOn = arguments.requiredDate("paidOn"),
                accountId = account?.id,
                categoryId = category?.id,
            ),
        ).requiredValue()
        val receipt = creation.value
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("status", JsonPrimitive("PAID"))
            put("cashFlowEntryId", JsonPrimitive(entryId.toString()))
            put("settlementId", JsonPrimitive(receipt.settlement.id.toString()))
            put("transactionId", JsonPrimitive(receipt.transaction.id.toString()))
            put("scheduledOn", JsonPrimitive(receipt.settlement.scheduledOn.toString()))
            put("paidOn", JsonPrimitive(receipt.transaction.occurredOn.toString()))
            put("settledAt", JsonPrimitive(receipt.settlement.settledAt.toString()))
            put("type", JsonPrimitive(receipt.transaction.type.name))
            put("amount", moneyJson(receipt.transaction.money.amount))
            put("currency", JsonPrimitive(receipt.transaction.currency.name))
            put("description", JsonPrimitive(receipt.transaction.description.value))
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
            put("incomeOccurrenceCount", JsonPrimitive(projection.incomeOccurrenceCount))
            put("expenseOccurrenceCount", JsonPrimitive(projection.expenseOccurrenceCount))
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

    protected suspend fun accountById(context: ToolExecutionContext, accountId: UUID): Account =
        accounts(context).singleOrNull { it.id == accountId }
            ?: throw ToolRejectedException("account_not_found")

    protected suspend fun categoryById(context: ToolExecutionContext, categoryId: UUID): Category =
        categories(context).singleOrNull { it.id == categoryId }
            ?: throw ToolRejectedException("category_not_found")

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

private fun JsonObject.optionalDecimal(name: String): BigDecimal? =
    this[name]?.jsonPrimitive?.content?.toBigDecimalOrNull()
        ?: if (name in this) throw IllegalArgumentException("Argument $name must be a decimal") else null

private fun JsonObject.requiredDate(name: String): LocalDate =
    LocalDate.parse(requiredString(name))

private fun JsonObject.optionalDate(name: String): LocalDate? = optionalString(name)?.let(LocalDate::parse)

private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.requiredUuid(name: String): UUID =
    runCatching { UUID.fromString(requiredString(name)) }
        .getOrElse { throw IllegalArgumentException("Argument $name must be a UUID") }

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
    put("transactionId", JsonPrimitive(id.toString()))
    put("type", JsonPrimitive(type.name))
    put("amount", moneyJson(money.amount))
    put("currency", JsonPrimitive(currency.name))
    put("description", JsonPrimitive(description.value))
    put("occurredOn", JsonPrimitive(occurredOn.toString()))
    accounts[accountId]?.let { put("account", JsonPrimitive(it.name.value)) }
    categories[categoryId]?.let { put("category", JsonPrimitive(it.name.value)) }
}

private fun Transaction.toToolJson(
    account: Account,
    category: Category,
    operation: String,
): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(true))
    put(operation, JsonPrimitive(true))
    put("transactionId", JsonPrimitive(id.toString()))
    put("type", JsonPrimitive(type.name))
    put("amount", moneyJson(money.amount))
    put("currency", JsonPrimitive(currency.name))
    put("description", JsonPrimitive(description.value))
    put("category", JsonPrimitive(category.name.value))
    put("account", JsonPrimitive(account.name.value))
    put("occurredOn", JsonPrimitive(occurredOn.toString()))
}

private fun CashFlowEntry.toToolJson(
    account: Account?,
    category: Category?,
    operation: String,
): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(true))
    put(operation, JsonPrimitive(true))
    put("cashFlowEntryId", JsonPrimitive(id.toString()))
    put("type", JsonPrimitive(type.name))
    put("amount", moneyJson(money.amount))
    put("currency", JsonPrimitive(currency.name))
    put("description", JsonPrimitive(description.value))
    put("recurrence", JsonPrimitive(recurrence.name))
    put("startDate", JsonPrimitive(startDate.toString()))
    endDate?.let { put("endDate", JsonPrimitive(it.toString())) }
    dayOfMonth?.let { put("dayOfMonth", JsonPrimitive(it)) }
    account?.let { put("account", JsonPrimitive(it.name.value)) }
    category?.let { put("category", JsonPrimitive(it.name.value)) }
}

private fun <T> FinancialResult<T>.requiredValue(): T = when (this) {
    is FinancialResult.Success -> value
    is FinancialResult.Rejected -> throw ToolRejectedException(
        "financial_rejection",
        buildJsonObject { put("reason", JsonPrimitive(reason.name.lowercase())) },
    )
}

private val COMBINING_MARKS = Regex("\\p{M}+")
