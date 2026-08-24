package com.sova.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    private val requiredEnvironment = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/sova",
        "DATABASE_USER" to "sova",
        "DATABASE_PASSWORD" to "secret",
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
}
