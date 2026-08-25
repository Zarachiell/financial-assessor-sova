package com.sova.ai.application

enum class AiMessageRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    init {
        require(id.isNotBlank()) { "Tool call id must not be blank" }
        require(name.isNotBlank()) { "Tool call name must not be blank" }
        require(arguments.isNotBlank()) { "Tool call arguments must not be blank" }
    }
}

data class AiMessage(
    val role: AiMessageRole,
    val content: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    init {
        if (role == AiMessageRole.TOOL) {
            require(!toolCallId.isNullOrBlank()) { "Tool messages require a tool call id" }
            require(content != null) { "Tool messages require content" }
        }
        if (toolCalls.isNotEmpty()) {
            require(role == AiMessageRole.ASSISTANT) { "Only assistant messages may contain tool calls" }
        }
    }
}

enum class AiJsonType(val wireName: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
}

data class AiToolParameter(
    val type: AiJsonType,
    val description: String,
    val enumValues: List<String> = emptyList(),
)

data class AiToolParameters(
    val properties: Map<String, AiToolParameter>,
    val required: Set<String> = emptySet(),
) {
    init {
        require(properties.isNotEmpty()) { "Tool parameters must define at least one property" }
        require(required.all(properties::containsKey)) { "Required tool parameters must exist in properties" }
    }
}

data class AiToolDefinition(
    val name: String,
    val description: String,
    val parameters: AiToolParameters,
) {
    init {
        require(name.matches(TOOL_NAME)) { "Invalid tool name" }
        require(description.isNotBlank()) { "Tool description must not be blank" }
    }

    private companion object {
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

data class AiRequest(
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition> = emptyList(),
) {
    init {
        require(messages.isNotEmpty()) { "AI request requires at least one message" }
    }
}

data class AiResponse(val message: AiMessage) {
    init {
        require(message.role == AiMessageRole.ASSISTANT) { "AI response must contain an assistant message" }
    }
}

fun interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}

enum class AiProviderFailure {
    TIMEOUT,
    RATE_LIMITED,
    REJECTED_REQUEST,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

class AiProviderException(
    val failure: AiProviderFailure,
    cause: Throwable? = null,
    val httpStatusCode: Int? = null,
    val providerErrorType: String? = null,
    val providerErrorCode: String? = null,
) : RuntimeException("AI provider failed: ${failure.name.lowercase()}", cause)
