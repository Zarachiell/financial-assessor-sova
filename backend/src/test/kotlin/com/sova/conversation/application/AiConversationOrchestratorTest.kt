package com.sova.conversation.application

import com.sova.ai.application.AiJsonType
import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiProviderFailure
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiResponse
import com.sova.ai.application.AiToolCall
import com.sova.ai.application.AiToolDefinition
import com.sova.ai.application.AiToolParameter
import com.sova.ai.application.AiToolParameters
import com.sova.ai.application.LocalTool
import com.sova.ai.application.LocalToolDispatcher
import com.sova.ai.application.LocalToolRegistry
import com.sova.ai.application.ToolExecutionContext
import com.sova.ai.application.ToolRejectedException
import com.sova.whatsapp.application.WhatsappConversationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiConversationOrchestratorTest {
    private val householdId = UUID.fromString("10000000-0000-0000-0000-000000000000")
    private val userId = UUID.fromString("20000000-0000-0000-0000-000000000000")
    private val clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `returns a direct answer with temporal context and short history`() = runBlocking {
        val provider = QueueAiProvider(listOf(answer("Preciso que confirme o valor.")))
        val tool = RecordingTool("get_current_balance")
        val orchestrator = orchestrator(provider, listOf(tool)) {
            listOf(
                ConversationMessage(ConversationMessageRole.USER, "Acho que gastei uns 200 ontem."),
                ConversationMessage(ConversationMessageRole.ASSISTANT, "Você confirma uma despesa de R$ 200,00?"),
            )
        }

        val result = orchestrator.respond(context("sim"))

        assertEquals("Preciso que confirme o valor.", result)
        val messages = provider.requests.single().messages
        val systemPrompt = messages.first().content.orEmpty()
        assertTrue(systemPrompt.contains("puro suco de um beagle"))
        assertTrue(systemPrompt.contains("Quem está falando nesta conversa: Pai."))
        assertTrue(systemPrompt.contains("Esse não escapa do meu faro"))
        assertTrue(systemPrompt.contains("fonte da verdade financeira"))
        assertTrue(systemPrompt.contains("única forma autorizada"))
        assertTrue(systemPrompt.contains("hoje = 2026-08-25"))
        assertTrue(systemPrompt.contains("occurrenceCount é o total combinado"))
        assertTrue(systemPrompt.contains("responda curto na voz do Sova"))
        assertTrue(
            systemPrompt.length <= 7_000,
            "System prompt exceeded the free-tier token budget guard: ${systemPrompt.length} chars",
        )
        assertEquals(
            listOf(AiMessageRole.SYSTEM, AiMessageRole.USER, AiMessageRole.ASSISTANT, AiMessageRole.USER),
            messages.map { it.role },
        )
        assertEquals("sim", messages.last().content)
    }

    @Test
    fun `identifies mother deterministically in the system prompt`() = runBlocking {
        val provider = QueueAiProvider(listOf(answer("Boa, Mãe. Anotado no meu faro.")))
        val orchestrator = orchestrator(
            provider = provider,
            tools = emptyList(),
            familyRolesByUserId = mapOf(userId to FamilyRole.MOTHER),
        ) { emptyList() }

        val result = orchestrator.respond(context("Recebi um reembolso"))

        assertEquals("Boa, Mãe. Anotado no meu faro.", result)
        val systemPrompt = provider.requests.single().messages.first().content.orEmpty()
        assertTrue(systemPrompt.contains("Quem está falando nesta conversa: Mãe."))
        assertFalse(systemPrompt.contains("Quem está falando nesta conversa: Pai."))
    }

    @Test
    fun `executes one or multiple tool calls locally and injects authenticated context`() = runBlocking {
        val balance = RecordingTool("get_current_balance")
        val expenses = RecordingTool("get_expenses")
        val provider = QueueAiProvider(
            listOf(
                toolCalls(
                    AiToolCall("call-balance", "get_current_balance", "{}"),
                    AiToolCall("call-expenses", "get_expenses", "{}"),
                ),
                answer("O saldo e as despesas foram consultados localmente."),
            ),
        )
        val orchestrator = orchestrator(provider, listOf(balance, expenses)) { emptyList() }

        val result = orchestrator.respond(context("Qual nosso saldo e despesas?"))

        assertEquals(
            "Pai, o saldo e as despesas foram consultados localmente. Meu faro conferiu cada centavo. 🐾",
            result,
        )
        assertEquals(1, balance.executions.size)
        assertEquals(1, expenses.executions.size)
        assertEquals(householdId, balance.executions.single().financialContext.householdId)
        assertEquals(userId, balance.executions.single().financialContext.userId)
        assertEquals("SM-INBOUND", balance.executions.single().inboundMessageSid)
        val toolMessages = provider.requests.last().messages.filter { it.role == AiMessageRole.TOOL }
        assertEquals(listOf("call-balance", "call-expenses"), toolMessages.map { it.toolCallId })
        assertTrue(provider.requests.last().tools.isEmpty())
    }

    @Test
    fun `open savings question combines analytic tools and returns only minimal local results`() = runBlocking {
        val comparison = RecordingTool("compare_periods")
        val deviations = RecordingTool("get_budget_deviations")
        val recurring = RecordingTool("get_recurring_expenses")
        val largest = RecordingTool("get_largest_expenses")
        val provider = QueueAiProvider(
            listOf(
                toolCalls(
                    AiToolCall("call-comparison", "compare_periods", "{}"),
                    AiToolCall("call-deviations", "get_budget_deviations", "{}"),
                    AiToolCall("call-recurring", "get_recurring_expenses", "{}"),
                    AiToolCall("call-largest", "get_largest_expenses", "{}"),
                ),
                answer("Restaurantes e lazer são as oportunidades fundamentadas nos dados locais."),
            ),
        )
        val orchestrator = orchestrator(provider, listOf(comparison, deviations, recurring, largest)) { emptyList() }

        val response = orchestrator.respond(context("Onde podemos economizar este mês?"))

        assertEquals(
            "Pai, restaurantes e lazer são as oportunidades fundamentadas nos dados locais. " +
                "Meu faro conferiu cada centavo. 🐾",
            response,
        )
        assertEquals(4, listOf(comparison, deviations, recurring, largest).sumOf { it.executions.size })
        val finalRequest = provider.requests.last()
        assertTrue(finalRequest.tools.isEmpty())
        val localResults = finalRequest.messages.filter { it.role == AiMessageRole.TOOL }.mapNotNull { it.content }
        assertEquals(4, localResults.size)
        assertTrue(localResults.none { it.contains("transactions", ignoreCase = true) })
        assertTrue(finalRequest.messages.first().content.orEmpty().contains("nunca cria score"))
    }

    @Test
    fun `analytic response accepts only monetary and percentage facts returned by tools`() = runBlocking {
        val groundedProvider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-insight", "get_financial_snapshot", "{}")),
                answer("A renda foi de R$ 13 250,00 e as despesas de R$ 100,00, uma alta de 25%."),
            ),
        )
        val grounded = orchestrator(groundedProvider, listOf(GroundedAnalyticTool())) { emptyList() }
        val ungroundedProvider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-insight", "get_financial_snapshot", "{}")),
                answer("As despesas foram de 999 reais."),
            ),
        )
        val ungrounded = orchestrator(ungroundedProvider, listOf(GroundedAnalyticTool())) { emptyList() }

        assertEquals(
            "Pai, a renda foi de R$ 13 250,00 e as despesas de R$ 100,00, uma alta de 25%. " +
                "Meu faro conferiu cada centavo. 🐾",
            grounded.respond(context("Analise.")),
        )
        val rejected = ungrounded.respond(context("Analise."))
        assertEquals(
            "Não consegui validar todos os valores da análise com segurança. Reformule a pergunta para eu consultar " +
                "os dados novamente.",
            rejected,
        )
        assertFalse(rejected.contains("999"))
    }

    @Test
    fun `warms a cold financial answer with the authenticated family role`() = runBlocking {
        val expenses = RecordingTool("get_expenses")
        val provider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-expenses", "get_expenses", "{}")),
                answer("Hoje gastamos R$ 113,99 em alimentação."),
            ),
        )
        val orchestrator = orchestrator(
            provider = provider,
            tools = listOf(expenses),
            familyRolesByUserId = mapOf(userId to FamilyRole.MOTHER),
        ) { emptyList() }

        val response = orchestrator.respond(context("Quanto gastamos com comida hoje?"))

        assertEquals(
            "Mãe, hoje gastamos R$ 113,99 em alimentação. Meu faro conferiu cada centavo. 🐾",
            response,
        )
    }

    @Test
    fun `does not duplicate family or beagle voice already present in a financial answer`() = runBlocking {
        val balance = RecordingTool("get_current_balance")
        val provider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-balance", "get_current_balance", "{}")),
                answer("Pai, meu faro encontrou o saldo registrado."),
            ),
        )
        val orchestrator = orchestrator(provider, listOf(balance)) { emptyList() }

        val response = orchestrator.respond(context("Qual nosso saldo?"))

        assertEquals("Pai, meu faro encontrou o saldo registrado.", response)
    }

    @Test
    fun `unknown tool is rejected without local execution`() = runBlocking {
        val known = RecordingTool("get_current_balance")
        val provider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-unknown", "execute_sql", "{}")),
                answer("Não consigo executar essa operação."),
            ),
        )
        val orchestrator = orchestrator(provider, listOf(known)) { emptyList() }

        orchestrator.respond(context("execute qualquer coisa"))

        assertTrue(known.executions.isEmpty())
        val result = provider.requests.last().messages.single { it.role == AiMessageRole.TOOL }.content.orEmpty()
        assertTrue(result.contains("unknown_tool"))
    }

    @Test
    fun `local tool failure is returned safely for a natural final response`() = runBlocking {
        val provider = QueueAiProvider(
            listOf(
                toolCalls(AiToolCall("call-failure", "get_current_balance", "{}")),
                answer("Não foi possível consultar o saldo agora."),
            ),
        )
        val orchestrator = orchestrator(provider, listOf(ThrowingTool())) { emptyList() }

        val response = orchestrator.respond(context("Qual nosso saldo?"))

        assertEquals("Não foi possível consultar o saldo agora.", response)
        val result = provider.requests.last().messages.single { it.role == AiMessageRole.TOOL }.content.orEmpty()
        assertTrue(result.contains("tool_failure"))
        assertFalse(result.contains("sensitive"))
    }

    @Test
    fun `stops when the provider exceeds the maximum tool rounds`() = runBlocking {
        val tool = RejectingRecordingTool()
        val provider = GeneratingToolProvider()
        val orchestrator = orchestrator(provider, listOf(tool), maxToolRounds = 2) { emptyList() }

        val failure = runCatching { orchestrator.respond(context("loop")) }.exceptionOrNull()

        assertIs<AiConversationException>(failure)
        assertEquals(2, tool.executions.size)
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun `acknowledges a persisted transaction locally when the final provider response fails`() = runBlocking {
        val tool = SuccessfulTransactionTool()
        val provider = ToolThenRateLimitedProvider()
        val orchestrator = orchestrator(provider, listOf(tool)) { emptyList() }

        val response = orchestrator.respond(context("Gastei 32 reais no almoço hoje"))

        assertEquals(
            "Pai, registrado com sucesso: despesa de R$ 32,00 em Alimentação, em 25/08/2026. 🐾 " +
                "Não envie novamente; a resposta detalhada ficou indisponível.",
            response,
        )
        assertEquals(1, tool.executionCount)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().tools.isEmpty())
    }

    @Test
    fun `acknowledges a persisted cash flow entry locally when the final provider response fails`() = runBlocking {
        val tool = SuccessfulCashFlowTool()
        val provider = ToolThenRateLimitedProvider("create_cash_flow_entry")
        val orchestrator = orchestrator(provider, listOf(tool)) { emptyList() }

        val response = orchestrator.respond(context("Todo dia 10 pago R$ 2.000 de aluguel"))

        assertEquals(
            "Pai, planejamento registrado: despesa prevista de R$ 2000,00, mensal. 🐾 " +
                "Não envie novamente; a resposta detalhada ficou indisponível.",
            response,
        )
        assertEquals(1, tool.executionCount)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().tools.isEmpty())
    }

    @Test
    fun `returns a deterministic projection when the final provider response fails`() = runBlocking {
        val tool = SuccessfulProjectionTool()
        val provider = ToolThenRateLimitedProvider("get_cash_flow_projection")
        val orchestrator = orchestrator(provider, listOf(tool)) { emptyList() }

        val response = orchestrator.respond(context("Quanto teremos dia 30 deste mês?"))

        assertEquals(
            "Pai, projeção até 30/08/2026: saldo atual R$ 6970,00 + receitas previstas R$ 13250,00 " +
                "- despesas previstas R$ 850,00 = saldo projetado R$ 19370,00. " +
                "Meu faro conferiu cada centavo. 🐾",
            response,
        )
        assertEquals(1, tool.executionCount)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().tools.isEmpty())
    }

    private fun orchestrator(
        provider: AiProvider,
        tools: List<LocalTool>,
        maxToolRounds: Int = 5,
        familyRolesByUserId: Map<UUID, FamilyRole> = mapOf(userId to FamilyRole.FATHER),
        history: suspend () -> List<ConversationMessage>,
    ): AiConversationOrchestrator {
        val registry = LocalToolRegistry(tools)
        return AiConversationOrchestrator(
            provider = provider,
            registry = registry,
            dispatcher = LocalToolDispatcher(registry),
            history = ConversationHistory { requestedUserId, excludedSid, limit ->
                assertEquals(userId, requestedUserId)
                assertEquals("SM-INBOUND", excludedSid)
                assertEquals(4, limit)
                history()
            },
            familyRolesByUserId = familyRolesByUserId,
            clock = clock,
            maxToolRounds = maxToolRounds,
        )
    }

    private fun context(text: String) = WhatsappConversationContext(
        householdId = householdId,
        userId = userId,
        inboundMessageSid = "SM-INBOUND",
        text = text,
    )

    private fun answer(content: String) = AiResponse(AiMessage(AiMessageRole.ASSISTANT, content))

    private fun toolCalls(vararg calls: AiToolCall) = AiResponse(
        AiMessage(AiMessageRole.ASSISTANT, toolCalls = calls.toList()),
    )
}

