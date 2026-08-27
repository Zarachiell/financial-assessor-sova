package com.sova.ai.infrastructure.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.FunctionCall
import com.google.genai.kotlin.types.FunctionDeclaration
import com.google.genai.kotlin.types.FunctionResponse
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.GenerateContentResponse
import com.google.genai.kotlin.types.HttpOptions
import com.google.genai.kotlin.types.HttpRetryOptions
import com.google.genai.kotlin.types.Part
import com.google.genai.kotlin.types.Schema
import com.google.genai.kotlin.types.Tool
import com.google.genai.kotlin.types.Type
import com.sova.ai.application.AiFinishReason
import com.sova.ai.application.AiJsonType
import com.sova.ai.application.AiMessage
import com.sova.ai.application.AiMessageRole
import com.sova.ai.application.AiProvider
import com.sova.ai.application.AiProviderException
import com.sova.ai.application.AiProviderFailure
import com.sova.ai.application.AiRequest
import com.sova.ai.application.AiResponse
import com.sova.ai.application.AiResponseMetadata
import com.sova.ai.application.AiToolCall
import com.sova.ai.application.AiToolDefinition
import com.sova.ai.application.AiUsage
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.time.TimeSource

class GeminiAiProvider internal constructor(
    private val project: String,
    private val location: String,
    private val model: String,
    private val client: GeminiContentClient,
) : AiProvider {
    constructor(
        project: String,
        location: String,
        model: String,
    ) : this(
        project = project,
        location = location,
        model = model,
        client = GoogleGeminiContentClient(project, location),
    )

    init {
        require(project.isNotBlank()) { "Google Cloud project must not be blank" }
        require(location.isNotBlank()) { "Google Cloud location must not be blank" }
        require(model.isNotBlank()) { "Gemini model must not be blank" }
        require(client.enterprise) { "Gemini client must use the Google Cloud enterprise backend" }
    }

    override suspend fun complete(request: AiRequest): AiResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val response = client.generateContent(
                model = model,
                contents = request.toGeminiContents(),
                config = request.toGeminiConfig(),
            )
            response.toAiResponse(model).also { normalized ->
                val usage = normalized.metadata?.usage
                LOGGER.atInfo()
                    .addKeyValue("provider", PROVIDER_NAME)
                    .addKeyValue("model", model)
                    .addKeyValue("project", project)
                    .addKeyValue("location", location)
                    .addKeyValue("inputTokens", usage?.inputTokens)
                    .addKeyValue("outputTokens", usage?.outputTokens)
                    .addKeyValue("totalTokens", usage?.totalTokens)
                    .addKeyValue("finishReason", normalized.metadata?.finishReason?.name?.lowercase())
                    .addKeyValue("toolCalls", normalized.message.toolCalls.size)
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .addKeyValue("status", "success")
                    .log("Gemini interaction processed")
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: AiProviderException) {
            throw cause
        } catch (cause: GenAiApiException) {
            throw cause.toProviderException().also { failure ->
                LOGGER.atWarn()
                    .addKeyValue("provider", PROVIDER_NAME)
                    .addKeyValue("model", model)
                    .addKeyValue("project", project)
                    .addKeyValue("location", location)
                    .addKeyValue("httpStatus", cause.code)
                    .addKeyValue("providerStatus", cause.status.safeDiagnosticValue())
                    .addKeyValue("failure", failure.failure.name.lowercase())
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .addKeyValue("status", "failed")
                    .log("Gemini interaction failed")
            }
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

    private fun AiRequest.toGeminiConfig(): GenerateContentConfig {
        val systemPrompt = messages
            .filter { message -> message.role == AiMessageRole.SYSTEM }
            .mapNotNull(AiMessage::content)
            .joinToString("\n\n")
            .takeIf(String::isNotBlank)
        return GenerateContentConfig(
            systemInstruction = systemPrompt?.let(Content::fromText),
            temperature = TEMPERATURE,
            maxOutputTokens = MAX_OUTPUT_TOKENS,
            tools = tools
                .takeIf(List<*>::isNotEmpty)
                ?.let { definitions -> listOf(Tool(functionDeclarations = definitions.map { it.toGemini() })) },
        )
    }

    private fun AiRequest.toGeminiContents(): List<Content> {
        val nonSystemMessages = messages.filterNot { message -> message.role == AiMessageRole.SYSTEM }
        val toolCallsById = nonSystemMessages
            .flatMap(AiMessage::toolCalls)
            .associateBy(AiToolCall::id)
        val contents = mutableListOf<Content>()
        var index = 0
        while (index < nonSystemMessages.size) {
            val message = nonSystemMessages[index]
            if (message.role != AiMessageRole.TOOL) {
                contents += message.toGeminiContent()
                index += 1
                continue
            }
            val functionResponses = mutableListOf<Part>()
            while (index < nonSystemMessages.size && nonSystemMessages[index].role == AiMessageRole.TOOL) {
                val toolMessage = nonSystemMessages[index]
                val toolCallId = requireNotNull(toolMessage.toolCallId)
                val toolCall = requireNotNull(toolCallsById[toolCallId]) {
                    "Tool result does not have a matching assistant tool call"
                }
                functionResponses += Part(
                    functionResponse = FunctionResponse(
                        id = toolCallId,
                        name = toolCall.name,
                        response = toolMessage.content.toGeminiToolResponse(),
                    ),
                )
                index += 1
            }
            contents += Content(role = GEMINI_USER_ROLE, parts = functionResponses)
        }
        require(contents.isNotEmpty()) { "Gemini request requires at least one non-system message" }
        return contents
    }

    private fun AiMessage.toGeminiContent(): Content = when (role) {
        AiMessageRole.USER -> Content.fromText(requireNotNull(content), GEMINI_USER_ROLE)
        AiMessageRole.ASSISTANT -> Content(
            role = GEMINI_MODEL_ROLE,
            parts = buildList {
                content?.takeIf(String::isNotBlank)?.let { text -> add(Part(text = text)) }
                toolCalls.forEach { call ->
                    add(
                        Part(
                            functionCall = FunctionCall(
                                id = call.id,
                                name = call.name,
                                args = call.arguments.toJsonObject(),
                            ),
                            thoughtSignature = call.continuationContext?.decodeBase64(),
                        ),
                    )
                }
            }.also { parts -> require(parts.isNotEmpty()) { "Assistant message must not be empty" } },
        )
        AiMessageRole.SYSTEM -> error("System messages must be mapped to systemInstruction")
        AiMessageRole.TOOL -> error("Tool messages must be grouped as function responses")
    }

    private fun AiToolDefinition.toGemini(): FunctionDeclaration = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = parameters.properties.mapValues { (parameterName, parameter) ->
                Schema(
                    type = when (parameter.type) {
                        AiJsonType.STRING -> Type.STRING
                        AiJsonType.NUMBER -> Type.NUMBER
                        AiJsonType.INTEGER -> Type.INTEGER
                    },
                    description = parameter.description,
                    enum = parameter.enumValues.takeIf(List<*>::isNotEmpty),
                    nullable = true.takeUnless { parameterName in parameters.required },
                )
            },
            required = parameters.required.toList(),
        ),
    )

    internal companion object {
        const val PROVIDER_NAME = "gemini"
        const val GEMINI_USER_ROLE = "user"
        const val GEMINI_MODEL_ROLE = "model"
        const val REQUEST_TIMEOUT_MILLIS = 20_000
        const val MAX_OUTPUT_TOKENS = 512
        const val TEMPERATURE = 0.1
        val RETRYABLE_HTTP_STATUSES = listOf(408, 429, 500, 502, 503, 504)
        val JSON = Json { ignoreUnknownKeys = true }
        val LOGGER = LoggerFactory.getLogger("com.sova.ai.gemini")
    }
}

