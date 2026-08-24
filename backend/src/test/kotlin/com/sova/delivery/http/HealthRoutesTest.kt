package com.sova.delivery.http

import com.sova.DatabaseHealth
import com.sova.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `liveness does not depend on the database`() = testApplication {
        application { module(DatabaseHealth { false }) }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UP", response.healthBody().status)
    }

    @Test
    fun `readiness reports an available database`() = testApplication {
        application { module(DatabaseHealth { true }) }

        val response = client.get("/health/ready")
        val body = response.healthBody()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UP", body.status)
        assertEquals("UP", body.components?.get("database"))
    }

    @Test
    fun `readiness rejects traffic while the database is unavailable`() = testApplication {
        application { module(DatabaseHealth { false }) }

        val response = client.get("/health")
        val body = response.healthBody()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("DOWN", body.status)
        assertEquals("DOWN", body.components?.get("database"))
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.healthBody(): HealthResponse =
    Json.decodeFromString(bodyAsText())