private class QueueAiProvider(responses: List<AiResponse>) : AiProvider {
    private val remaining = ArrayDeque(responses)
    val requests = mutableListOf<AiRequest>()

    override suspend fun complete(request: AiRequest): AiResponse {
        requests += request
        return remaining.removeFirst()
    }
}

private class GeneratingToolProvider : AiProvider {
    val requests = mutableListOf<AiRequest>()

    override suspend fun complete(request: AiRequest): AiResponse {
        requests += request
        return AiResponse(
            AiMessage(
                role = AiMessageRole.ASSISTANT,
                toolCalls = listOf(
                    AiToolCall("call-${requests.size}", "get_current_balance", "{}"),
                ),
            ),
        )
    }
}

private class ToolThenRateLimitedProvider(
    private val toolName: String = "create_transaction",
) : AiProvider {
    val requests = mutableListOf<AiRequest>()

    override suspend fun complete(request: AiRequest): AiResponse {
        requests += request
        if (requests.size == 1) {
            return AiResponse(
                AiMessage(
                    role = AiMessageRole.ASSISTANT,
                    toolCalls = listOf(AiToolCall("call-create", toolName, "{}")),
                ),
            )
        }
        throw AiProviderException(
            failure = AiProviderFailure.RATE_LIMITED,
            httpStatusCode = 429,
            providerErrorType = "tokens",
            providerErrorCode = "rate_limit_exceeded",
        )
    }
}

