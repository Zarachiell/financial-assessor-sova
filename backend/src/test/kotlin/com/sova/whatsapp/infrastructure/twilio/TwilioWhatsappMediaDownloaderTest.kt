package com.sova.whatsapp.infrastructure.twilio

import com.sova.media.application.MediaDownloadException
import com.sova.media.application.MediaDownloadFailure
import com.sova.shared.config.TwilioConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TwilioWhatsappMediaDownloaderTest {
    @Test
    fun `downloads media with Twilio basic authentication and a bounded body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/ogg")
                    .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))),
            )
            val media = downloader(server, maxBytes = 10).use { it.download(server.url("/media/ME1").toString()) }

            assertContentEquals(byteArrayOf(1, 2, 3, 4), media.bytes)
            assertEquals("audio/ogg", media.contentType)
            assertEquals("media.ogg", media.filename)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
            assertEquals("/media/ME1", request.path)
        }
    }

    @Test
    fun `rejects media larger than the configured limit without retaining it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/ogg")
                    .setBody("123456"),
            )

            val failure = assertFailsWith<MediaDownloadException> {
                downloader(server, maxBytes = 5).use { it.download(server.url("/media/large").toString()) }
            }

            assertEquals(MediaDownloadFailure.FILE_TOO_LARGE, failure.failure)
        }
    }

    @Test
    fun `classifies transient Twilio failures for controlled retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))

            val failure = assertFailsWith<MediaDownloadException> {
                downloader(server, maxBytes = 10).use { it.download(server.url("/media/unavailable").toString()) }
            }

            assertEquals(MediaDownloadFailure.UNAVAILABLE, failure.failure)
            assertTrue(failure.failure.retryable)
        }
    }

    private fun downloader(server: MockWebServer, maxBytes: Long) = TwilioWhatsappMediaDownloader(
        config = TwilioConfig(
            accountSid = "AC00000000000000000000000000000000",
            authToken = "test-auth-token",
            whatsappSender = "whatsapp:+15550000000",
        ),
        maxBytes = maxBytes,
        client = OkHttpClient(),
        urlPolicy = { url -> url.host == server.hostName },
    )
}
