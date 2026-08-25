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
        "SOVA_AI_PROVIDER" to "groq",
        "GROQ_API_KEY" to "test-api-key",
        "GROQ_MODEL" to "test-tool-model",
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
    fun `loads Groq provider configuration`() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)

        assertEquals("groq", config.ai.provider)
        assertEquals("test-api-key", config.ai.apiKey)
        assertEquals("test-tool-model", config.ai.model)
    }

    @Test
    fun `rejects unsupported AI provider`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(requiredEnvironment + ("SOVA_AI_PROVIDER" to "remote-mcp"))
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