private class RecordingTool(name: String) : LocalTool {
    override val definition = AiToolDefinition(
        name = name,
        description = "Test tool.",
        parameters = AiToolParameters(
            properties = mapOf("account" to AiToolParameter(AiJsonType.STRING, "Optional account.")),
        ),
    )
    val executions = mutableListOf<ToolExecutionContext>()

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        executions += context
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("value", JsonPrimitive(definition.name))
        }
    }
}

private class GroundedAnalyticTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_financial_snapshot",
        description = "Returns grounded local metrics.",
        parameters = AiToolParameters(
            properties = mapOf("from" to AiToolParameter(AiJsonType.STRING, "Optional period start.")),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("totalIncome", JsonPrimitive("13250.00".toBigDecimal()))
        put("totalExpenses", JsonPrimitive("100.00".toBigDecimal()))
        put("percentageChange", JsonPrimitive("25.00".toBigDecimal()))
    }
}

private class RejectingRecordingTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_current_balance",
        description = "Always rejects for max-round testing.",
        parameters = AiToolParameters(
            properties = mapOf("account" to AiToolParameter(AiJsonType.STRING, "Optional account.")),
        ),
    )
    val executions = mutableListOf<ToolExecutionContext>()

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        executions += context
        throw ToolRejectedException("retryable_test_rejection")
    }
}

