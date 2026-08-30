package com.sova.whatsapp.infrastructure.twilio

import com.sova.media.application.DownloadedMedia
import com.sova.media.application.MediaDownloadException
import com.sova.media.application.MediaDownloadFailure
import com.sova.media.application.WhatsappMediaDownloader
import com.sova.shared.config.TwilioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

class TwilioWhatsappMediaDownloader(
    config: TwilioConfig,
    private val maxBytes: Long,
    private val client: OkHttpClient = defaultClient(),
    private val urlPolicy: (HttpUrl) -> Boolean = ::isTwilioMediaUrl,
) : WhatsappMediaDownloader, AutoCloseable {
    private val authorization = Credentials.basic(config.accountSid, config.authToken)

    init {
        require(maxBytes > 0) { "Maximum media size must be positive" }
    }

    override suspend fun download(mediaUrl: String): DownloadedMedia = withContext(Dispatchers.IO) {
        val url = mediaUrl.toHttpUrlOrNull()
            ?.takeIf(urlPolicy)
            ?: throw MediaDownloadException(MediaDownloadFailure.REJECTED)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw MediaDownloadException(MediaDownloadFailure.AUTHENTICATION)
                    response.code == 429 ->
                        throw MediaDownloadException(MediaDownloadFailure.RATE_LIMITED)
                    response.code >= 500 ->
                        throw MediaDownloadException(MediaDownloadFailure.UNAVAILABLE)
                    !response.isSuccessful ->
                        throw MediaDownloadException(MediaDownloadFailure.REJECTED)
                }
                val body = response.body ?: throw MediaDownloadException(MediaDownloadFailure.REJECTED)
                if (body.contentLength() > maxBytes) {
                    throw MediaDownloadException(MediaDownloadFailure.FILE_TOO_LARGE)
                }
                val contentType = body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                    ?: throw MediaDownloadException(MediaDownloadFailure.REJECTED)
                val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES.toLong()).toInt())
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw MediaDownloadException(MediaDownloadFailure.FILE_TOO_LARGE)
                        output.write(buffer, 0, read)
                    }
                }
                DownloadedMedia(
                    bytes = output.toByteArray(),
                    contentType = contentType,
                    filename = "media.${contentType.fileExtension()}",
                )
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: MediaDownloadException) {
            throw cause
        } catch (cause: SocketTimeoutException) {
            throw MediaDownloadException(MediaDownloadFailure.TIMEOUT, cause)
        } catch (cause: IOException) {
            throw MediaDownloadException(MediaDownloadFailure.UNAVAILABLE, cause)
        } catch (cause: Exception) {
            throw MediaDownloadException(MediaDownloadFailure.REJECTED, cause)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        runCatching { client.cache?.close() }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 8 * 1024
        const val INITIAL_BUFFER_BYTES = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(40))
            .build()
    }
}

private fun isTwilioMediaUrl(url: HttpUrl): Boolean =
    url.isHttps && (url.host == "api.twilio.com" || url.host.endsWith(".twilio.com"))

private fun String.fileExtension(): String = when (this) {
    "audio/ogg" -> "ogg"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/aiff" -> "aiff"
    "audio/aac" -> "aac"
    "audio/flac" -> "flac"
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "bin"
}
