package com.sova.shared.config

import com.sova.whatsapp.domain.WhatsappAddress
import java.util.Locale

data class AppConfig(
    val http: HttpConfig,
    val database: DatabaseConfig,
    val twilio: TwilioConfig,
    val household: HouseholdProvisioningConfig,
    val ai: AiConfig,
    val cashFlow: CashFlowConfig,
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
            )

        private const val DEFAULT_MAX_PROJECTION_MONTHS = 24
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

enum class AiProviderType {
    GROQ,
    GEMINI,
}

sealed interface AiProviderSettings {
    val model: String
}

data class GroqAiConfig(
    val apiKey: String,
    override val model: String,
) : AiProviderSettings

data class GeminiAiConfig(
    val project: String,
    val location: String,
    override val model: String,
    val credentialsPath: String,
) : AiProviderSettings

data class AiConfig(
    val provider: AiProviderType,
    val settings: AiProviderSettings,
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
    val configuredProvider = get("AI_PROVIDER")
        ?.takeIf(String::isNotBlank)
        ?: required("SOVA_AI_PROVIDER")
    val provider = runCatching {
        AiProviderType.valueOf(configuredProvider.uppercase(Locale.ROOT))
    }.getOrElse {
        throw IllegalArgumentException("Environment variable AI_PROVIDER must be gemini or groq")
    }
    val settings = when (provider) {
        AiProviderType.GROQ -> GroqAiConfig(
            apiKey = required("GROQ_API_KEY"),
            model = required("GROQ_MODEL"),
        )
        AiProviderType.GEMINI -> GeminiAiConfig(
            project = required("GOOGLE_CLOUD_PROJECT"),
            location = get("GOOGLE_CLOUD_LOCATION")?.takeIf(String::isNotBlank) ?: DEFAULT_GOOGLE_CLOUD_LOCATION,
            model = get("GEMINI_MODEL")?.takeIf(String::isNotBlank) ?: DEFAULT_GEMINI_MODEL,
            credentialsPath = required("GOOGLE_APPLICATION_CREDENTIALS"),
        )
    }
    return AiConfig(
        provider = provider,
        settings = settings,
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

private fun Map<String, String>.requiredWhatsappAddress(name: String): String =
    required(name).also { value ->
        require(WHATSAPP_ADDRESS.matches(value)) {
            "Environment variable $name must use the format whatsapp:+<E.164 number>"
        }
    }

private val WHATSAPP_ADDRESS = Regex("^whatsapp:\\+[1-9]\\d{6,14}$")
private const val DEFAULT_GOOGLE_CLOUD_LOCATION = "global"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
private const val DEFAULT_MAX_TOOL_ITERATIONS = 5
