package com.sova.ai.infrastructure.groq

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
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
        val response = GroqAiProvider("test-secret", "openai/gpt-oss-120b", client).use { provider ->
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
        assertEquals("openai/gpt-oss-120b", body.getValue("model").jsonPrimitive.content)
        assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("hidden", body.getValue("reasoning_format").jsonPrimitive.content)
        assertEquals("512", body.getValue("max_completion_tokens").jsonPrimitive.content)
        val function = body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals("get_current_balance", function.getValue("name").jsonPrimitive.content)
        val parameters = function.getValue("parameters").jsonObject
        assertFalse(parameters.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
        val properties = parameters.getValue("properties").jsonObject
        assertTrue("account" in properties)
        assertEquals(
            listOf("string", "null"),
            properties.getValue("account").jsonObject.getValue("type").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("account", "type"),
            parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("INCOME", "EXPENSE", "null"),
            properties.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse("userId" in properties)
        assertFalse("householdId" in properties)
    }

    @Test
    fun `omits tool schemas when requesting only the final answer`() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedRequest = request
                respond(
                    content = """{"choices":[{"message":{"role":"assistant","content":"Registrado."}}]}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            },
        )

        GroqAiProvider("test-secret", "test-model", client).use { provider ->
            provider.complete(request().copy(tools = emptyList()))
        }

        val body = Json.parseToJsonElement(requireNotNull(capturedRequest).body.text()).jsonObject
        assertFalse("tools" in body)
        assertFalse("tool_choice" in body)
        assertFalse("parallel_tool_calls" in body)
        assertFalse("reasoning_effort" in body)
        assertFalse("reasoning_format" in body)
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
    fun `maps rate limit and server responses without exposing bodies through exceptions`() = runBlocking {
        val rateLimited = providerFailure(HttpStatusCode.TooManyRequests, "sensitive rate response")
        val unavailable = providerFailure(HttpStatusCode.BadGateway, "sensitive server response")

        assertEquals(AiProviderFailure.RATE_LIMITED, rateLimited.failure)
        assertEquals(AiProviderFailure.UNAVAILABLE, unavailable.failure)
        assertFalse(rateLimited.message.orEmpty().contains("sensitive"))
        assertFalse(unavailable.message.orEmpty().contains("sensitive"))
    }

    @Test
    fun `logs request sizing and safe rate limit error diagnostics`() = runBlocking {
        val apiKey = "test-secret-value"
        val responseBody =
            """{"error":{"message":"request too large; key=$apiKey","type":"tokens","code":"rate_limit_exceeded"}}"""
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedRequest = request
                respond(
                    content = responseBody,
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        "retry-after" to listOf("17"),
                        "x-ratelimit-limit-requests" to listOf("1000"),
                        "x-ratelimit-limit-tokens" to listOf("8000"),
                        "x-ratelimit-remaining-requests" to listOf("998"),
                        "x-ratelimit-remaining-tokens" to listOf("0"),
                        "x-ratelimit-reset-requests" to listOf("1h2m"),
                        "x-ratelimit-reset-tokens" to listOf("17s"),
                    ),
                )
            },
        )
        val logger = LoggerFactory.getLogger("com.sova.ai.groq") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val exception = runCatching {
                GroqAiProvider(apiKey, "openai/gpt-oss-20b", client).use { provider ->
                    provider.complete(request())
                }
            }.exceptionOrNull()
            assertEquals(AiProviderFailure.RATE_LIMITED, assertIs<AiProviderException>(exception).failure)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val sentBody = requireNotNull(capturedRequest).body.text()
        val sentJson = Json.parseToJsonElement(sentBody).jsonObject
        val requestLog = appender.list.single { event -> event.formattedMessage == "Groq request prepared" }
        val requestFields = requestLog.structuredFields()
        assertEquals("groq", requestFields["provider"])
        assertEquals("openai/gpt-oss-20b", requestFields["model"])
        assertEquals((sentBody.encodeToByteArray().size + 3) / 4, requestFields["estimatedInputTokens"])
        assertEquals("Use tools locais.".length, requestFields["systemPromptChars"])
        assertEquals(sentJson.getValue("tools").toString().length, requestFields["toolDefinitionsChars"])
        assertEquals(2, requestFields["conversationMessages"])

        val errorLog = appender.list.single { event -> event.formattedMessage == "Groq request failed" }
        val errorFields = errorLog.structuredFields()
        assertEquals(Level.WARN, errorLog.level)
        assertEquals(429, errorFields["httpStatus"])
        assertEquals("tokens", errorFields["providerErrorType"])
        assertEquals("rate_limit_exceeded", errorFields["providerErrorCode"])
        assertEquals(responseBody.length, errorFields["providerResponseBodyChars"])
        assertEquals(false, errorFields["providerResponseBodyTruncated"])
        assertTrue(errorFields["providerResponseBody"].toString().contains("request too large"))
        assertTrue(errorFields["providerResponseBody"].toString().contains("[REDACTED]"))
        assertFalse(errorFields["providerResponseBody"].toString().contains(apiKey))
        assertEquals("17", errorFields["retryAfter"])
        assertEquals("1000", errorFields["rateLimitLimitRequests"])
        assertEquals("8000", errorFields["rateLimitLimitTokens"])
        assertEquals("998", errorFields["rateLimitRemainingRequests"])
        assertEquals("0", errorFields["rateLimitRemainingTokens"])
        assertEquals("1h2m", errorFields["rateLimitResetRequests"])
        assertEquals("17s", errorFields["rateLimitResetTokens"])
    }

    @Test
    fun `retains only safe provider diagnostics from rejected responses`() = runBlocking {
        val rejected = providerFailure(
            HttpStatusCode.Forbidden,
            """
            {
              "error": {
                "message": "sensitive provider explanation",
                "type": "permission_error",
                "code": "model_not_permitted"
              }
            }
            """.trimIndent(),
        )

        assertEquals(AiProviderFailure.REJECTED_REQUEST, rejected.failure)
        assertEquals(403, rejected.httpStatusCode)
        assertEquals("permission_error", rejected.providerErrorType)
        assertEquals("model_not_permitted", rejected.providerErrorCode)
        assertFalse(rejected.message.orEmpty().contains("sensitive"))
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
                        "type" to AiToolParameter(
                            AiJsonType.STRING,
                            "Tipo opcional.",
                            enumValues = listOf("INCOME", "EXPENSE"),
                        ),
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

    private fun ILoggingEvent.structuredFields(): Map<String, Any?> =
        keyValuePairs.orEmpty().associate { field -> field.key to field.value }

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
