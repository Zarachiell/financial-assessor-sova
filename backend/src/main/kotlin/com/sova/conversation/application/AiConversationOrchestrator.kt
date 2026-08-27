package com.sova.conversation.application

import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiToolDefinition
import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.ToolDispatchResult
import com.sova.ai.application.ToolExecutionContext
import com.sova.finance.application.DefaultFinancialCatalog
import com.sova.finance.domain.FinancialContext
import com.sova.finance.domain.FinancialSource
import com.sova.whatsapp.application.WhatsappConversationContext
import com.sova.whatsapp.application.WhatsappMessageOrigin
import com.sova.whatsapp.application.WhatsappTextConversation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.TimeSource

enum class ConversationMessageRole {
    USER,
    ASSISTANT,
}

data class ConversationMessage(
    val role: ConversationMessageRole,
    val content: String,
)

fun interface ConversationHistory {
    suspend fun recentMessages(
        userId: UUID,
        excludingMessageSid: String,
        limit: Int,
    ): List<ConversationMessage>
}

enum class FamilyRole(val vocative: String) {
    FATHER("Pai"),
    MOTHER("Mãe"),
}

class AiConversationOrchestrator(
    private val provider: AiProvider,
    private val registry: LocalToolRegistry,
    private val dispatcher: LocalToolDispatcher,
    private val history: ConversationHistory,
    private val familyRolesByUserId: Map<UUID, FamilyRole>,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.of(DEFAULT_TIME_ZONE),
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val systemPersonality: String = loadPrompt("sova-system.md"),
    private val financialRules: String = loadPrompt("financial-rules.md"),
    private val toolCallingRules: String = loadPrompt("tool-calling.md"),
) : WhatsappTextConversation {
    init {
        require(maxToolRounds > 0) { "Maximum tool rounds must be positive" }
    }

    override suspend fun respond(context: WhatsappConversationContext): String {
        val messages = mutableListOf(
            AiMessage(AiMessageRole.SYSTEM, systemPrompt(context)),
        )
        history.recentMessages(context.userId, context.inboundMessageSid, HISTORY_LIMIT)
            .takeLast(HISTORY_LIMIT)
            .forEach { previous ->
                messages += AiMessage(
                    role = when (previous.role) {
                        ConversationMessageRole.USER -> AiMessageRole.USER
                        ConversationMessageRole.ASSISTANT -> AiMessageRole.ASSISTANT
                    },
                    content = previous.content.take(MAX_HISTORY_MESSAGE_LENGTH),
                )
            }
        messages += AiMessage(AiMessageRole.USER, context.text.take(MAX_USER_MESSAGE_LENGTH))

        val executionContext = ToolExecutionContext(
            financialContext = FinancialContext(context.householdId, context.userId),
            inboundMessageSid = context.inboundMessageSid,
            source = when (context.origin) {
                WhatsappMessageOrigin.TEXT -> FinancialSource.WHATSAPP_TEXT
                WhatsappMessageOrigin.AUDIO -> FinancialSource.WHATSAPP_AUDIO
            },
        )
        var completedToolRounds = 0
        var availableTools = registry.definitions
        var completedBatch: List<CompletedToolExecution> = emptyList()
        var finalizationRetryUsed = false
        val completedReadCalls = mutableSetOf<Pair<String, String>>()
        while (true) {
            val response = try {
                complete(messages, availableTools, context, completedToolRounds)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                if (completedBatch.isNotEmpty()) return localFallback(completedBatch, context, cause)
                throw cause
            }
            if (response.toolCalls.isEmpty()) {
                val finalText = response.content?.trim().orEmpty()
                if (finalText.isBlank()) throw AiConversationException("AI returned an empty response")
                if (!isGroundedAnalyticResponse(finalText, completedBatch, context)) {
                    return UNGROUNDED_ANALYTIC_FALLBACK
                }
                return applySovaVoice(finalText, completedBatch, context).take(MAX_OUTBOUND_MESSAGE_LENGTH)
            }
            if (response.toolCalls.all { call -> (call.name to call.arguments) in completedReadCalls }) {
                if (!finalizationRetryUsed) {
                    finalizationRetryUsed = true
                    availableTools = emptyList()
                    messages += AiMessage(AiMessageRole.USER, FINALIZATION_RETRY_PROMPT)
                    continue
                }
                return localFallback(completedBatch, context, AiConversationException("AI repeated a completed tool"))
            }
            if (availableTools.isEmpty() && completedBatch.isNotEmpty()) {
                if (!finalizationRetryUsed && completedBatch.none { it.name in PERSISTED_WRITE_TOOLS }) {
                    finalizationRetryUsed = true
                    messages += AiMessage(AiMessageRole.USER, FINALIZATION_RETRY_PROMPT)
                    continue
                }
                return localFallback(completedBatch, context, AiConversationException("AI called a disabled tool"))
            }
            if (completedToolRounds >= maxToolRounds) {
                throw AiConversationException("AI exceeded the local tool round limit")
            }
            requireValidToolCalls(response.toolCalls.map { it.id })
            completedToolRounds += 1
            messages += response
            val executedBatch = response.toolCalls.map { call ->
                val result = dispatcher.dispatch(call, executionContext)
                messages += AiMessage(
                    role = AiMessageRole.TOOL,
                    content = result.content,
                    toolCallId = call.id,
                )
                CompletedToolExecution(call.name, result)
            }
            val batchSucceeded = executedBatch.all { it.result.successful }
            completedBatch = if (batchSucceeded) {
                completedBatch + executedBatch
            } else {
                completedBatch + executedBatch.filter { execution ->
                    execution.name in PERSISTED_WRITE_TOOLS && execution.result.successful
                }
            }
            val writePersisted = executedBatch.any { execution ->
                execution.name in PERSISTED_WRITE_TOOLS && execution.result.successful
            }
            response.toolCalls.zip(executedBatch)
                .filter { (_, execution) -> execution.result.successful && execution.name !in PERSISTED_WRITE_TOOLS }
                .forEach { (call, _) -> completedReadCalls += call.name to call.arguments }
            val recordLookupCompleted = executedBatch.any { execution ->
                execution.name in RECORD_LOOKUP_TOOLS && execution.result.successful
            }
            availableTools = when {
                writePersisted -> emptyList()
                !batchSucceeded || recordLookupCompleted -> registry.definitions
                else -> emptyList()
            }
        }
    }

    private suspend fun complete(
        messages: List<AiMessage>,
        tools: List<AiToolDefinition>,
        context: WhatsappConversationContext,
        toolRound: Int,
    ): AiMessage {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val completion = provider.complete(AiRequest(messages.toList(), tools))
            val response = completion.message
            val metadata = completion.metadata
            LOGGER.atInfo()
                .addKeyValue("status", "success")
                .addKeyValue("provider", metadata?.provider)
                .addKeyValue("model", metadata?.model)
                .addKeyValue("inputTokens", metadata?.usage?.inputTokens)
                .addKeyValue("outputTokens", metadata?.usage?.outputTokens)
                .addKeyValue("totalTokens", metadata?.usage?.totalTokens)
                .addKeyValue("finishReason", metadata?.finishReason?.name?.lowercase())
                .addKeyValue("toolRound", toolRound)
                .addKeyValue("toolCallCount", response.toolCalls.size)
                .addKeyValue("toolDefinitionCount", tools.size)
                .addKeyValue("userId", context.userId)
                .addKeyValue("householdId", context.householdId)
                .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                .log("AI interaction processed")
            response
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            val providerFailure = cause as? AiProviderException
            val status = providerFailure?.failure?.name?.lowercase() ?: "failed"
            val event = LOGGER.atWarn()
                .addKeyValue("status", status)
                .addKeyValue("toolRound", toolRound)
                .addKeyValue("userId", context.userId)
                .addKeyValue("householdId", context.householdId)
                .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
            providerFailure?.httpStatusCode?.let { event.addKeyValue("providerHttpStatus", it) }
            providerFailure?.providerErrorType?.let { event.addKeyValue("providerErrorType", it) }
            providerFailure?.providerErrorCode?.let { event.addKeyValue("providerErrorCode", it) }
            event.log("AI interaction failed")
            throw cause
        }
    }

    private fun localFallback(
        executions: List<CompletedToolExecution>,
        context: WhatsappConversationContext,
        cause: Exception,
    ): String {
        val providerStatus = (cause as? AiProviderException)?.failure?.name?.lowercase() ?: "invalid_final_response"
        LOGGER.atWarn()
            .addKeyValue("status", "local_fallback")
            .addKeyValue("providerStatus", providerStatus)
            .addKeyValue("toolNames", executions.joinToString(",") { it.name })
            .addKeyValue("userId", context.userId)
            .addKeyValue("householdId", context.householdId)
            .log("AI final response replaced by local fallback")
        val fallback = executions.singleOrNull()
            ?.takeIf { it.name == CREATE_TRANSACTION_TOOL }
            ?.let(::transactionFallback)
            ?: executions.singleOrNull()
                ?.takeIf { it.name == CREATE_CASH_FLOW_ENTRY_TOOL }
                ?.let(::cashFlowFallback)
            ?: executions.singleOrNull()
                ?.takeIf { it.name == GET_CASH_FLOW_PROJECTION_TOOL }
                ?.let(::projectionFallback)
            ?: if (executions.none { it.name in PERSISTED_WRITE_TOOLS }) {
                READ_LOCAL_FALLBACK
            } else {
                GENERIC_LOCAL_FALLBACK
            }
        return applySovaVoice(fallback, executions, context).take(MAX_OUTBOUND_MESSAGE_LENGTH)
    }

    private fun applySovaVoice(
        response: String,
        executions: List<CompletedToolExecution>,
        context: WhatsappConversationContext,
    ): String {
        if (executions.none { it.result.successful }) return response
        val familyRole = familyRolesByUserId[context.userId]
            ?: throw AiConversationException("Authenticated user has no configured family role")
        val hasVocative = Regex("(?iu)\\b${Regex.escape(familyRole.vocative)}\\b").containsMatchIn(response)
        val hasBeagleVoice = BEAGLE_VOICE_MARKER.containsMatchIn(response)
        val personalized = if (hasVocative) {
            response
        } else {
            "${familyRole.vocative}, ${response.lowercaseSentenceStart()}"
        }
        return if (hasBeagleVoice) personalized else "$personalized Meu faro conferiu cada centavo. 🐾"
    }

    private fun isGroundedAnalyticResponse(
        response: String,
        executions: List<CompletedToolExecution>,
        context: WhatsappConversationContext,
    ): Boolean {
        val analyticExecutions = executions.filter { it.name in ANALYTIC_TOOLS }
        if (analyticExecutions.isEmpty()) return true
        val grounding = analyticExecutions.fold(ResponseGrounding()) { accumulated, execution ->
            val content = runCatching { JSON.parseToJsonElement(execution.result.content) }.getOrNull()
            if (content == null) accumulated else accumulated + content.groundingNumbers()
        }
        val ungroundedMoney = MONEY_CLAIM.findAll(response)
            .mapNotNull { match ->
                (match.groups[1]?.value ?: match.groups[2]?.value)?.asLocalizedDecimal()
            }
            .any { claim -> claim.moneyScale() !in grounding.money }
        val ungroundedPercentage = PERCENTAGE_CLAIM.findAll(response)
            .mapNotNull { match -> match.groups[1]?.value?.asLocalizedDecimal() }
            .any { claim -> claim.percentageScale() !in grounding.percentages }
        if (!ungroundedMoney && !ungroundedPercentage) return true

        LOGGER.atWarn()
            .addKeyValue("status", "ungrounded_analytic_response")
            .addKeyValue("toolNames", analyticExecutions.joinToString(",") { it.name })
            .addKeyValue("userId", context.userId)
            .addKeyValue("householdId", context.householdId)
            .log("AI analytic response rejected by local grounding guard")
        return false
    }

    private fun transactionFallback(execution: CompletedToolExecution): String = runCatching {
        val content = JSON.parseToJsonElement(execution.result.content).jsonObject
        require(content.getValue("ok").jsonPrimitive.boolean)
        val type = when (content.getValue("type").jsonPrimitive.content) {
            "INCOME" -> "receita"
            "EXPENSE" -> "despesa"
            else -> error("Unsupported transaction type")
        }
        val amount = content.getValue("amount").jsonPrimitive.content.toBigDecimal().asBrazilianMoney()
        val category = content.getValue("category").jsonPrimitive.content
        val occurredOn = LocalDate.parse(content.getValue("occurredOn").jsonPrimitive.content).format(DISPLAY_DATE)
        "Registrado com sucesso: $type de R$ $amount em $category, em $occurredOn. 🐾 " +
            "Não envie novamente; a resposta detalhada ficou indisponível."
    }.getOrDefault(GENERIC_LOCAL_FALLBACK)

    private fun cashFlowFallback(execution: CompletedToolExecution): String = runCatching {
        val content = JSON.parseToJsonElement(execution.result.content).jsonObject
        require(content.getValue("ok").jsonPrimitive.boolean)
        val type = when (content.getValue("type").jsonPrimitive.content) {
            "INCOME" -> "receita prevista"
            "EXPENSE" -> "despesa prevista"
            else -> error("Unsupported cash flow type")
        }
        val amount = content.getValue("amount").jsonPrimitive.content.toBigDecimal().asBrazilianMoney()
        val recurrence = when (content.getValue("recurrence").jsonPrimitive.content) {
            "ONCE" -> "pontual"
            "MONTHLY" -> "mensal"
            else -> error("Unsupported cash flow recurrence")
        }
        "Planejamento registrado: $type de R$ $amount, $recurrence. 🐾 " +
            "Não envie novamente; a resposta detalhada ficou indisponível."
    }.getOrDefault(GENERIC_LOCAL_FALLBACK)

    private fun projectionFallback(execution: CompletedToolExecution): String = runCatching {
        val content = JSON.parseToJsonElement(execution.result.content).jsonObject
        require(content.getValue("ok").jsonPrimitive.boolean)
        val currentBalance = content.getValue("currentBalance").moneyContent()
        val expectedIncome = content.getValue("expectedIncome").moneyContent()
        val expectedExpenses = content.getValue("expectedExpenses").moneyContent()
        val projectedBalance = content.getValue("projectedBalance").moneyContent()
        val targetDate = LocalDate.parse(content.getValue("targetDate").jsonPrimitive.content).format(DISPLAY_DATE)
        "Projeção até $targetDate: saldo atual R$ $currentBalance + receitas previstas R$ $expectedIncome " +
            "- despesas previstas R$ $expectedExpenses = saldo projetado R$ $projectedBalance."
    }.getOrDefault(GENERIC_LOCAL_FALLBACK)

    private fun systemPrompt(context: WhatsappConversationContext): String {
        val today = LocalDate.now(clock.withZone(zoneId))
        val currentMonth = YearMonth.from(today)
        val previousMonth = currentMonth.minusMonths(1)
        val familyRole = familyRolesByUserId[context.userId]
            ?: throw AiConversationException("Authenticated user has no configured family role")
        return """
            ${systemPersonality.trim()}

            ---

            ${financialRules.trim()}

            ---

            ${toolCallingRules.trim()}

            ## Contexto operacional desta conversa

            Quem está falando nesta conversa: ${familyRole.vocative}. Use esse vocativo naturalmente e nunca troque
            Pai por Mãe ou Mãe por Pai.

            Responda em português brasileiro. Cálculos e dados financeiros vêm somente das tools. Para receita/despesa
            clara, chame create_transaction; se houver vários lançamentos claros, faça uma chamada por item no mesmo
            round com itemIndex sequencial 1..N. Se valor, tipo ou intenção forem ambíguos ("acho", "uns 200"),
            confirme antes. Histórico ajuda a entender confirmações, nunca a repetir escrita.

            create_transaction: type INCOME/EXPENSE, amount positivo, occurredOn YYYY-MM-DD e nomes de conta/categoria;
            omita opcionais vazios. Futuro ou recorrente usa create_cash_flow_entry: recurrence ONCE/MONTHLY,
            startDate YYYY-MM-DD, dayOfMonth 1..31 obrigatório para MONTHLY e endDate opcional. Projeções usam
            get_cash_flow_projection com targetDate YYYY-MM-DD. occurrenceCount é o total combinado; para quantidades
            por tipo use incomeOccurrenceCount e expenseOccurrenceCount. Para listar contas futuras e descobrir o
            identificador exato, use get_cash_flow_entries. Para quitar, use settle_cash_flow_occurrence com o
            cashFlowEntryId e scheduledOn retornados; paidOn é a data real e não pode estar no futuro. Edição/exclusão
            exige identificar primeiro um único registro via get_transactions ou get_cash_flow_entries. Categorias de despesa:
            ${DefaultFinancialCatalog.expenseCategories.keys.joinToString()}.
            Categorias de receita: ${DefaultFinancialCatalog.incomeCategories.joinToString()}. Almoço/alimentação = Alimentação;
            restaurante = Restaurante; reembolso = Reembolso.

            Análises usam só métricas das tools de insights. Para economia, combine apenas as necessárias entre
            compare_periods, get_budget_deviations, get_recurring_expenses e get_largest_expenses. Envie metas como
            savingsTarget; use só savingsPlan e declare remainingAmount. Tendências usam get_expense_trend; avaliação
            geral usa get_financial_health_snapshot e nunca cria score. savingsRate e ratios são frações; campos Pct são
            percentuais. Recorrência é heurística: diga "parece recorrente" e cite confidence. Não calcule por conta
            própria, converta ausência de baseline em percentual nem trate projeção como garantia.

            Datas em $DEFAULT_TIME_ZONE: hoje = $today; ontem = ${today.minusDays(1)};
            este mês=${currentMonth.atDay(1)}..${currentMonth.atEndOfMonth()};
            mês passado=${previousMonth.atDay(1)}..${previousMonth.atEndOfMonth()}.
            Após tools, use exatamente o resultado e responda curto na voz do Sova: trate por ${familyRole.vocative}
            e inclua uma breve marca de faro/beagle. Saldo é o saldo registrado.
        """.trimIndent()
    }

    private fun requireValidToolCalls(ids: List<String>) {
        if (ids.size > MAX_TOOL_CALLS_PER_ROUND || ids.distinct().size != ids.size) {
            throw AiConversationException("AI returned an invalid tool call batch")
        }
    }

    private companion object {
        const val DEFAULT_TIME_ZONE = "America/Fortaleza"
        const val DEFAULT_MAX_TOOL_ROUNDS = 5
        const val HISTORY_LIMIT = 4
        const val MAX_HISTORY_MESSAGE_LENGTH = 500
        const val MAX_USER_MESSAGE_LENGTH = 1_000
        const val MAX_OUTBOUND_MESSAGE_LENGTH = 1_500
        const val MAX_TOOL_CALLS_PER_ROUND = 8
        const val CREATE_TRANSACTION_TOOL = "create_transaction"
        const val CREATE_CASH_FLOW_ENTRY_TOOL = "create_cash_flow_entry"
        const val GET_CASH_FLOW_PROJECTION_TOOL = "get_cash_flow_projection"
        val PERSISTED_WRITE_TOOLS = setOf(
            CREATE_TRANSACTION_TOOL,
            CREATE_CASH_FLOW_ENTRY_TOOL,
            "update_transaction",
            "delete_transaction",
            "update_cash_flow_entry",
            "delete_cash_flow_entry",
            "settle_cash_flow_occurrence",
        )
        val RECORD_LOOKUP_TOOLS = setOf("get_transactions", "get_cash_flow_entries")
        val ANALYTIC_TOOLS = setOf(
            "get_financial_snapshot",
            "compare_periods",
            "get_expense_trend",
            "get_largest_expenses",
            "get_recurring_expenses",
            "get_budget_deviations",
            "get_financial_health_snapshot",
            "get_cash_flow_entries",
        )
        const val GENERIC_LOCAL_FALLBACK =
            "A operação financeira foi concluída localmente, mas a resposta detalhada ficou indisponível. " +
                "Se foi um registro, não envie novamente; consulte os dados em instantes."
        const val READ_LOCAL_FALLBACK =
            "A consulta foi conclu\u00edda localmente, mas a resposta detalhada ficou indispon\u00edvel. " +
                "Tente consultar novamente em instantes."
        const val FINALIZATION_RETRY_PROMPT =
            "A consulta local j\u00e1 foi executada. Responda agora somente em texto, usando os resultados das tools acima. " +
                "N\u00e3o chame nenhuma ferramenta novamente."
        const val UNGROUNDED_ANALYTIC_FALLBACK =
            "Não consegui validar todos os valores da análise com segurança. Reformule a pergunta para eu consultar " +
                "os dados novamente."
        const val LOCALIZED_NUMBER =
            "-?(?:[0-9]{1,3}(?:[. ][0-9]{3})+(?:,[0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)"
        val MONEY_CLAIM = Regex("(?i)(?:R\\$\\s*($LOCALIZED_NUMBER)|($LOCALIZED_NUMBER)\\s*reais\\b)")
        val PERCENTAGE_CLAIM = Regex("($LOCALIZED_NUMBER)\\s*%")
        val BEAGLE_VOICE_MARKER = Regex("(?iu)faro|farej|cheiro|beagle|petisco|passeio|\\bcav(?:ar|ei|ou)\\b|🐾|🐶|👃")
        val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val JSON = Json { ignoreUnknownKeys = true }
        val LOGGER = LoggerFactory.getLogger("com.sova.ai.interaction")
    }
}

