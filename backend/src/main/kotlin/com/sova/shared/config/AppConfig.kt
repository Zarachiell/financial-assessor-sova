package com.sova.shared.config

import com.sova.whatsapp.domain.WhatsappAddress
import java.math.BigDecimal
import java.net.URI

data class AppConfig(
    val http: HttpConfig,
    val database: DatabaseConfig,
    val twilio: TwilioConfig,
    val household: HouseholdProvisioningConfig,
    val ai: AiConfig,
    val cashFlow: CashFlowConfig,
    val audio: AudioConfig,
    val image: ImageConfig,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                http = HttpConfig(
                    host = environment["SOVA_HTTP_HOST"] ?: "0.0.0.0",
                    port = environment.optionalPort("SOVA_HTTP_PORT") ?: 8080,
                ),
                database = DatabaseConfig(
                    url = environment.required("DATABASE_URL"),
                    user = environment.required("DATABASE_USER"),
                    password = environment.required("DATABASE_PASSWORD"),
                ),
                twilio = TwilioConfig(
                    accountSid = environment.required("TWILIO_ACCOUNT_SID"),
                    authToken = environment.required("TWILIO_AUTH_TOKEN"),
                    whatsappSender = environment.requiredWhatsappAddress("TWILIO_WHATSAPP_SENDER"),
                ),
                household = HouseholdProvisioningConfig(
                    name = environment.required("SOVA_HOUSEHOLD_NAME"),
                    users = listOf(
                        ProvisionedUserConfig(
                            name = environment.required("SOVA_USER_A_NAME"),
                            whatsappAddress = environment.requiredWhatsappAddress("SOVA_USER_A_WHATSAPP"),
                        ),
                        ProvisionedUserConfig(
                            name = environment.required("SOVA_USER_B_NAME"),
                            whatsappAddress = environment.requiredWhatsappAddress("SOVA_USER_B_WHATSAPP"),
                        ),
                    ),
                ),
                ai = environment.aiConfig(),
                cashFlow = CashFlowConfig(
                    maxProjectionMonths = environment.optionalPositiveInt("SOVA_MAX_PROJECTION_MONTHS")
                        ?: DEFAULT_MAX_PROJECTION_MONTHS,
                ),
                audio = AudioConfig(
                    model = environment["GEMINI_AUDIO_MODEL"]?.takeIf(String::isNotBlank)
                        ?: environment["GEMINI_MODEL"]?.takeIf(String::isNotBlank)
                        ?: DEFAULT_GEMINI_MODEL,
                    maxAudioBytes = environment.optionalPositiveLong("SOVA_MAX_AUDIO_BYTES")
                        ?: DEFAULT_MAX_AUDIO_BYTES,
                    maxAudioDurationMinutes = environment.optionalPositiveInt("SOVA_MAX_AUDIO_DURATION_MINUTES")
                        ?: DEFAULT_MAX_AUDIO_DURATION_MINUTES,
                    maxAttempts = environment.optionalPositiveInt("SOVA_AUDIO_MAX_ATTEMPTS")
                        ?: DEFAULT_AUDIO_MAX_ATTEMPTS,
                ),
                image = ImageConfig(
                    model = environment["GEMINI_IMAGE_MODEL"]?.takeIf(String::isNotBlank)
                        ?: environment["GEMINI_MODEL"]?.takeIf(String::isNotBlank)
                        ?: DEFAULT_GEMINI_MODEL,
                    maxImageBytes = environment.optionalPositiveLong("SOVA_MAX_IMAGE_BYTES")
                        ?: DEFAULT_MAX_IMAGE_BYTES,
                    maxAttempts = environment.optionalPositiveInt("SOVA_IMAGE_MAX_ATTEMPTS")
                        ?: DEFAULT_IMAGE_MAX_ATTEMPTS,
                    minimumAutoConfidence = environment["SOVA_IMAGE_MIN_CONFIDENCE"]?.let { raw ->
                        raw.toBigDecimalOrNull()
                            ?: throw IllegalArgumentException("SOVA_IMAGE_MIN_CONFIDENCE must be a decimal number")
                    } ?: DEFAULT_IMAGE_MIN_CONFIDENCE,
                    storagePath = environment["SOVA_MEDIA_STORAGE_PATH"]?.takeIf(String::isNotBlank)
                        ?: DEFAULT_MEDIA_STORAGE_PATH,
                    publicBaseUrl = normalizePublicBaseUrl(
                        environment["SOVA_PUBLIC_BASE_URL"]?.takeIf(String::isNotBlank)
                            ?: environment["SOVA_ADDRESS"]?.takeIf(String::isNotBlank)
                            ?: "http://localhost",
                    ),
                    signingSecret = environment["SOVA_MEDIA_SIGNING_KEY"]?.takeIf(String::isNotBlank)
                        ?: environment.required("TWILIO_AUTH_TOKEN"),
                ),
            )

        private const val DEFAULT_MAX_PROJECTION_MONTHS = 24
        private const val DEFAULT_MAX_AUDIO_BYTES = 16L * 1024 * 1024
        private const val DEFAULT_MAX_AUDIO_DURATION_MINUTES = 5
        private const val DEFAULT_AUDIO_MAX_ATTEMPTS = 3
        private const val DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val DEFAULT_IMAGE_MAX_ATTEMPTS = 3
        private val DEFAULT_IMAGE_MIN_CONFIDENCE = BigDecimal("0.85")
        private const val DEFAULT_MEDIA_STORAGE_PATH = "/var/lib/sova/attachments"
    }
}

