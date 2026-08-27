package com.sova.audio.infrastructure.gemini

import com.sova.audio.application.AudioInput
import com.sova.audio.application.SpeechToTextContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeminiSpeechToTextProviderTest {
    @Test
    fun `sends only audio and transcription context through enterprise Gemini`() = runBlocking {
        val client = RecordingGeminiAudioClient(
            GeminiAudioResponse(
                text = """{"text":"Gastei 35 reais no almoço hoje."}""",
                model = "gemini-3.5-flash-lite-001",
                audioInputTokens = 320,
            ),
        )
        val result = GeminiSpeechToTextProvider(
            project = "sova-test",
            location = "global",
            model = "gemini-3.5-flash-lite",
            client = client,
        ).use { provider ->
            provider.transcribe(
                AudioInput(byteArrayOf(1, 2, 3), "audio/ogg", "audio.ogg"),
                SpeechToTextContext("pt-BR", "audio/ogg", "audio.ogg"),
            )
        }

        val request = requireNotNull(client.request)
        assertEquals("gemini-3.5-flash-lite", request.model)
        assertEquals("audio/ogg", request.mimeType)
        assertEquals("pt-BR", request.languageHint)
        assertContentEquals(byteArrayOf(1, 2, 3), request.bytes)
        assertEquals("Gastei 35 reais no almoço hoje.", result.text)
        assertEquals("gemini", result.provider)
        assertEquals("gemini-3.5-flash-lite-001", result.model)
        assertEquals(10_000, result.durationMs)
        assertEquals("pt-BR", result.language)
        assertNull(result.confidence)
    }

    @Test
    fun `builds inline audio content without identity or financial context`() {
        val request = GeminiAudioRequest(
            model = "gemini-test",
            bytes = byteArrayOf(9, 8, 7),
            mimeType = "audio/mpeg",
            languageHint = "pt-BR",
        )

        val content = request.toGeminiContent()

        assertEquals("user", content.role)
        assertEquals(2, content.parts?.size)
        val prompt = content.parts?.first()?.text.orEmpty()
        assertEquals(true, prompt.contains("pt-BR"))
        assertEquals(false, prompt.contains("userId"))
        assertEquals(false, prompt.contains("householdId"))
        val inline = requireNotNull(content.parts?.get(1)?.inlineData)
        assertEquals("audio/mpeg", inline.mimeType)
        assertContentEquals(byteArrayOf(9, 8, 7), inline.data)
    }
}

private class RecordingGeminiAudioClient(
    private val response: GeminiAudioResponse,
) : GeminiAudioClient {
    override val enterprise: Boolean = true
    var request: GeminiAudioRequest? = null

    override suspend fun transcribe(request: GeminiAudioRequest): GeminiAudioResponse {
        this.request = request
        return response
    }
}
