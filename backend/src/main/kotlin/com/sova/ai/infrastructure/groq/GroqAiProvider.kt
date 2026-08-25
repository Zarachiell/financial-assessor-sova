package com.sova.ai.infrastructure.groq

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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GroqAiProvider(
    private val apiKey: String,
    private val model: String,
    private val client: HttpClient = defaultHttpClient(),
    private val json: Json = DEFAULT_JSON,
) : AiProvider, AutoCloseable {
    init {
        require(apiKey.isNotBlank()) { "Groq API key must not be blank" }
        require(model.isNotBlank()) { "Groq model must not be blank" }
    }

    override suspend fun complete(request: AiRequest): AiResponse {
        val payload = request.toGroqRequest(model)
        try {
            val response = client.post(CHAT_COMPLETIONS_URL) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(payload))
            }
            val responseBody = response.bodyAsText()
            when {
                response.status == HttpStatusCode.TooManyRequests ->
                    fail(AiProviderFailure.RATE_LIMITED, response.status, responseBody)
                response.status.value in 400..499 ->
                    fail(AiProviderFailure.REJECTED_REQUEST, response.status, responseBody)
                response.status.value >= 500 ->
                    fail(AiProviderFailure.UNAVAILABLE, response.status, responseBody)
                response.status.value !in 200..299 ->
                    fail(AiProviderFailure.UNAVAILABLE, response.status, responseBody)
            }
            return json.decodeFromString<GroqChatResponse>(responseBody).toAiResponse()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: AiProviderException) {
            throw cause
        } catch (cause: HttpRequestTimeoutException) {
            throw AiProviderException(AiProviderFailure.TIMEOUT, cause)
        } catch (cause: ConnectTimeoutException) {
            throw AiProviderException(AiProviderFailure.TIMEOUT, cause)
        } catch (cause: SocketTimeoutException) {
            throw AiProviderException(AiProviderFailure.TIMEOUT, cause)
        } catch (cause: SerializationException) {
            throw AiProviderException(AiProviderFailure.INVALID_RESPONSE, cause)
        } catch (cause: IllegalArgumentException) {
            throw AiProviderException(AiProviderFailure.INVALID_RESPONSE, cause)
        } catch (cause: Exception) {
            throw AiProviderException(AiProviderFailure.UNAVAILABLE, cause)
        }
    }

    override fun close() {
        client.close()
    }

    private fun fail(
        failure: AiProviderFailure,
        status: HttpStatusCode,
        responseBody: String,
    ): Nothing {
        val error = json.safeErrorMetadata(responseBody)
        throw AiProviderException(
            failure = failure,
            httpStatusCode = status.value,
            providerErrorType = error.type,
            providerErrorCode = error.code,
        )
    }

    private companion object {
        const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val REQUEST_TIMEOUT_MILLIS = 20_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val SOCKET_TIMEOUT_MILLIS = 20_000L
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
        }
    }
}

private data class GroqErrorMetadata(
    val type: String? = null,
    val code: String? = null,
)

private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 64
private val SAFE_DIAGNOSTIC_VALUE = Regex("^[A-Za-z0-9._-]+$")

private fun Json.safeErrorMetadata(responseBody: String): GroqErrorMetadata {
    val error = runCatching {
        parseToJsonElement(responseBody).jsonObject["error"]?.jsonObject
    }.getOrNull() ?: return GroqErrorMetadata()
    return GroqErrorMetadata(
        type = error["type"].safeDiagnosticValue(),
        code = error["code"].safeDiagnosticValue(),
    )
}

private fun JsonElement?.safeDiagnosticValue(): String? = runCatching {
    this?.jsonPrimitive?.contentOrNull
        ?.takeIf { value ->
            value.length <= MAX_DIAGNOSTIC_VALUE_LENGTH && SAFE_DIAGNOSTIC_VALUE.matches(value)
        }
}.getOrNull()

@Serializable
private data class GroqChatRequest(
    val model: String,
    val messages: List<GroqRequestMessage>,
    val tools: List<GroqToolDefinition>,
    @SerialName("tool_choice") val toolChoice: String = "auto",
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean = true,
    val temperature: Double = 0.1,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int = 1_024,
)

@Serializable
private data class GroqRequestMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<GroqToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
private data class GroqToolDefinition(
    val type: String = "function",
    val function: GroqFunctionDefinition,
)

@Serializable
private data class GroqFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class GroqToolCall(
    val id: String,
    val type: String = "function",
    val function: GroqFunctionCall,
)

@Serializable
private data class GroqFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
private data class GroqChatResponse(val choices: List<GroqChoice>)

@Serializable
private data class GroqChoice(val message: GroqResponseMessage)

@Serializable
private data class GroqResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<GroqToolCall> = emptyList(),
)

private fun AiRequest.toGroqRequest(model: String): GroqChatRequest = GroqChatRequest(
    model = model,
    messages = messages.map(AiMessage::toGroqMessage),
    tools = tools.map(AiToolDefinition::toGroqDefinition),
)

private fun AiMessage.toGroqMessage(): GroqRequestMessage = GroqRequestMessage(
    role = role.wireName,
    content = content,
    toolCalls = toolCalls.takeIf(List<*>::isNotEmpty)?.map { call ->
        GroqToolCall(
            id = call.id,
            function = GroqFunctionCall(call.name, call.arguments),
        )
    },
    toolCallId = toolCallId,
)

private fun AiToolDefinition.toGroqDefinition(): GroqToolDefinition = GroqToolDefinition(
    function = GroqFunctionDefinition(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    parameters.properties.forEach { (name, parameter) ->
                        put(
                            name,
                            buildJsonObject {
                                put("type", JsonPrimitive(parameter.type.wireName))
                                put("description", JsonPrimitive(parameter.description))
                                if (parameter.enumValues.isNotEmpty()) {
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            parameter.enumValues.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
            put(
                "required",
                buildJsonArray {
                    parameters.required.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("additionalProperties", JsonPrimitive(false))
        },
    ),
)

private fun GroqChatResponse.toAiResponse(): AiResponse {
    val response = choices.firstOrNull()?.message
        ?: throw IllegalArgumentException("Groq response did not contain a choice")
    require(response.role == AiMessageRole.ASSISTANT.wireName) { "Groq response role must be assistant" }
    return AiResponse(
        AiMessage(
            role = AiMessageRole.ASSISTANT,
            content = response.content,
            toolCalls = response.toolCalls.map { call ->
                require(call.type == "function") { "Groq returned an unsupported tool call type" }
                AiToolCall(call.id, call.function.name, call.function.arguments)
            },
        ),
    )
}
