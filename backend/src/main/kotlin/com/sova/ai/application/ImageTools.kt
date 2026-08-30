package com.sova.ai.application

import com.sova.image.application.ImageCandidateService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class GetImageCandidatesTool(
    private val service: ImageCandidateService,
) : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_image_candidates",
        description = "Lista imagens financeiras recentes ainda aguardando confirmação ou uso.",
        parameters = AiToolParameters(
            properties = mapOf(
                "limit" to AiToolParameter(AiJsonType.INTEGER, "Máximo de candidatos entre 1 e 10."),
            ),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val candidates = service.listPending(
            context.financialContext,
            arguments["limit"]?.jsonPrimitive?.int ?: 5,
        )
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(candidates.size))
            put(
                "candidates",
                buildJsonArray {
                    candidates.forEach { candidate ->
                        add(
                            buildJsonObject {
                                put("attachmentId", JsonPrimitive(candidate.attachmentId.toString()))
                                put("documentType", JsonPrimitive(candidate.documentType.name))
                                candidate.merchant?.let { put("merchant", JsonPrimitive(it)) }
                                candidate.amount?.let { put("amount", JsonPrimitive(it)) }
                                candidate.occurredOn?.let { put("occurredOn", JsonPrimitive(it.toString())) }
                                candidate.suggestedCategory?.let { put("suggestedCategory", JsonPrimitive(it)) }
                                put("confidence", JsonPrimitive(candidate.confidence))
                                put("confirmationRequired", JsonPrimitive(candidate.confirmationRequired))
                                candidate.caption.takeIf(String::isNotBlank)?.let { put("caption", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        }
    }
}
