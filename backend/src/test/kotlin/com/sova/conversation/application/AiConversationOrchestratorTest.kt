package com.sova.conversation.application

import com.sova.ai.application.AiJsonType
import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
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
        assertTrue(systemPrompt.contains("fonte da verdade financeira"))
        assertTrue(systemPrompt.contains("única forma autorizada"))
        assertTrue(systemPrompt.contains("hoje = 2026-08-25"))
        assertEquals(
            listOf(AiMessageRole.SYSTEM, AiMessageRole.USER, AiMessageRole.ASSISTANT, AiMessageRole.USER),
            messages.map { it.role },
        )
        assertEquals("sim", messages.last().content)
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

        assertEquals("O saldo e as despesas foram consultados localmente.", result)
        assertEquals(1, balance.executions.size)
        assertEquals(1, expenses.executions.size)
        assertEquals(householdId, balance.executions.single().financialContext.householdId)
        assertEquals(userId, balance.executions.single().financialContext.userId)
        assertEquals("SM-INBOUND", balance.executions.single().inboundMessageSid)
        val toolMessages = provider.requests.last().messages.filter { it.role == AiMessageRole.TOOL }
        assertEquals(listOf("call-balance", "call-expenses"), toolMessages.map { it.toolCallId })
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
        val tool = RecordingTool("get_current_balance")
        val provider = GeneratingToolProvider()
        val orchestrator = orchestrator(provider, listOf(tool), maxToolRounds = 2) { emptyList() }

        val failure = runCatching { orchestrator.respond(context("loop")) }.exceptionOrNull()

        assertIs<AiConversationException>(failure)
        assertEquals(2, tool.executions.size)
        assertEquals(3, provider.requests.size)
    }

    private fun orchestrator(
        provider: AiProvider,
        tools: List<LocalTool>,
        maxToolRounds: Int = 5,
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
                assertEquals(6, limit)
                history()
            },
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
