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
import com.sova.whatsapp.application.WhatsappConversationContext
import com.sova.whatsapp.application.WhatsappTextConversation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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

class AiConversationOrchestrator(
    private val provider: AiProvider,
    private val registry: LocalToolRegistry,
    private val dispatcher: LocalToolDispatcher,
    private val history: ConversationHistory,
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
            AiMessage(AiMessageRole.SYSTEM, systemPrompt()),
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
        )
        var completedToolRounds = 0
        var availableTools = registry.definitions
        var completedBatch: List<CompletedToolExecution> = emptyList()
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
                return finalText.take(MAX_OUTBOUND_MESSAGE_LENGTH)
            }
            if (availableTools.isEmpty() && completedBatch.isNotEmpty()) {
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
                executedBatch
            } else {
                completedBatch + executedBatch.filter { execution ->
                    execution.name in PERSISTED_WRITE_TOOLS && execution.result.successful
                }
            }
            availableTools = if (batchSucceeded) emptyList() else registry.definitions
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
            provider.complete(AiRequest(messages.toList(), tools)).message.also { response ->
                LOGGER.atInfo()
                    .addKeyValue("status", "success")
                    .addKeyValue("toolRound", toolRound)
                    .addKeyValue("toolCallCount", response.toolCalls.size)
                    .addKeyValue("toolDefinitionCount", tools.size)
                    .addKeyValue("userId", context.userId)
                    .addKeyValue("householdId", context.householdId)
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .log("AI interaction processed")
            }
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
        return executions.singleOrNull()
            ?.takeIf { it.name == CREATE_TRANSACTION_TOOL }
            ?.let(::transactionFallback)
            ?: executions.singleOrNull()
                ?.takeIf { it.name == CREATE_CASH_FLOW_ENTRY_TOOL }
                ?.let(::cashFlowFallback)
            ?: GENERIC_LOCAL_FALLBACK
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

    private fun systemPrompt(): String {
        val today = LocalDate.now(clock.withZone(zoneId))
        val currentMonth = YearMonth.from(today)
        val previousMonth = currentMonth.minusMonths(1)
        return """
            ${systemPersonality.trim()}

            ---

            ${financialRules.trim()}

            ---

            ${toolCallingRules.trim()}

            ## Contexto operacional desta conversa

            Você é o Sova e responde em português brasileiro. Cálculos e dados financeiros vêm somente das tools.
            Mensagem clara de receita/despesa: chame create_transaction uma vez. Se valor, tipo ou intenção forem
            ambíguos ("acho", "uns 200"), peça confirmação sem chamar tool. Histórico serve para entender confirmações,
            nunca para repetir escrita.

            create_transaction: type INCOME/EXPENSE, amount positivo, occurredOn YYYY-MM-DD e nomes de conta/categoria;
            omita campos opcionais sem valor. Evento futuro ou recorrente usa create_cash_flow_entry, nunca
            create_transaction: recurrence ONCE/MONTHLY, startDate YYYY-MM-DD, dayOfMonth 1..31 obrigatório para MONTHLY
            e endDate opcional. Projeções usam get_cash_flow_projection com targetDate YYYY-MM-DD; nunca calcule saldo
            projetado por conta própria. Categorias de despesa: ${DefaultFinancialCatalog.expenseCategories.joinToString()}.
            Categorias de receita: ${DefaultFinancialCatalog.incomeCategories.joinToString()}. Almoço/alimentação = Alimentação;
            restaurante = Restaurante; reembolso = Reembolso. Nunca envie IDs, SQL ou URLs.

            Datas em $DEFAULT_TIME_ZONE: hoje = $today; ontem = ${today.minusDays(1)};
            este mês=${currentMonth.atDay(1)}..${currentMonth.atEndOfMonth()};
            mês passado=${previousMonth.atDay(1)}..${previousMonth.atEndOfMonth()}.
            Após tools, responda curto e natural usando exatamente o resultado. Saldo é o saldo registrado.
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
        val PERSISTED_WRITE_TOOLS = setOf(CREATE_TRANSACTION_TOOL, CREATE_CASH_FLOW_ENTRY_TOOL)
        const val GENERIC_LOCAL_FALLBACK =
            "A operação financeira foi concluída localmente, mas a resposta detalhada ficou indisponível. " +
                "Se foi um registro, não envie novamente; consulte os dados em instantes."
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

private fun loadPrompt(fileName: String): String = requireNotNull(
    AiConversationOrchestrator::class.java.getResourceAsStream("/prompts/$fileName"),
) { "Missing prompt resource: $fileName" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
