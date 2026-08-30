package com.sova.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    private val requiredEnvironment = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/sova",
        "DATABASE_USER" to "sova",
        "DATABASE_PASSWORD" to "secret",
        "TWILIO_ACCOUNT_SID" to "AC00000000000000000000000000000000",
        "TWILIO_AUTH_TOKEN" to "twilio-secret",
        "TWILIO_WHATSAPP_SENDER" to "whatsapp:+15550000000",
        "SOVA_HOUSEHOLD_NAME" to "Sova Household",
        "SOVA_USER_A_NAME" to "User A",
        "SOVA_USER_A_WHATSAPP" to "whatsapp:+15550000001",
        "SOVA_USER_B_NAME" to "User B",
        "SOVA_USER_B_WHATSAPP" to "whatsapp:+15550000002",
        "AI_PROVIDER" to "gemini",
        "GOOGLE_CLOUD_PROJECT" to "sova-test",
        "GOOGLE_APPLICATION_CREDENTIALS" to "/run/secrets/google_service_account",
    )

    @Test
    fun `uses safe HTTP defaults`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)

        assertEquals("0.0.0.0", config.http.host)
        assertEquals(8080, config.http.port)
    }

    @Test
    fun `accepts a valid configured port`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_HTTP_PORT" to "9090"))

        assertEquals(9090, config.http.port)
    }

    @Test
    fun `loads Twilio and the two household users from configuration`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)

        assertEquals("AC00000000000000000000000000000000", config.twilio.accountSid)
        assertEquals("whatsapp:+15550000000", config.twilio.whatsappSender)
        assertEquals("Sova Household", config.household.name)
        assertEquals(
            listOf("whatsapp:+15550000001", "whatsapp:+15550000002"),
            config.household.users.map { it.whatsappAddress },
        )
    }

    @Test
    fun `loads Gemini as the exclusive provider`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)
        val settings = config.ai.settings

        assertEquals("sova-test", settings.project)
        assertEquals("gemini-3.5-flash-lite", settings.model)
        assertEquals(5, config.ai.maxToolIterations)
    }

    @Test
    fun `loads Gemini enterprise configuration without requiring Groq credentials`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)
        val settings = config.ai.settings

        assertEquals("sova-test", settings.project)
        assertEquals("global", settings.location)
        assertEquals("gemini-3.5-flash-lite", settings.model)
        assertEquals("/run/secrets/google_service_account", settings.credentialsPath)
    }

    @Test
    fun `uses explicit Gemini settings and configurable tool iteration limit`() {
        val config = AppConfig.fromEnvironment(
            requiredEnvironment + mapOf(
                "AI_PROVIDER" to "gemini",
                "GOOGLE_CLOUD_PROJECT" to "another-project",
                "GOOGLE_CLOUD_LOCATION" to "us-central1",
                "GOOGLE_APPLICATION_CREDENTIALS" to "/credentials.json",
                "GEMINI_MODEL" to "gemini-custom",
                "MAX_TOOL_ITERATIONS" to "7",
            ),
        )
        val settings = config.ai.settings

        assertEquals("us-central1", settings.location)
        assertEquals("gemini-custom", settings.model)
        assertEquals(7, config.ai.maxToolIterations)
    }

    @Test
    fun `uses and validates the configurable cash flow horizon`() {
        assertEquals(24, AppConfig.fromEnvironment(requiredEnvironment).cashFlow.maxProjectionMonths)
        assertEquals(
            36,
            AppConfig.fromEnvironment(
                requiredEnvironment + ("SOVA_MAX_PROJECTION_MONTHS" to "36"),
            ).cashFlow.maxProjectionMonths,
        )
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_MAX_PROJECTION_MONTHS" to "121"))
        }
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_MAX_PROJECTION_MONTHS" to "zero"))
        }
    }

    @Test
    fun `uses centralized Gemini audio limits and model`() {
        val defaults = AppConfig.fromEnvironment(requiredEnvironment).audio
        assertEquals("gemini-3.5-flash-lite", defaults.model)
        assertEquals(16L * 1024 * 1024, defaults.maxAudioBytes)
        assertEquals(5, defaults.maxAudioDurationMinutes)
        assertEquals(3, defaults.maxAttempts)

        val configured = AppConfig.fromEnvironment(
            requiredEnvironment + mapOf(
                "GEMINI_AUDIO_MODEL" to "gemini-audio-test",
                "SOVA_MAX_AUDIO_BYTES" to "1024",
                "SOVA_MAX_AUDIO_DURATION_MINUTES" to "4",
                "SOVA_AUDIO_MAX_ATTEMPTS" to "2",
            ),
        ).audio
        assertEquals("gemini-audio-test", configured.model)
        assertEquals(1024, configured.maxAudioBytes)
        assertEquals(4, configured.maxAudioDurationMinutes)
        assertEquals(2, configured.maxAttempts)
    }

    @Test
    fun `uses centralized Gemini image limits confidence and storage`() {
        val defaults = AppConfig.fromEnvironment(requiredEnvironment).image
        assertEquals("gemini-3.5-flash-lite", defaults.model)
        assertEquals(10L * 1024 * 1024, defaults.maxImageBytes)
        assertEquals(3, defaults.maxAttempts)
        assertEquals("0.85".toBigDecimal(), defaults.minimumAutoConfidence)
        assertEquals("/var/lib/sova/attachments", defaults.storagePath)

        val configured = AppConfig.fromEnvironment(
            requiredEnvironment + mapOf(
                "GEMINI_IMAGE_MODEL" to "gemini-image-test",
                "SOVA_MAX_IMAGE_BYTES" to "2048",
                "SOVA_IMAGE_MAX_ATTEMPTS" to "2",
                "SOVA_IMAGE_MIN_CONFIDENCE" to "0.90",
                "SOVA_MEDIA_STORAGE_PATH" to "build/media-test",
            ),
        ).image
        assertEquals("gemini-image-test", configured.model)
        assertEquals(2048, configured.maxImageBytes)
        assertEquals(2, configured.maxAttempts)
        assertEquals("0.90".toBigDecimal(), configured.minimumAutoConfidence)
        assertEquals("build/media-test", configured.storagePath)
    }

    @Test
    fun `rejects malformed or out of range image confidence`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_IMAGE_MIN_CONFIDENCE" to "high"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_IMAGE_MIN_CONFIDENCE" to "1.1"))
        }
    }

    @Test
    fun `rejects unsupported AI provider`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("AI_PROVIDER" to "remote-mcp"))
        }
    }

    @Test
    fun `rejects an excessive tool iteration limit`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("MAX_TOOL_ITERATIONS" to "21"))
        }
    }

    @Test
    fun `fails without database credentials`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(emptyMap())
        }
    }

    @Test
    fun `rejects an invalid configured port`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_HTTP_PORT" to "70000"))
        }
    }

    @Test
    fun `rejects duplicate authorized WhatsApp addresses`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                requiredEnvironment + ("SOVA_USER_B_WHATSAPP" to requiredEnvironment.getValue("SOVA_USER_A_WHATSAPP")),
            )
        }
    }

    @Test
    fun `rejects Brazilian mobile aliases as duplicate authorized identities`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                requiredEnvironment + mapOf(
                    "SOVA_USER_A_WHATSAPP" to "whatsapp:+5585991234567",
                    "SOVA_USER_B_WHATSAPP" to "whatsapp:+558591234567",
                ),
            )
        }
    }

    @Test
    fun `rejects a WhatsApp address outside the provider format`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("TWILIO_WHATSAPP_SENDER" to "+15550000000"))
        }
    }
}
