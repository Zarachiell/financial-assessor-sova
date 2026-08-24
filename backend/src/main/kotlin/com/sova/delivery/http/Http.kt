package com.sova.delivery.http

import com.sova.DatabaseHealth
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureHttp(database: DatabaseHealth) {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        verify { it.length in 1..128 }
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("correlationId")
        filter { call -> !call.request.path().startsWith("/health") }
    }

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unexpected request failure", cause)
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = HealthResponse(status = "ERROR"),
            )
        }
    }

    routing {
        get("/health/live") {
            call.respond(HealthResponse(status = "UP"))
        }

        get("/health/ready") {
            call.respondReadiness(database)
        }

        get("/health") {
            call.respondReadiness(database)
        }
    }
}

private suspend fun ApplicationCall.respondReadiness(database: DatabaseHealth) {
    val ready = database.isReady()
    respond(
        status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        message = HealthResponse(
            status = if (ready) "UP" else "DOWN",
            components = mapOf("database" to if (ready) "UP" else "DOWN"),
        ),
    )
}

@Serializable
data class HealthResponse(
    val status: String,
    val components: Map<String, String>? = null,
)
