package com.sova.media.application

import java.util.UUID

data class StoredMedia(
    val storageKey: String,
    val sha256Hex: String,
    val sizeBytes: Long,
)

data class StoredMediaContent(
    val bytes: ByteArray,
    val contentType: String,
    val sha256Hex: String,
)

interface MediaStorage {
    suspend fun store(
        householdId: UUID,
        attachmentId: UUID,
        contentType: String,
        bytes: ByteArray,
    ): StoredMedia

    suspend fun read(storageKey: String, expectedSha256Hex: String, contentType: String): StoredMediaContent
}
