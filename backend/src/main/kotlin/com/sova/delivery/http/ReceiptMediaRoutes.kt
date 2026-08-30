package com.sova.delivery.http

import com.sova.receipt.application.ReceiptMediaAccess
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

data class ReceiptMediaHttpDependencies(val access: ReceiptMediaAccess)

fun Route.receiptMediaRoutes(dependencies: ReceiptMediaHttpDependencies) {
    get("/media/receipts/{receiptId}") {
        val receiptId = call.parameters["receiptId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val expires = call.request.queryParameters["expires"]?.toLongOrNull()
        val signature = call.request.queryParameters["signature"]
        if (receiptId == null || expires == null || signature == null) {
            call.respondText("", status = HttpStatusCode.NotFound)
            return@get
        }
        val media = dependencies.access.read(receiptId, expires, signature)
        if (media == null) {
            call.respondText("", status = HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, CacheControl.NoStore(null).toString())
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(media.bytes, ContentType.parse(media.contentType), HttpStatusCode.OK)
    }
}
