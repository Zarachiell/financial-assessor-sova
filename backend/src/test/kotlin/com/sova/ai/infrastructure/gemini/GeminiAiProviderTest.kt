package com.sova.ai.infrastructure.gemini

import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.Candidate
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.FinishReason
import com.google.genai.kotlin.types.FunctionCall
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.GenerateContentResponse
import com.google.genai.kotlin.types.GenerateContentResponseUsageMetadata
import com.google.genai.kotlin.types.Part
import com.sova.ai.application.AiFinishReason
import com.sova.ai.application.AiJsonType
import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiProviderFailure
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiToolCall
import com.sova.ai.application.AiToolDefinition
import com.sova.ai.application.AiToolParameter
import com.sova.ai.application.AiToolParameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiAiProviderTest {
    @Test
    fun `maps system prompt tools and Gemini function call without automatic execution`() = runBlocking {
        val signature = byteArrayOf(1, 2, 3, 4)
        val client = RecordingGeminiClient(
            response = GenerateContentResponse(
                responseId = "response-1",
                modelVersion = "gemini-3.5-flash-lite-001",
                candidates = listOf(
                    Candidate(
                        finishReason = FinishReason.STOP,
                        content = Content(
                            role = "model",
                            parts = listOf(
                                Part(
                                    functionCall = FunctionCall(
                                        id = "call-1",
                                        name = "get_current_balance",
                                        args = emptyMap(),
                                    ),
                                    thoughtSignature = signature,
                                ),
                            ),
                        ),
                    ),
                ),
                usageMetadata = GenerateContentResponseUsageMetadata(
                    promptTokenCount = 123,
                    candidatesTokenCount = 17,
                    totalTokenCount = 140,
                ),
            ),
        )

        val response = GeminiAiProvider("sova-test", "global", "gemini-test", client).use { provider ->
            provider.complete(initialRequest())
        }

        assertEquals("gemini-test", client.model)
        assertEquals("Use tools locais.", client.config?.systemInstruction?.parts?.single()?.text)
        assertEquals(listOf("user"), client.contents?.map(Content::role))
        assertEquals("Qual nosso saldo?", client.contents?.single()?.parts?.single()?.text)
        val declaration = client.config?.tools?.single()?.functionDeclarations?.single()
        assertNotNull(declaration)
        assertEquals("get_current_balance", declaration.name)
        assertFalse(declaration.parameters?.properties?.getValue("type")?.nullable == true)
        assertTrue(declaration.parameters?.properties?.getValue("account")?.nullable == true)
        assertEquals(listOf("type"), declaration.parameters?.required)

        val call = response.message.toolCalls.single()
        assertEquals("call-1", call.id)
        assertEquals("get_current_balance", call.name)
        assertEquals("{}", call.arguments)
        assertNotNull(call.continuationContext)
        assertEquals("gemini", response.metadata?.provider)
        assertEquals("gemini-3.5-flash-lite-001", response.metadata?.model)
        assertEquals(123, response.metadata?.usage?.inputTokens)
        assertEquals(17, response.metadata?.usage?.outputTokens)
        assertEquals(140, response.metadata?.usage?.totalTokens)
        assertEquals(AiFinishReason.STOP, response.metadata?.finishReason)
        assertTrue(client.closed)
    }

    @Test
    fun `returns tool results with matching id name and continuation context`() = runBlocking {
        val client = RecordingGeminiClient(textResponse("Registrado."))
        val continuationContext = "AQIDBA=="
        val request = AiRequest(
            messages = listOf(
                AiMessage(AiMessageRole.SYSTEM, "Use tools locais."),
                AiMessage(AiMessageRole.USER, "Registre a despesa."),
                AiMessage(
                    role = AiMessageRole.ASSISTANT,
                    toolCalls = listOf(
                        AiToolCall(
                            id = "call-1",
                            name = "create_transaction",
                            arguments = "{\"amount\":42}",
                            continuationContext = continuationContext,
                        ),
                    ),
                ),
                AiMessage(
                    role = AiMessageRole.TOOL,
                    content = "{\"successful\":true}",
                    toolCallId = "call-1",
                ),
            ),
        )

        val response = GeminiAiProvider("sova-test", "global", "gemini-test", client).use {
            it.complete(request)
        }

        assertEquals("Registrado.", response.message.content)
        assertEquals(listOf("user", "model", "user"), client.contents?.map(Content::role))
        val modelPart = client.contents?.get(1)?.parts?.single()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), modelPart?.thoughtSignature)
        val functionResponse = client.contents?.get(2)?.parts?.single()?.functionResponse
        assertEquals("call-1", functionResponse?.id)
        assertEquals("create_transaction", functionResponse?.name)
        assertEquals(true, (functionResponse?.response?.get("successful") as JsonPrimitive).content.toBoolean())
        assertNull(client.config?.tools)
    }

    @Test
    fun `groups parallel tool results into one Gemini user turn`() = runBlocking {
        val client = RecordingGeminiClient(textResponse("Resumo pronto."))
        val request = AiRequest(
            messages = listOf(
                AiMessage(AiMessageRole.USER, "Compare."),
                AiMessage(
                    role = AiMessageRole.ASSISTANT,
                    toolCalls = listOf(
                        AiToolCall("call-1", "get_expenses", "{}"),
                        AiToolCall("call-2", "get_income", "{}"),
                    ),
                ),
                AiMessage(AiMessageRole.TOOL, "{\"total\":20}", toolCallId = "call-1"),
                AiMessage(AiMessageRole.TOOL, "{\"total\":50}", toolCallId = "call-2"),
            ),
        )

        GeminiAiProvider("sova-test", "global", "gemini-test", client).use { it.complete(request) }

        val responseParts = client.contents?.last()?.parts.orEmpty()
        assertEquals(2, responseParts.size)
        assertEquals(listOf("call-1", "call-2"), responseParts.map { it.functionResponse?.id })
    }

    @Test
    fun `requires the explicit enterprise client backend`() {
        val client = RecordingGeminiClient(textResponse("Oi."), enterprise = false)

        assertFailsWith<IllegalArgumentException> {
            GeminiAiProvider("sova-test", "global", "gemini-test", client)
        }
    }

    @Test
    fun `maps authentication authorization model quota rate and server failures`() = runBlocking {
        assertFailure(401, "UNAUTHENTICATED", "invalid credentials", AiProviderFailure.AUTHENTICATION)
        assertFailure(403, "PERMISSION_DENIED", "permission denied", AiProviderFailure.AUTHORIZATION)
        assertFailure(404, "NOT_FOUND", "model missing", AiProviderFailure.MODEL_NOT_FOUND)
        assertFailure(429, "RESOURCE_EXHAUSTED", "quota exceeded", AiProviderFailure.QUOTA_EXCEEDED)
        assertFailure(429, "RESOURCE_EXHAUSTED", "temporarily busy", AiProviderFailure.RATE_LIMITED)
        assertFailure(503, "UNAVAILABLE", "provider unavailable", AiProviderFailure.UNAVAILABLE)
    }

    @Test
    fun `rejects a response without text or function calls`() = runBlocking {
        val client = RecordingGeminiClient(
            GenerateContentResponse(
                candidates = listOf(Candidate(content = Content(role = "model", parts = emptyList()))),
            ),
        )

        val exception = assertFailsWith<AiProviderException> {
            GeminiAiProvider("sova-test", "global", "gemini-test", client).use {
                it.complete(initialRequest())
            }
        }

        assertEquals(AiProviderFailure.INVALID_RESPONSE, exception.failure)
    }

    private suspend fun assertFailure(
        statusCode: Int,
        status: String,
        message: String,
        expected: AiProviderFailure,
    ) {
        val client = RecordingGeminiClient(failure = GenAiApiException(statusCode, status, message))
        val exception = runCatching {
            GeminiAiProvider("sova-test", "global", "gemini-test", client).use {
                it.complete(initialRequest())
            }
        }.exceptionOrNull()

        assertEquals(expected, assertIs<AiProviderException>(exception).failure)
        assertEquals(statusCode, exception.httpStatusCode)
        assertEquals(status, exception.providerErrorType)
        assertFalse(exception.message.orEmpty().contains(message))
    }

    private fun initialRequest(): AiRequest = AiRequest(
        messages = listOf(
            AiMessage(AiMessageRole.SYSTEM, "Use tools locais."),
            AiMessage(AiMessageRole.USER, "Qual nosso saldo?"),
        ),
        tools = listOf(
            AiToolDefinition(
                name = "get_current_balance",
                description = "Calcula o saldo localmente.",
                parameters = AiToolParameters(
                    properties = mapOf(
                        "account" to AiToolParameter(AiJsonType.STRING, "Conta opcional."),
                        "type" to AiToolParameter(
                            AiJsonType.STRING,
                            "Tipo obrigatório.",
                            enumValues = listOf("INCOME", "EXPENSE"),
                        ),
                    ),
                    required = setOf("type"),
                ),
            ),
        ),
    )

    private fun textResponse(text: String): GenerateContentResponse = GenerateContentResponse(
        candidates = listOf(
            Candidate(
                finishReason = FinishReason.STOP,
                content = Content(role = "model", parts = listOf(Part(text = text))),
            ),
        ),
    )
}

private class RecordingGeminiClient(
    private val response: GenerateContentResponse? = null,
    override val enterprise: Boolean = true,
    private val failure: Exception? = null,
) : GeminiContentClient {
    var model: String? = null
    var contents: List<Content>? = null
    var config: GenerateContentConfig? = null
    var closed: Boolean = false

    override suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig,
    ): GenerateContentResponse {
        this.model = model
        this.contents = contents
        this.config = config
        failure?.let { throw it }
        return requireNotNull(response)
    }

    override fun close() {
        closed = true
    }
}
