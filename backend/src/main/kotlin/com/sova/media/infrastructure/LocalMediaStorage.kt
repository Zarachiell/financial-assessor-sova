package com.sova.media.infrastructure

import com.sova.media.application.MediaStorage
import com.sova.media.application.StoredMedia
import com.sova.media.application.StoredMediaContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

class LocalMediaStorage(root: Path) : MediaStorage {
    private val root = root.toAbsolutePath().normalize().also(Files::createDirectories)

    override suspend fun store(
        householdId: UUID,
        attachmentId: UUID,
        contentType: String,
        bytes: ByteArray,
    ): StoredMedia = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Stored media must not be empty" }
        val extension = contentType.extension()
        val storageKey = "$householdId/$attachmentId.$extension"
        val target = resolve(storageKey)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "$attachmentId-", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        StoredMedia(storageKey, bytes.sha256Hex(), bytes.size.toLong())
    }

    override suspend fun read(
        storageKey: String,
        expectedSha256Hex: String,
        contentType: String,
    ): StoredMediaContent = withContext(Dispatchers.IO) {
        require(expectedSha256Hex.matches(SHA256)) { "Invalid expected media hash" }
        val bytes = Files.readAllBytes(resolve(storageKey))
        val actualHash = bytes.sha256Hex()
        check(MessageDigest.isEqual(actualHash.toByteArray(), expectedSha256Hex.toByteArray())) {
            "Stored media integrity check failed"
        }
        StoredMediaContent(bytes, contentType, actualHash)
    }

    private fun resolve(storageKey: String): Path {
        require(storageKey.isNotBlank() && !storageKey.startsWith('/')) { "Invalid media storage key" }
        val resolved = root.resolve(storageKey).normalize()
        require(resolved.startsWith(root)) { "Media storage key escapes configured root" }
        return resolved
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun String.extension(): String = when (this) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> throw IllegalArgumentException("Unsupported stored media content type")
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
