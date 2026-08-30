package com.sova.media.infrastructure

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class LocalMediaStorageTest {
    @Test
    fun `stores outside the database and verifies content integrity`() = runBlocking {
        val root = createTempDirectory("sova-media-")
        try {
            val storage = LocalMediaStorage(root)
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
            val stored = storage.store(UUID.randomUUID(), UUID.randomUUID(), "image/png", bytes)

            assertContentEquals(bytes, storage.read(stored.storageKey, stored.sha256Hex, "image/png").bytes)

            Files.write(root.resolve(stored.storageKey), byteArrayOf(9, 8, 7))
            assertFailsWith<IllegalStateException> {
                storage.read(stored.storageKey, stored.sha256Hex, "image/png")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects storage keys that escape the configured root`() = runBlocking {
        val root = createTempDirectory("sova-media-")
        try {
            assertFailsWith<IllegalArgumentException> {
                LocalMediaStorage(root).read("../receipt.png", "a".repeat(64), "image/png")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
