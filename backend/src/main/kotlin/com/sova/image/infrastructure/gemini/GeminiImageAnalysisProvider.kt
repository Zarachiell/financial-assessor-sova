package com.sova.image.infrastructure.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.Blob
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.HttpOptions
import com.google.genai.kotlin.types.HttpRetryOptions
import com.google.genai.kotlin.types.Part
import com.google.genai.kotlin.types.Schema
import com.google.genai.kotlin.types.Type
import com.sova.image.application.ImageAnalysisContext
import com.sova.image.application.ImageAnalysisException
import com.sova.image.application.ImageAnalysisProvider
import com.sova.image.application.ImageAnalysisResult
import com.sova.image.application.ImageDocumentType
import com.sova.image.application.ImageFailure
import com.sova.image.application.ImageInput
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.time.TimeSource

class GeminiImageAnalysisProvider internal constructor(
    private val project: String,
    private val location: String,
    private val model: String,
    private val client: GeminiImageClient,
) : ImageAnalysisProvider, AutoCloseable {
    constructor(project: String, location: String, model: String) : this(
        project,
        location,
        model,
        GoogleGeminiImageClient(project, location),
    )

    init {
        require(project.isNotBlank()) { "Google Cloud project must not be blank" }
        require(location.isNotBlank()) { "Google Cloud location must not be blank" }
        require(model.isNotBlank()) { "Gemini image model must not be blank" }
        require(client.enterprise) { "Gemini image client must use the Google Cloud enterprise backend" }
    }

    override suspend fun analyze(image: ImageInput, context: ImageAnalysisContext): ImageAnalysisResult {
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val response = client.analyze(
                GeminiImageRequest(model, image.bytes, image.mimeType, context),
            )
            response.toAnalysisResult(model).also { result ->
                LOGGER.atInfo()
                    .addKeyValue("provider", PROVIDER_NAME)
                    .addKeyValue("model", result.model)
                    .addKeyValue("project", project)
                    .addKeyValue("location", location)
                    .addKeyValue("confidence", result.confidence)
                    .addKeyValue("durationMs", startedAt.elapsedNow().inWholeMilliseconds)
                    .addKeyValue("status", "success")
                    .log("Gemini image interaction processed")
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: ImageAnalysisException) {
            throw cause
        } catch (cause: GenAiApiException) {
            throw ImageAnalysisException(cause.toImageFailure(), cause)
        } catch (cause: HttpRequestTimeoutException) {
            throw ImageAnalysisException(ImageFailure.VISION_TIMEOUT, cause)
        } catch (cause: ConnectTimeoutException) {
            throw ImageAnalysisException(ImageFailure.VISION_TIMEOUT, cause)
        } catch (cause: SocketTimeoutException) {
            throw ImageAnalysisException(ImageFailure.VISION_TIMEOUT, cause)
        } catch (cause: SerializationException) {
            throw ImageAnalysisException(ImageFailure.VISION_INVALID_RESPONSE, cause)
        } catch (cause: IllegalArgumentException) {
            throw ImageAnalysisException(ImageFailure.VISION_INVALID_RESPONSE, cause)
        } catch (cause: Exception) {
            throw ImageAnalysisException(ImageFailure.VISION_UNAVAILABLE, cause)
        }
    }

    override fun close() = client.close()

    internal companion object {
        const val PROVIDER_NAME = "gemini"
        const val REQUEST_TIMEOUT_MILLIS = 60_000
        const val MAX_OUTPUT_TOKENS = 1_024
        val RETRYABLE_HTTP_STATUSES = listOf(408, 429, 500, 502, 503, 504)
        val JSON = Json { ignoreUnknownKeys = true }
        val LOGGER = LoggerFactory.getLogger("com.sova.image.gemini")
    }
}

internal data class GeminiImageRequest(
    val model: String,
    val bytes: ByteArray,
    val mimeType: String,
    val context: ImageAnalysisContext,
)

internal data class GeminiImageResponse(val text: String, val model: String)

internal interface GeminiImageClient : AutoCloseable {
    val enterprise: Boolean
    suspend fun analyze(request: GeminiImageRequest): GeminiImageResponse
    override fun close() = Unit
}

private class GoogleGeminiImageClient(project: String, location: String) : GeminiImageClient {
    private val delegate = Client(
        project = project,
        location = location,
        enterprise = true,
        httpOptions = HttpOptions(
            apiVersion = "v1",
            timeout = GeminiImageAnalysisProvider.REQUEST_TIMEOUT_MILLIS,
            retryOptions = HttpRetryOptions(
                attempts = 1,
                initialDelay = 0.5,
                maxDelay = 0.5,
                expBase = 1.0,
                jitter = 0.0,
                httpStatusCodes = GeminiImageAnalysisProvider.RETRYABLE_HTTP_STATUSES,
            ),
        ),
    )