data class HttpConfig(
    val host: String,
    val port: Int,
)

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

data class TwilioConfig(
    val accountSid: String,
    val authToken: String,
    val whatsappSender: String,
)

data class GeminiAiConfig(
    val project: String,
    val location: String,
    val model: String,
    val credentialsPath: String,
)

data class AiConfig(
    val settings: GeminiAiConfig,
    val maxToolIterations: Int,
) {
    init {
        require(maxToolIterations in 1..MAX_TOOL_ITERATIONS) {
            "Maximum tool iterations must be between 1 and $MAX_TOOL_ITERATIONS"
        }
    }

    private companion object {
        const val MAX_TOOL_ITERATIONS = 20
    }
}

data class CashFlowConfig(
    val maxProjectionMonths: Int,
) {
    init {
        require(maxProjectionMonths in 1..MAX_PROJECTION_MONTHS) {
            "Maximum projection months must be between 1 and $MAX_PROJECTION_MONTHS"
        }
    }

    private companion object {
        const val MAX_PROJECTION_MONTHS = 120
    }
}

data class AudioConfig(
    val model: String,
    val maxAudioBytes: Long,
    val maxAudioDurationMinutes: Int,
    val maxAttempts: Int,
) {
    init {
        require(model.isNotBlank()) { "Gemini audio model must not be blank" }
        require(maxAudioBytes in 1..MAX_PROVIDER_UPLOAD_BYTES) {
            "Maximum audio size must be between 1 byte and $MAX_PROVIDER_UPLOAD_BYTES bytes"
        }
        require(maxAudioDurationMinutes in 1..MAX_AUDIO_DURATION_MINUTES) {
            "Maximum audio duration must be between 1 and $MAX_AUDIO_DURATION_MINUTES minutes"
        }
        require(maxAttempts in 1..MAX_AUDIO_ATTEMPTS) {
            "Maximum audio attempts must be between 1 and $MAX_AUDIO_ATTEMPTS"
        }
    }

    private companion object {
        const val MAX_PROVIDER_UPLOAD_BYTES = 25L * 1024 * 1024
        const val MAX_AUDIO_DURATION_MINUTES = 30
        const val MAX_AUDIO_ATTEMPTS = 10
    }
}

data class ImageConfig(
    val model: String,
    val maxImageBytes: Long,
    val maxAttempts: Int,
    val minimumAutoConfidence: BigDecimal,
    val storagePath: String,
    val publicBaseUrl: String,
    val signingSecret: String,
) {
    init {
        require(model.isNotBlank()) { "Gemini image model must not be blank" }
        require(maxImageBytes in 1..MAX_PROVIDER_UPLOAD_BYTES) {
            "Maximum image size must be between 1 byte and $MAX_PROVIDER_UPLOAD_BYTES bytes"
        }
        require(maxAttempts in 1..MAX_IMAGE_ATTEMPTS) {
            "Maximum image attempts must be between 1 and $MAX_IMAGE_ATTEMPTS"
        }
        require(minimumAutoConfidence in BigDecimal.ZERO..BigDecimal.ONE) {
            "Minimum image confidence must be between zero and one"
        }
        require(storagePath.isNotBlank()) { "Media storage path must not be blank" }
        require(publicBaseUrl.isNotBlank()) { "Media public base URL must not be blank" }
        require(signingSecret.isNotBlank()) { "Media signing key must not be blank" }
    }

    private companion object {
        const val MAX_PROVIDER_UPLOAD_BYTES = 25L * 1024 * 1024
        const val MAX_IMAGE_ATTEMPTS = 10
    }
}

