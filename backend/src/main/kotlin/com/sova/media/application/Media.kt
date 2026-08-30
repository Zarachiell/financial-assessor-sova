package com.sova.media.application

data class DownloadedMedia(
    val bytes: ByteArray,
    val contentType: String,
    val filename: String,
)

fun interface WhatsappMediaDownloader {
    suspend fun download(mediaUrl: String): DownloadedMedia
}

enum class MediaDownloadFailure(
    val code: String,
    val retryable: Boolean,
) {
    TIMEOUT("download_timeout", true),
    RATE_LIMITED("download_rate_limited", true),
    UNAVAILABLE("download_unavailable", true),
    AUTHENTICATION("download_authentication", false),
    REJECTED("download_rejected", false),
    FILE_TOO_LARGE("file_too_large", false),
}

class MediaDownloadException(
    val failure: MediaDownloadFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.code, cause)
