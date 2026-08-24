package com.sova.shared.config

import com.sova.whatsapp.domain.WhatsappAddress

data class AppConfig(
    val http: HttpConfig,
    val database: DatabaseConfig,
    val twilio: TwilioConfig,
    val household: HouseholdProvisioningConfig,
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
            )
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

private fun Map<String, String>.optionalPort(name: String): Int? =
    get(name)?.let { rawValue ->
        rawValue.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: error("Environment variable $name must be a port between 1 and 65535")
    }

private fun Map<String, String>.requiredWhatsappAddress(name: String): String =
    required(name).also { value ->
        require(WHATSAPP_ADDRESS.matches(value)) {
            "Environment variable $name must use the format whatsapp:+<E.164 number>"
        }
    }

private val WHATSAPP_ADDRESS = Regex("^whatsapp:\\+[1-9]\\d{6,14}$")
