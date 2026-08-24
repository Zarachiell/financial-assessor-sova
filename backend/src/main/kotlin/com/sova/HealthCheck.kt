package com.sova

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

fun main() {
    val port = System.getenv("SOVA_HTTP_PORT") ?: "8080"
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:$port/health/ready"))
        .GET()
        .build()
    val statusCode = runCatching {
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }.getOrDefault(503)

    if (statusCode !in 200..299) exitProcess(1)
}
