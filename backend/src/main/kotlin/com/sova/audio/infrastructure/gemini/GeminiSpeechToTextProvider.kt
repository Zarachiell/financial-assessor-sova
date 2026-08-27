package com.sova.audio.infrastructure.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.Blob
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.HttpOptions
import com.google.genai.kotlin.types.HttpRetryOptions
import com.google.genai.kotlin.types.MediaModality
import com.google.genai.kotlin.types.Part
import com.google.genai.kotlin.types.Schema
import com.google.genai.kotlin.types.Type
import com.sova.audio.application.AudioFailure
import com.sova.audio.application.AudioInput
import com.sova.audio.application.SpeechToTextContext
import com.sova.audio.application.SpeechToTextException
import com.sova.audio.application.SpeechToTextProvider
import com.sova.audio.application.TranscriptionResult
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

class GeminiSpeechToTextProvider internal constructor(
    private val project: String,
    private val location: String,
    private val model: String,
    private val client: GeminiAudioClient,
) : SpeechToTextProvider, AutoCloseable {
    constructor(
        project: String,
        location: String,
        model: String,
    ) : this(project, location, model, GoogleGeminiAudioClient(project, location))

    init {
        require(project.isNotBlank()) { "Google Cloud project must not be blank" }
        require(location.isNotBlank()) { "Google Cloud location must not be blank" }
        require(model.isNotBlank()) { "Gemini audio model must not be blank" }
        require(client.enterprise) { "Gemini audio client must use the Google Cloud enterprise backend" }
    }

    override suspend fun transcribe(
        audio: AudioInput,
        context: SpeechToTextContext,
    ): TranscriptionResult {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val response = client.transcribe(
                GeminiAudioRequest(
                    model = model,
                    bytes = audio.bytes,
                    mimeType = context.mimeType,
                    languageHint = context.languageHint,
                ),
            )
            val payload = JSON.parseToJsonElement(response.text).jsonObject
            val transcription = payload["text"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Gemini transcription did not contain text")
            val audioTokens = response.audioInputTokens
                ?: throw IllegalArgumentException("Gemini transcription did not report audio input tokens")
            require(audioTokens >= 0) { "Gemini audio token count must not be negative" }
            TranscriptionResult(
                text = transcription,
                provider = PROVIDER_NAME,
                model = response.model.ifBlank { model },
                durationMs = audioTokens * 1_000L / TOKENS_PER_AUDIO_SECOND,
                language = context.languageHint,
            ).also { result ->
                LOGGER.atInfo()
                    .addKeyValue("provider", PROVIDER_NAME)
                    .addKeyValue("model", result.model)
                    .addKeyValue("project", project)
                    .addKeyValue("location", location)
                    .addKeyValue("audioInputTokens", audioTokens)
                    .addKeyValue("audioDurationMs", result.durationMs)
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .addKeyValue("status", "success")
                    .log("Gemini speech-to-text interaction processed")
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: SpeechToTextException) {
            throw cause
        } catch (cause: GenAiApiException) {
            throw SpeechToTextException(cause.toAudioFailure(), cause)
        } catch (cause: HttpRequestTimeoutException) {
            throw SpeechToTextException(AudioFailure.STT_TIMEOUT, cause)
        } catch (cause: ConnectTimeoutException) {
            throw SpeechToTextException(AudioFailure.STT_TIMEOUT, cause)
        } catch (cause: SocketTimeoutException) {
            throw SpeechToTextException(AudioFailure.STT_TIMEOUT, cause)
        } catch (cause: SerializationException) {
            throw SpeechToTextException(AudioFailure.STT_INVALID_RESPONSE, cause)
        } catch (cause: IllegalArgumentException) {
            throw SpeechToTextException(AudioFailure.STT_INVALID_RESPONSE, cause)
        } catch (cause: Exception) {
            throw SpeechToTextException(AudioFailure.STT_UNAVAILABLE, cause)
        }
    }

    override fun close() {
        client.close()
    }

    internal companion object {
        const val PROVIDER_NAME = "gemini"
        const val REQUEST_TIMEOUT_MILLIS = 60_000
        const val MAX_OUTPUT_TOKENS = 8_192
        const val TOKENS_PER_AUDIO_SECOND = 32L
        val RETRYABLE_HTTP_STATUSES = listOf(408, 429, 500, 502, 503, 504)
        val JSON = Json { ignoreUnknownKeys = true }
        val LOGGER = LoggerFactory.getLogger("com.sova.audio.gemini")
    }
}

internal data class GeminiAudioRequest(
    val model: String,
    val bytes: ByteArray,
    val mimeType: String,
    val languageHint: String,
)

internal data class GeminiAudioResponse(
    val text: String,
    val model: String,
    val audioInputTokens: Long?,
)

internal interface GeminiAudioClient : AutoCloseable {
    val enterprise: Boolean

    suspend fun transcribe(request: GeminiAudioRequest): GeminiAudioResponse

    override fun close() = Unit
}

private class GoogleGeminiAudioClient(
    project: String,
    location: String,
) : GeminiAudioClient {
    private val delegate = Client(
        project = project,
        location = location,
        enterprise = true,
        httpOptions = HttpOptions(
            apiVersion = "v1",
            timeout = GeminiSpeechToTextProvider.REQUEST_TIMEOUT_MILLIS,
            retryOptions = HttpRetryOptions(
                attempts = 1,
                initialDelay = 0.5,
                maxDelay = 0.5,
                expBase = 1.0,
                jitter = 0.0,
                httpStatusCodes = GeminiSpeechToTextProvider.RETRYABLE_HTTP_STATUSES,
            ),
        ),
    )

    override val enterprise: Boolean = delegate.enterprise

    override suspend fun transcribe(request: GeminiAudioRequest): GeminiAudioResponse {
        val response = delegate.models.generateContent(
            model = request.model,
            contents = listOf(request.toGeminiContent()),
            config = transcriptionConfig(request.languageHint),
        )
        val candidate = response.candidates?.firstOrNull()
            ?: throw IllegalArgumentException("Gemini transcription response did not contain a candidate")
        val responseText = candidate.content?.parts.orEmpty()
            .filterNot { part -> part.thought == true }
            .mapNotNull(Part::text)
            .joinToString("")
            .takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Gemini transcription response did not contain text")
        val audioTokens = response.usageMetadata?.promptTokensDetails
            ?.firstOrNull { detail -> detail.modality == MediaModality.AUDIO }
            ?.tokenCount
            ?.toLong()
        return GeminiAudioResponse(
            text = responseText,
            model = response.modelVersion.orEmpty(),
            audioInputTokens = audioTokens,
        )
    }

    override fun close() {
        delegate.close()
    }
}

internal fun GeminiAudioRequest.toGeminiContent(): Content = Content(
    role = "user",
    parts = listOf(
        Part(
            text = """
                Transcreva fielmente somente a fala deste áudio em $languageHint.
                Não traduza, não resuma e não acrescente comentários.
                Se não houver fala inteligível, retorne o campo text vazio.
            """.trimIndent(),
        ),
        Part(
            inlineData = Blob(
                data = bytes,
                displayName = "whatsapp-audio",
                mimeType = mimeType,
            ),
        ),
    ),
)

private fun transcriptionConfig(languageHint: String): GenerateContentConfig = GenerateContentConfig(
    temperature = 0.0,
    maxOutputTokens = GeminiSpeechToTextProvider.MAX_OUTPUT_TOKENS,
    responseMimeType = "application/json",
    responseSchema = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "text" to Schema(
                type = Type.STRING,
                description = "Transcrição fiel da fala em $languageHint; vazio quando não houver fala inteligível.",
            ),
        ),
        required = listOf("text"),
    ),
)

private fun GenAiApiException.toAudioFailure(): AudioFailure = when (code) {
    408 -> AudioFailure.STT_TIMEOUT
    429 -> AudioFailure.STT_RATE_LIMITED
    in 500..599 -> AudioFailure.STT_UNAVAILABLE
    else -> AudioFailure.STT_REJECTED
}