class AiConversationException(message: String) : RuntimeException(message)

private data class CompletedToolExecution(
    val name: String,
    val result: ToolDispatchResult,
)

private fun BigDecimal.asBrazilianMoney(): String = setScale(2).toPlainString().replace('.', ',')

private fun String.lowercaseSentenceStart(): String =
    if (startsWith("R$")) this else replaceFirstChar { character -> character.lowercase() }

private fun kotlinx.serialization.json.JsonElement.moneyContent(): String =
    jsonPrimitive.content.toBigDecimal().asBrazilianMoney()

private data class ResponseGrounding(
    val money: Set<BigDecimal> = emptySet(),
    val percentages: Set<BigDecimal> = emptySet(),
) {
    operator fun plus(other: ResponseGrounding): ResponseGrounding = ResponseGrounding(
        money = money + other.money,
        percentages = percentages + other.percentages,
    )
}

private fun JsonElement.groundingNumbers(fieldName: String? = null): ResponseGrounding = when (this) {
    is JsonObject -> entries.fold(ResponseGrounding()) { accumulated, (name, value) ->
        accumulated + value.groundingNumbers(name)
    }
    is JsonArray -> fold(ResponseGrounding()) { accumulated, value ->
        accumulated + value.groundingNumbers(fieldName)
    }
    is JsonPrimitive -> {
        val number = takeUnless(JsonPrimitive::isString)?.content?.toBigDecimalOrNull()
            ?: return ResponseGrounding()
        val normalizedName = fieldName?.lowercase().orEmpty()
        ResponseGrounding(
            money = if (normalizedName.isMoneyField()) setOf(number.moneyScale()) else emptySet(),
            percentages = if (normalizedName.isPercentageField()) {
                setOf(number.percentageScale())
            } else {
                emptySet()
            },
        )
    }
}