internal interface GeminiContentClient : AutoCloseable {
    val enterprise: Boolean

    suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig,
    ): GenerateContentResponse

    override fun close() = Unit
}

private class GoogleGeminiContentClient(
    project: String,
    location: String,
) : GeminiContentClient {
    private val delegate = Client(
        project = project,
        location = location,
        enterprise = true,
        httpOptions = HttpOptions(
            apiVersion = "v1",
            timeout = GeminiAiProvider.REQUEST_TIMEOUT_MILLIS,
            retryOptions = HttpRetryOptions(
                attempts = 3,
                initialDelay = 0.5,
                maxDelay = 2.0,
                expBase = 2.0,
                jitter = 0.25,
                httpStatusCodes = GeminiAiProvider.RETRYABLE_HTTP_STATUSES,
            ),
        ),
    )

    override val enterprise: Boolean = delegate.enterprise

    override suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig,
    ): GenerateContentResponse = delegate.models.generateContent(model, contents, config)

    override fun close() {
        delegate.close()
    }
}

private fun GenerateContentResponse.toAiResponse(configuredModel: String): AiResponse {
    val candidate = candidates?.firstOrNull()
        ?: throw IllegalArgumentException("Gemini response did not contain a candidate")
    val parts = candidate.content?.parts.orEmpty()
    val responseText = parts
        .filterNot { part -> part.thought == true }
        .mapNotNull(Part::text)
        .joinToString("")
        .takeIf(String::isNotBlank)
    val responseIdPrefix = responseId?.takeIf(String::isNotBlank) ?: "gemini-call"
    val calls = parts.mapIndexedNotNull { index, part ->
        part.functionCall?.toAiToolCall(responseIdPrefix, index, part.thoughtSignature)
    }
    if (responseText == null && calls.isEmpty()) {
        throw IllegalArgumentException("Gemini response did not contain text or function calls")
    }
    val usage = usageMetadata?.let { metadata ->
        AiUsage(
            inputTokens = metadata.promptTokenCount,
            outputTokens = metadata.candidatesTokenCount,
            totalTokens = metadata.totalTokenCount,
        )
    }
    return AiResponse(
        message = AiMessage(
            role = AiMessageRole.ASSISTANT,
            content = responseText,
            toolCalls = calls,
        ),
        metadata = AiResponseMetadata(
            provider = "gemini",
            model = modelVersion?.takeIf(String::isNotBlank) ?: configuredModel,
            usage = usage,
            finishReason = candidate.finishReason?.value.toAiFinishReason(),
        ),
    )
}

