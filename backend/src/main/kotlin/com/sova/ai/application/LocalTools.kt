package com.sova.ai.application

import com.sova.finance.domain.FinancialContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

data class ToolExecutionContext(
    val financialContext: FinancialContext,
    val inboundMessageSid: String,
)

interface LocalTool {
    val definition: AiToolDefinition

    suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject
}

data class ToolDispatchResult(
    val content: String,
    val successful: Boolean,
)

class LocalToolRegistry(tools: List<LocalTool>) {
    private val toolsByName = tools.associateBy(LocalTool::definitionName).also { byName ->
        require(byName.size == tools.size) { "Local tool names must be unique" }
    }

    val definitions: List<AiToolDefinition> = tools.map(LocalTool::definition)

    fun find(name: String): LocalTool? = toolsByName[name]
}

class LocalToolDispatcher(
    private val registry: LocalToolRegistry,
    private val json: Json = Json,
) {
    suspend fun dispatch(call: AiToolCall, context: ToolExecutionContext): ToolDispatchResult {
        val startedAt = TimeSource.Monotonic.markNow()
        val tool = registry.find(call.name)
        if (tool == null) {
            log(call.name, context, "unknown_tool", startedAt.elapsedNow().inWholeMilliseconds)
            return failure("unknown_tool")
        }

        val arguments = parseArguments(call.arguments)
            ?: return failure("invalid_arguments").also {
                log(call.name, context, "invalid_arguments", startedAt.elapsedNow().inWholeMilliseconds)
            }
        if (!tool.definition.parameters.accepts(arguments)) {
            log(call.name, context, "invalid_arguments", startedAt.elapsedNow().inWholeMilliseconds)
            return failure("invalid_arguments")
        }

        return try {
            val result = tool.execute(context, arguments)
            log(call.name, context, "success", startedAt.elapsedNow().inWholeMilliseconds)
            ToolDispatchResult(result.toString(), successful = true)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: ToolRejectedException) {
            log(call.name, context, cause.code, startedAt.elapsedNow().inWholeMilliseconds)
            ToolDispatchResult(toolFailure(cause.code, cause.details).toString(), successful = false)
        } catch (cause: IllegalArgumentException) {
            log(call.name, context, "invalid_arguments", startedAt.elapsedNow().inWholeMilliseconds)
            failure("invalid_arguments")
        } catch (cause: Exception) {
            LOGGER.atError()
                .setCause(cause)
                .addKeyValue("toolName", call.name)
                .addKeyValue("userId", context.financialContext.userId)
                .addKeyValue("householdId", context.financialContext.householdId)
                .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                .log("Local financial tool failed")
            failure("tool_failure")
        }
    }

    private fun parseArguments(rawArguments: String): JsonObject? = runCatching {
        json.parseToJsonElement(rawArguments) as? JsonObject
    }.getOrNull()

    private fun failure(code: String): ToolDispatchResult =
        ToolDispatchResult(toolFailure(code).toString(), successful = false)

    private fun log(
        toolName: String,
        context: ToolExecutionContext,
        status: String,
        durationMs: Long,
    ) {
        LOGGER.atInfo()
            .addKeyValue("toolName", toolName)
            .addKeyValue("status", status)
            .addKeyValue("userId", context.financialContext.userId)
            .addKeyValue("householdId", context.financialContext.householdId)
            .addKeyValue("durationMs", durationMs)
            .log("Local financial tool processed")
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("com.sova.ai.tools")
    }
}

class ToolRejectedException(
    val code: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : RuntimeException(code)

private fun LocalTool.definitionName(): String = definition.name

private fun AiToolParameters.accepts(arguments: JsonObject): Boolean {
    if (arguments.keys.any { it !in properties }) return false
    if (required.any { it !in arguments }) return false
    return arguments.all { (name, value) ->
        val parameter = properties.getValue(name)
        if (value is JsonNull) return@all name !in required
        val primitive = value as? JsonPrimitive ?: return@all false
        if (primitive.booleanOrNull != null) return@all false
        val matchesType = when (parameter.type) {
            AiJsonType.STRING -> primitive.isString
            AiJsonType.NUMBER -> !primitive.isString && primitive.doubleOrNull != null
            AiJsonType.INTEGER -> !primitive.isString && primitive.intOrNull != null
        }
        matchesType && (parameter.enumValues.isEmpty() || primitive.jsonPrimitive.content in parameter.enumValues)
    }
}

private fun toolFailure(code: String, details: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(false))
    put("error", JsonPrimitive(code))
    details.forEach { (name, value) -> put(name, value) }
}