data class HouseholdProvisioningConfig(
    val name: String,
    val users: List<ProvisionedUserConfig>,
) {
    init {
        require(users.size == EXPECTED_USER_COUNT) { "Exactly two users must be configured" }
        require(users.map { WhatsappAddress(it.whatsappAddress).identityKey }.distinct().size == EXPECTED_USER_COUNT) {
            "Configured WhatsApp addresses must be distinct"
        }
    }

    private companion object {
        const val EXPECTED_USER_COUNT = 2
    }
}

data class ProvisionedUserConfig(
    val name: String,
    val whatsappAddress: String,
)

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

private fun Map<String, String>.aiConfig(): AiConfig {
    val configuredProvider = get("AI_PROVIDER")?.takeIf(String::isNotBlank)
    require(configuredProvider == null || configuredProvider.equals("gemini", ignoreCase = true)) {
        "Environment variable AI_PROVIDER must be gemini"
    }
    return AiConfig(
        settings = GeminiAiConfig(
            project = required("GOOGLE_CLOUD_PROJECT"),
            location = get("GOOGLE_CLOUD_LOCATION")?.takeIf(String::isNotBlank) ?: DEFAULT_GOOGLE_CLOUD_LOCATION,
            model = get("GEMINI_MODEL")?.takeIf(String::isNotBlank) ?: DEFAULT_GEMINI_MODEL,
            credentialsPath = required("GOOGLE_APPLICATION_CREDENTIALS"),
        ),
        maxToolIterations = optionalPositiveInt("MAX_TOOL_ITERATIONS") ?: DEFAULT_MAX_TOOL_ITERATIONS,
    )
}

private fun Map<String, String>.optionalPort(name: String): Int? =
    get(name)?.let { rawValue ->
        rawValue.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: error("Environment variable $name must be a port between 1 and 65535")
    }

private fun Map<String, String>.optionalPositiveInt(name: String): Int? =
    get(name)?.let { rawValue ->
        rawValue.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: error("Environment variable $name must be a positive integer")
    }

private fun Map<String, String>.optionalPositiveLong(name: String): Long? =
    get(name)?.let { rawValue ->
        rawValue.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: error("Environment variable $name must be a positive integer")
    }

private fun Map<String, String>.requiredWhatsappAddress(name: String): String =
    required(name).also { value ->
        require(WHATSAPP_ADDRESS.matches(value)) {
            "Environment variable $name must use the format whatsapp:+<E.164 number>"
        }
    }

private fun normalizePublicBaseUrl(rawValue: String): String {
    val value = rawValue.trim().trimEnd('/')
    require('?' !in value && '#' !in value) {
        "Media public base URL must not contain a query or fragment"
    }
    return when {
        value.startsWith("https://", ignoreCase = true) -> value
        value.equals("localhost", ignoreCase = true) ||
            value.startsWith("localhost:", ignoreCase = true) -> "http://$value"
        value.startsWith("http://", ignoreCase = true) && value.hasLocalOrIpHost() -> value
        "://" !in value -> "https://$value"
        else -> throw IllegalArgumentException("Media public base URL must use HTTPS outside localhost or an IP address")
    }
}

private fun String.hasLocalOrIpHost(): Boolean {
    val host = runCatching { URI(this).host }.getOrNull() ?: return false
    return host.equals("localhost", ignoreCase = true) || host.isIpv4Literal()
}

private fun String.isIpv4Literal(): Boolean {
    val octets = split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
    }
}

private val WHATSAPP_ADDRESS = Regex("^whatsapp:\\+[1-9]\\d{6,14}$")
private const val DEFAULT_GOOGLE_CLOUD_LOCATION = "global"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
private const val DEFAULT_MAX_TOOL_ITERATIONS = 5
