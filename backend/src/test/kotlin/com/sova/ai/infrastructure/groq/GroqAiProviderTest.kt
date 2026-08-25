package com.sova.ai.infrastructure.groq

import com.sova.ai.application.AiJsonType
import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiProviderFailure
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiToolDefinition
import com.sova.ai.application.AiToolParameter
import com.sova.ai.application.AiToolParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroqAiProviderTest {
    @Test
    fun `serializes local tools over fixed HTTPS and converts tool calls`() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedRequest = request
                respond(
                    content = toolCallResponse,
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            },
        )
        val response = GroqAiProvider("test-secret", "test-model", client).use { provider ->
            provider.complete(request())
        }

        assertEquals("call-1", response.message.toolCalls.single().id)
        assertEquals("get_current_balance", response.message.toolCalls.single().name)
        assertEquals("{}", response.message.toolCalls.single().arguments)

        val sent = requireNotNull(capturedRequest)
        assertEquals("https", sent.url.protocol.name)
        assertEquals("api.groq.com", sent.url.host)
        assertEquals("/openai/v1/chat/completions", sent.url.encodedPath)
        assertEquals("Bearer test-secret", sent.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(sent.body.text()).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content)
        val function = body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals("get_current_balance", function.getValue("name").jsonPrimitive.content)
        val parameters = function.getValue("parameters").jsonObject
        assertFalse(parameters.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
        val properties = parameters.getValue("properties").jsonObject
        assertTrue("account" in properties)
        assertFalse("userId" in properties)
        assertFalse("householdId" in properties)
    }

    @Test
    fun `returns an assistant answer without a tool call`() = runBlocking {
        val client = clientResponding(
            """
            {"choices":[{"message":{"role":"assistant","content":"Seu saldo registrado é R$ 10,00."}}]}
            """.trimIndent(),
        )

        val response = GroqAiProvider("test-secret", "test-model", client).use { it.complete(request()) }

        assertEquals("Seu saldo registrado é R$ 10,00.", response.message.content)
        assertTrue(response.message.toolCalls.isEmpty())
    }

    @Test
    fun `maps rate limit and server responses without exposing provider bodies`() = runBlocking {
        val rateLimited = providerFailure(HttpStatusCode.TooManyRequests, "sensitive rate response")
        val unavailable = providerFailure(HttpStatusCode.BadGateway, "sensitive server response")

        assertEquals(AiProviderFailure.RATE_LIMITED, rateLimited.failure)
        assertEquals(AiProviderFailure.UNAVAILABLE, unavailable.failure)
        assertFalse(rateLimited.message.orEmpty().contains("sensitive"))
        assertFalse(unavailable.message.orEmpty().contains("sensitive"))
    }

    @Test
    fun `rejects malformed provider JSON as an invalid response`() = runBlocking {
        val client = clientResponding("not-json")

        val exception = runCatching {
            GroqAiProvider("test-secret", "test-model", client).use { it.complete(request()) }
        }.exceptionOrNull()

        assertIs<AiProviderException>(exception)
        assertEquals(AiProviderFailure.INVALID_RESPONSE, exception.failure)
    }

    private suspend fun providerFailure(status: HttpStatusCode, body: String): AiProviderException {
        val exception = runCatching {
            GroqAiProvider("test-secret", "test-model", clientResponding(body, status)).use {
                it.complete(request())
            }
        }.exceptionOrNull()
        return assertIs(exception)
    }

    private fun request() = AiRequest(
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
                        "account" to AiToolParameter(AiJsonType.STRING, "Nome opcional da conta."),
                    ),
                ),
            ),
        ),
    )

    private fun clientResponding(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient = HttpClient(
        MockEngine {
            respond(content = content, status = status, headers = jsonHeaders)
        },
    )

    private fun OutgoingContent.text(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> error("Expected a byte-array request body")
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val toolCallResponse = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "get_current_balance", "arguments": "{}"}
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