    override val enterprise: Boolean = delegate.enterprise

    override suspend fun analyze(request: GeminiImageRequest): GeminiImageResponse {
        val response = delegate.models.generateContent(
            model = request.model,
            contents = listOf(request.toGeminiContent()),
            config = imageAnalysisConfig(),
        )
        val text = response.candidates?.firstOrNull()?.content?.parts.orEmpty()
            .filterNot { it.thought == true }
            .mapNotNull(Part::text)
            .joinToString("")
            .takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Gemini image response did not contain text")
        return GeminiImageResponse(text, response.modelVersion.orEmpty())
    }

    override fun close() = delegate.close()
}

internal fun GeminiImageRequest.toGeminiContent(): Content = Content(
    role = "user",
    parts = listOf(
        Part(
            text = """
                Analise esta imagem financeira em português do Brasil como dado candidato, sem inventar campos.
                Extraia o tipo do documento, estabelecimento/beneficiário, valor total, data civil e categoria sugerida.
                Use data ISO YYYY-MM-DD e valor decimal positivo em string com ponto. Se um campo não estiver legível,
                retorne null e reduza sua confiança. A confiança de cada campo e a geral devem estar entre 0 e 1.
                Data atual: ${context.currentDate}.
                Categorias permitidas: ${context.knownCategories.joinToString()}.
                Legenda enviada pelo usuário: ${context.caption.ifBlank { "sem legenda" }}.
                A legenda ajuda no contexto, mas não substitui informação visual ausente.
            """.trimIndent(),
        ),
        Part(inlineData = Blob(data = bytes, displayName = "whatsapp-image", mimeType = mimeType)),
    ),
)

private fun imageAnalysisConfig(): GenerateContentConfig = GenerateContentConfig(
    temperature = 0.0,
    maxOutputTokens = GeminiImageAnalysisProvider.MAX_OUTPUT_TOKENS,
    responseMimeType = "application/json",
    responseSchema = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "documentType" to Schema(type = Type.STRING, enum = ImageDocumentType.entries.map(Enum<*>::name)),
            "merchant" to Schema(type = Type.STRING, nullable = true),
            "amount" to Schema(type = Type.STRING, nullable = true),
            "occurredOn" to Schema(type = Type.STRING, nullable = true),
            "suggestedCategory" to Schema(type = Type.STRING, nullable = true),
            "confidence" to Schema(type = Type.NUMBER),
            "merchantConfidence" to Schema(type = Type.NUMBER, nullable = true),
            "amountConfidence" to Schema(type = Type.NUMBER, nullable = true),
            "dateConfidence" to Schema(type = Type.NUMBER, nullable = true),
            "categoryConfidence" to Schema(type = Type.NUMBER, nullable = true),
        ),
        required = listOf(
            "documentType",
            "merchant",
            "amount",
            "occurredOn",
            "suggestedCategory",
            "confidence",
            "merchantConfidence",
            "amountConfidence",
            "dateConfidence",
            "categoryConfidence",
        ),
    ),
)

private fun GeminiImageResponse.toAnalysisResult(fallbackModel: String): ImageAnalysisResult {
    val payload = GeminiImageAnalysisProvider.JSON.parseToJsonElement(text).jsonObject
    return ImageAnalysisResult(
        documentType = ImageDocumentType.valueOf(payload.requiredText("documentType")),
        merchant = payload.optionalText("merchant")?.trim()?.takeIf(String::isNotEmpty),
        amount = payload.optionalText("amount")?.toBigDecimal(),
        occurredOn = payload.optionalText("occurredOn")?.let(LocalDate::parse),
        suggestedCategory = payload.optionalText("suggestedCategory")?.trim()?.takeIf(String::isNotEmpty),
        confidence = payload.requiredDecimal("confidence"),
        merchantConfidence = payload.optionalDecimal("merchantConfidence"),
        amountConfidence = payload.optionalDecimal("amountConfidence"),
        dateConfidence = payload.optionalDecimal("dateConfidence"),
        categoryConfidence = payload.optionalDecimal("categoryConfidence"),
        provider = GeminiImageAnalysisProvider.PROVIDER_NAME,
        model = model.ifBlank { fallbackModel },
    )
}

private fun JsonObject.requiredText(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalText(name: String): String? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.requiredDecimal(name: String): BigDecimal = getValue(name).jsonPrimitive.content.toBigDecimal()
private fun JsonObject.optionalDecimal(name: String): BigDecimal? = optionalText(name)?.toBigDecimal()

private fun GenAiApiException.toImageFailure(): ImageFailure = when (code) {
    408 -> ImageFailure.VISION_TIMEOUT
    429 -> ImageFailure.VISION_RATE_LIMITED
    in 500..599 -> ImageFailure.VISION_UNAVAILABLE
    else -> ImageFailure.VISION_REJECTED
}