private fun String.isMoneyField(): Boolean =
    MONEY_CONTAINED_FIELDS.any(::contains) || endsWith("income") || endsWith("expense") || endsWith("expenses")

private fun String.isPercentageField(): Boolean = contains("percentage") || endsWith("pct")

private fun String.asLocalizedDecimal(): BigDecimal? {
    val compact = replace(" ", "")
    val normalized = when {
        compact.contains(',') -> compact.replace(".", "").replace(',', '.')
        compact.matches(BRAZILIAN_GROUPED_INTEGER) -> compact.replace(".", "")
        else -> compact
    }
    return normalized.toBigDecimalOrNull()
}

private fun BigDecimal.moneyScale(): BigDecimal = setScale(2)

private fun BigDecimal.percentageScale(): BigDecimal = setScale(2)

private val MONEY_CONTAINED_FIELDS = listOf(
    "amount",
    "total",
    "balance",
    "expenses",
    "cashflow",
    "cut",
    "average",
    "absolutechange",
)

private val BRAZILIAN_GROUPED_INTEGER = Regex("[0-9]{1,3}(?:\\.[0-9]{3})+")

private fun loadPrompt(fileName: String): String = requireNotNull(
    AiConversationOrchestrator::class.java.getResourceAsStream("/prompts/$fileName"),
) { "Missing prompt resource: $fileName" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
