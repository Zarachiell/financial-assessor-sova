package com.sova

import com.sova.delivery.http.configureHttp
import com.sova.shared.config.AppConfig
import com.sova.shared.infrastructure.database.PostgresDatabase
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfig.fromEnvironment()
    val database = PostgresDatabase(config.database)

    database.migrate()

    embeddedServer(
        factory = Netty,
        host = config.http.host,
        port = config.http.port,
    ) {
        module(database)
    }.start(wait = true)
}

fun Application.module(database: DatabaseHealth) {
    configureHttp(database)
}