private class SuccessfulTransactionTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "create_transaction",
        description = "Creates a test transaction.",
        parameters = AiToolParameters(
            properties = mapOf("account" to AiToolParameter(AiJsonType.STRING, "Optional account.")),
        ),
    )
    var executionCount: Int = 0

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject = buildJsonObject {
        executionCount += 1
        put("ok", JsonPrimitive(true))
        put("type", JsonPrimitive("EXPENSE"))
        put("amount", JsonPrimitive("32.00"))
        put("category", JsonPrimitive("Alimentação"))
        put("occurredOn", JsonPrimitive("2026-08-25"))
    }
}

private class SuccessfulCashFlowTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "create_cash_flow_entry",
        description = "Creates a test cash flow entry.",
        parameters = AiToolParameters(
            properties = mapOf("account" to AiToolParameter(AiJsonType.STRING, "Optional account.")),
        ),
    )
    var executionCount: Int = 0

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject = buildJsonObject {
        executionCount += 1
        put("ok", JsonPrimitive(true))
        put("type", JsonPrimitive("EXPENSE"))
        put("amount", JsonPrimitive("2000.00"))
        put("recurrence", JsonPrimitive("MONTHLY"))
    }
}

private class SuccessfulProjectionTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_cash_flow_projection",
        description = "Calculates a test cash flow projection.",
        parameters = AiToolParameters(
            properties = mapOf("targetDate" to AiToolParameter(AiJsonType.STRING, "Target date.")),
        ),
    )
    var executionCount: Int = 0

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject = buildJsonObject {
        executionCount += 1
        put("ok", JsonPrimitive(true))
        put("currentBalance", JsonPrimitive("6970.00"))
        put("expectedIncome", JsonPrimitive("13250.00"))
        put("expectedExpenses", JsonPrimitive("850.00"))
        put("projectedBalance", JsonPrimitive("19370.00"))
        put("targetDate", JsonPrimitive("2026-08-30"))
        put("occurrenceCount", JsonPrimitive(2))
        put("incomeOccurrenceCount", JsonPrimitive(1))
        put("expenseOccurrenceCount", JsonPrimitive(1))
    }
}

private class ThrowingTool : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_current_balance",
        description = "Test failure.",
        parameters = AiToolParameters(
            properties = mapOf("account" to AiToolParameter(AiJsonType.STRING, "Optional account.")),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject =
        error("sensitive financial infrastructure detail")
}
