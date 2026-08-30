package com.sova.image.infrastructure.gemini

import com.sova.image.application.ImageAnalysisContext
import com.sova.image.application.ImageDocumentType
import com.sova.image.application.ImageInput
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiImageAnalysisProviderTest {
    @Test
    fun `maps structured Gemini candidate without identity or household data`() = runBlocking {
        val client = RecordingGeminiImageClient(
            GeminiImageResponse(
                """{"documentType":"RECEIPT","merchant":"Mercado Sova","amount":"87.45","occurredOn":"2026-08-30","suggestedCategory":"Alimentação","confidence":0.96,"merchantConfidence":0.95,"amountConfidence":0.99,"dateConfidence":0.94,"categoryConfidence":0.90}""",
                "gemini-image-001",
            ),
        )
        val result = GeminiImageAnalysisProvider("sova-test", "global", "gemini-image", client).use { provider ->
            provider.analyze(
                ImageInput(byteArrayOf(1, 2, 3), "image/png", "image.png"),
                ImageAnalysisContext("compra do mês", listOf("Alimentação", "Moradia"), LocalDate.of(2026, 8, 30)),
            )
        }

        assertEquals(ImageDocumentType.RECEIPT, result.documentType)
        assertEquals("Mercado Sova", result.merchant)
        assertEquals(BigDecimal("87.45"), result.amount)
        assertEquals(LocalDate.of(2026, 8, 30), result.occurredOn)
        assertEquals(BigDecimal("0.96"), result.confidence)
        assertEquals("gemini", result.provider)
        assertEquals("gemini-image-001", result.model)
        val request = requireNotNull(client.request)
        assertContentEquals(byteArrayOf(1, 2, 3), request.bytes)
        val prompt = request.toGeminiContent().parts?.first()?.text.orEmpty()
        assertTrue(prompt.contains("compra do mês"))
        assertTrue(prompt.contains("Alimentação"))
        assertFalse(prompt.contains("userId"))
        assertFalse(prompt.contains("householdId"))
    }
}

private class RecordingGeminiImageClient(private val response: GeminiImageResponse) : GeminiImageClient {
    override val enterprise: Boolean = true
    var request: GeminiImageRequest? = null
    override suspend fun analyze(request: GeminiImageRequest): GeminiImageResponse {
        this.request = request
        return response
    }
}