private fun FunctionCall.toAiToolCall(
    responseIdPrefix: String,
    index: Int,
    thoughtSignature: ByteArray?,
): AiToolCall {
    val functionName = name?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Gemini function call did not contain a name")
    return AiToolCall(
        id = id?.takeIf(String::isNotBlank) ?: "$responseIdPrefix-${index + 1}",
        name = functionName,
        arguments = JsonObject(args.orEmpty()).toString(),
        continuationContext = thoughtSignature?.let(Base64.getEncoder()::encodeToString),
    )
}

private fun String?.toGeminiToolResponse(): Map<String, JsonElement> {
    val raw = requireNotNull(this)
    val parsed = runCatching { GeminiAiProvider.JSON.parseToJsonElement(raw) }
        .getOrElse { JsonPrimitive(raw) }
    return if (parsed is JsonObject) parsed else mapOf("output" to parsed)
}

private fun String.toJsonObject(): Map<String, JsonElement> {
    val parsed = GeminiAiProvider.JSON.parseToJsonElement(this)
    require(parsed is JsonObject) { "Tool call arguments must be a JSON object" }
    return parsed
}

private fun String.decodeBase64(): ByteArray = try {
    Base64.getDecoder().decode(this)
} catch (cause: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid AI continuation context", cause)
}

private fun String?.toAiFinishReason(): AiFinishReason? = when (this) {
    null, "FINISH_REASON_UNSPECIFIED" -> null
    "STOP" -> AiFinishReason.STOP
    "MAX_TOKENS" -> AiFinishReason.MAX_TOKENS
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
        AiFinishReason.SAFETY
    "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS" ->
        AiFinishReason.INVALID_TOOL_CALL
    else -> AiFinishReason.OTHER
}

private fun GenAiApiException.toProviderException(): AiProviderException {
    val failure = when (code) {
        401 -> AiProviderFailure.AUTHENTICATION
        403 -> AiProviderFailure.AUTHORIZATION
        404 -> AiProviderFailure.MODEL_NOT_FOUND
        408 -> AiProviderFailure.TIMEOUT
        429 -> if (message.orEmpty().contains("quota", ignoreCase = true)) {
            AiProviderFailure.QUOTA_EXCEEDED
        } else {
            AiProviderFailure.RATE_LIMITED
        }
        in 400..499 -> AiProviderFailure.REJECTED_REQUEST
        else -> AiProviderFailure.UNAVAILABLE
    }
    return AiProviderException(
        failure = failure,
        httpStatusCode = code,
        providerErrorType = status.safeDiagnosticValue(),
    )
}

private fun String.safeDiagnosticValue(): String? =
    takeIf { value -> value.length <= 64 && value.matches(Regex("^[A-Za-z0-9._-]+$")) }
