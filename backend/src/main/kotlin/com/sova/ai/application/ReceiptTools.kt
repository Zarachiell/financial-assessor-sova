package com.sova.ai.application

import com.sova.receipt.application.ReceiptAccessLinkSigner
import com.sova.receipt.application.SettlementReceiptService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

fun receiptLocalTools(
    service: SettlementReceiptService,
    linkSigner: ReceiptAccessLinkSigner,
): List<LocalTool> = listOf(
    LinkSettlementReceiptTool(service),
    GetSettlementReceiptsTool(service, linkSigner),
)

class LinkSettlementReceiptTool(
    private val service: SettlementReceiptService,
) : LocalTool {
    override val definition = AiToolDefinition(
        name = "link_settlement_receipt",
        description = "Vincula a imagem atual ou um candidato de imagem a uma liquidação financeira existente.",
        parameters = AiToolParameters(
            properties = mapOf(
                "settlementId" to AiToolParameter(
                    AiJsonType.STRING,
                    "Identificador retornado por settle_cash_flow_occurrence ou get_cash_flow_entries.",
                ),
                "imageAttachmentId" to AiToolParameter(
                    AiJsonType.STRING,
                    "Imagem retornada por get_image_candidates; omita para usar a imagem atual.",
                ),
                "caption" to AiToolParameter(AiJsonType.STRING, "Legenda curta opcional do comprovante."),
            ),
            required = setOf("settlementId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val requestedAttachmentId = arguments["imageAttachmentId"]?.jsonPrimitive?.contentOrNull?.let(UUID::fromString)
        val attachmentId = requestedAttachmentId ?: context.attachmentId
            ?: throw ToolRejectedException("image_attachment_required")
        if (context.attachmentId != null && context.attachmentId != attachmentId) {
            throw ToolRejectedException("image_attachment_mismatch")
        }
        val creation = service.link(
            context.financialContext,
            UUID.fromString(arguments.getValue("settlementId").jsonPrimitive.content),
            attachmentId,
            arguments["caption"]?.jsonPrimitive?.contentOrNull,
        ) ?: throw ToolRejectedException("settlement_or_image_not_found")
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("created", JsonPrimitive(creation.created))
            put("receiptId", JsonPrimitive(creation.receipt.id.toString()))
            put("settlementId", JsonPrimitive(creation.receipt.settlementId.toString()))
            put("imageAttachmentId", JsonPrimitive(creation.receipt.attachmentId.toString()))
            put("sha256", JsonPrimitive(creation.receipt.sha256Hex))
            put("contentType", JsonPrimitive(creation.receipt.contentType))
            put("sizeBytes", JsonPrimitive(creation.receipt.sizeBytes))
            put("linkedAt", JsonPrimitive(creation.receipt.linkedAt.toString()))
        }
    }
}

class GetSettlementReceiptsTool(
    private val service: SettlementReceiptService,
    private val linkSigner: ReceiptAccessLinkSigner,
) : LocalTool {
    override val definition = AiToolDefinition(
        name = "get_settlement_receipts",
        description = "Lista comprovantes vinculados a uma liquidação e cria links temporários de acesso.",
        parameters = AiToolParameters(
            properties = mapOf(
                "settlementId" to AiToolParameter(
                    AiJsonType.STRING,
                    "Identificador retornado por get_cash_flow_entries.",
                ),
            ),
            required = setOf("settlementId"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): JsonObject {
        val settlementId = UUID.fromString(arguments.getValue("settlementId").jsonPrimitive.content)
        val receipts = service.list(context.financialContext, settlementId)
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(receipts.size))
            put(
                "receipts",
                buildJsonArray {
                    receipts.forEach { receipt ->
                        add(
                            buildJsonObject {
                                put("receiptId", JsonPrimitive(receipt.id.toString()))
                                put("settlementId", JsonPrimitive(receipt.settlementId.toString()))
                                receipt.caption?.let { put("caption", JsonPrimitive(it)) }
                                put("contentType", JsonPrimitive(receipt.contentType))
                                put("sizeBytes", JsonPrimitive(receipt.sizeBytes))
                                put("sha256", JsonPrimitive(receipt.sha256Hex))
                                put("linkedAt", JsonPrimitive(receipt.linkedAt.toString()))
                                put("accessUrl", JsonPrimitive(linkSigner.create(receipt.id)))
                            },
                        )
                    }
                },
            )
        }
    }
}
