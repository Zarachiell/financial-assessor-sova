package com.sova.conversation.application

import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiRequest
import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.ToolExecutionContext
import com.sova.finance.application.DefaultFinancialCatalog
import com.sova.finance.domain.FinancialContext
import com.sova.whatsapp.application.WhatsappConversationContext
import com.sova.whatsapp.application.WhatsappTextConversation
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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
        while (true) {
            val response = complete(messages, context, completedToolRounds)
            if (response.toolCalls.isEmpty()) {
                val finalText = response.content?.trim().orEmpty()
                if (finalText.isBlank()) throw AiConversationException("AI returned an empty response")
                return finalText.take(MAX_OUTBOUND_MESSAGE_LENGTH)
            }
            if (completedToolRounds >= maxToolRounds) {
                throw AiConversationException("AI exceeded the local tool round limit")
            }
            requireValidToolCalls(response.toolCalls.map { it.id })
            completedToolRounds += 1
            messages += response
            response.toolCalls.forEach { call ->
                val result = dispatcher.dispatch(call, executionContext)
                messages += AiMessage(
                    role = AiMessageRole.TOOL,
                    content = result.content,
                    toolCallId = call.id,
                )
            }
        }
    }

    private suspend fun complete(
        messages: List<AiMessage>,
        context: WhatsappConversationContext,
        toolRound: Int,
    ): AiMessage {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            provider.complete(AiRequest(messages.toList(), registry.definitions)).message.also { response ->
                LOGGER.atInfo()
                    .addKeyValue("status", "success")
                    .addKeyValue("toolRound", toolRound)
                    .addKeyValue("toolCallCount", response.toolCalls.size)
                    .addKeyValue("userId", context.userId)
                    .addKeyValue("householdId", context.householdId)
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .log("AI interaction processed")
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            val status = (cause as? AiProviderException)?.failure?.name?.lowercase() ?: "failed"
            LOGGER.atWarn()
                .addKeyValue("status", status)
                .addKeyValue("toolRound", toolRound)
                .addKeyValue("userId", context.userId)
                .addKeyValue("householdId", context.householdId)
                .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                .log("AI interaction failed")
            throw cause
        }
    }

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

            Você é o Sova, assessor financeiro privado de um único household. Responda em português brasileiro.
            Todos os cálculos financeiros são feitos pelas tools locais; não calcule nem invente valores.

            Uma mensagem clara e assertiva de receita ou despesa deve chamar create_transaction uma única vez.
            Se valor, tipo ou intenção forem materialmente ambíguos (por exemplo, "acho" ou "uns 200"), não execute tool:
            peça confirmação objetiva. Use o histórico curto somente para compreender uma confirmação como "sim".
            Nunca registre uma transação apenas porque ela apareceu no histórico.

            Para create_transaction, use type INCOME ou EXPENSE, amount numérico positivo, occurredOn YYYY-MM-DD e
            somente nomes de conta/categoria. Omita account para a conta padrão. Categorias iniciais de despesa:
            ${DefaultFinancialCatalog.expenseCategories.joinToString()}. Categorias iniciais de receita:
            ${DefaultFinancialCatalog.incomeCategories.joinToString()}. Para almoço e alimentação use Alimentação;
            para restaurante use Restaurante; para reembolso use Reembolso. Se uma tool informar nomes disponíveis,
            corrija os argumentos e tente novamente. Nunca envie userId, householdId, databaseId, SQL ou URLs.

            Contexto de datas em $DEFAULT_TIME_ZONE:
            hoje = $today; ontem = ${today.minusDays(1)};
            este mês = ${currentMonth.atDay(1)} até ${currentMonth.atEndOfMonth()};
            mês passado = ${previousMonth.atDay(1)} até ${previousMonth.atEndOfMonth()}.
            Datas finais ainda serão validadas localmente.

            Depois das tools, use apenas os resultados retornados e dê uma confirmação curta e natural, sem JSON.
            Saldo significa receitas registradas menos despesas registradas; deixe claro que é o saldo registrado.
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
        const val HISTORY_LIMIT = 6
        const val MAX_HISTORY_MESSAGE_LENGTH = 1_000
        const val MAX_USER_MESSAGE_LENGTH = 2_000
        const val MAX_OUTBOUND_MESSAGE_LENGTH = 1_500
        const val MAX_TOOL_CALLS_PER_ROUND = 8
        val LOGGER = LoggerFactory.getLogger("com.sova.ai.interaction")
    }
}

class AiConversationException(message: String) : RuntimeException(message)

private fun loadPrompt(fileName: String): String = requireNotNull(
    AiConversationOrchestrator::class.java.getResourceAsStream("/prompts/$fileName"),
) { "Missing prompt resource: $fileName" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
