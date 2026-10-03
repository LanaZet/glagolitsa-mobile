// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.parseIsoTimestampMillis
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AttachmentCacheTest {
    private fun newStore(): LocalDataStore =
        LocalDataStore(DatabaseDriverFactory().companionInMemory())

    @Test
    fun putOutgoingEncryptedStoresMetadataAndBytesSeparately() = runBlocking {
        val files = MemoryMediaFileStore()
        val cache = AttachmentCache(newStore(), files)
        val bytes = ByteArray(32) { it.toByte() }

        val record = cache.putOutgoingEncrypted(
            cacheId = "out-cache-1",
            encryptedBytes = bytes,
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 20,
            createdAt = "2026-07-24T10:00:00Z",
        )

        assertEquals("mem://out-cache-1", record.encryptedPath)
        assertEquals(AttachmentTransferState.PENDING, record.state)
        assertContentEquals(bytes, cache.readEncrypted("out-cache-1"))
        assertEquals(32, cache.metadata.totalBytes())
    }

    @Test
    fun stateTransitionsKeepAttachmentMetadataAddressableByRemoteId() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-2",
            encryptedBytes = byteArrayOf(1, 2, 3),
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-2",
            fileName = "report.pdf",
            mimeType = "application/pdf",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        cache.markUploading("out-cache-2", accessedAt = "2026-07-24T10:01:00Z")
        assertEquals(AttachmentTransferState.UPLOADING, cache.metadata.find("out-cache-2")?.state)

        cache.markUploaded("out-cache-2", "remote-attachment-1", accessedAt = "2026-07-24T10:02:00Z")

        val byCacheId = cache.metadata.find("out-cache-2")
        val byRemoteId = cache.metadata.findByAttachmentId("remote-attachment-1")
        assertEquals(AttachmentTransferState.UPLOADED, byCacheId?.state)
        assertEquals("out-cache-2", byRemoteId?.cacheId)
    }

    @Test
    fun thumbnailIsStoredEncryptedAndReadableThroughCache() = runBlocking {
        val files = MemoryMediaFileStore()
        val cache = AttachmentCache(newStore(), files)
        val thumbnail = ByteArray(18) { (it + 7).toByte() }
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-thumb",
            encryptedBytes = byteArrayOf(1, 2, 3),
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-thumb",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        val stored = cache.storeThumbnailPlaintext(
            cacheId = "out-cache-thumb",
            thumbnailPlaintext = thumbnail,
            accessedAt = "2026-07-24T10:01:00Z",
        )

        val record = cache.metadata.find("out-cache-thumb")
        assertEquals(true, stored)
        assertNotNull(record?.thumbnailPath)
        assertNotNull(record?.thumbnailKey)
        assertEquals(3 + (record?.thumbnailSize ?: 0), cache.metadata.totalBytes())
        assertFalse(files.raw(record?.thumbnailPath.orEmpty())?.contentEquals(thumbnail) == true)
        assertContentEquals(thumbnail, cache.readThumbnail("out-cache-thumb"))
    }

    @Test
    fun replacingThumbnailDeletesPreviousThumbnailFile() = runBlocking {
        val files = VersionedMemoryMediaFileStore()
        val cache = AttachmentCache(newStore(), files)
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-thumb-replace",
            encryptedBytes = byteArrayOf(1, 2, 3),
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-thumb-replace",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        cache.storeThumbnailPlaintext(
            cacheId = "out-cache-thumb-replace",
            thumbnailPlaintext = byteArrayOf(7, 8, 9),
            accessedAt = "2026-07-24T10:01:00Z",
        )
        val firstPath = cache.metadata.find("out-cache-thumb-replace")?.thumbnailPath ?: error("missing thumbnail")
        cache.storeThumbnailPlaintext(
            cacheId = "out-cache-thumb-replace",
            thumbnailPlaintext = byteArrayOf(4, 5, 6),
            accessedAt = "2026-07-24T10:02:00Z",
        )
        val secondPath = cache.metadata.find("out-cache-thumb-replace")?.thumbnailPath ?: error("missing thumbnail")

        assertFalse(files.contains(firstPath))
        assertEquals(true, files.contains(secondPath))
        assertContentEquals(byteArrayOf(4, 5, 6), cache.readThumbnail("out-cache-thumb-replace"))
    }

    @Test
    fun openEncryptedReadsStoredBlobByChunks() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        val bytes = ByteArray(12) { (it + 1).toByte() }
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-stream",
            encryptedBytes = bytes,
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-stream",
            fileName = "video.mp4",
            mimeType = "video/mp4",
            plaintextSize = 12,
            createdAt = "2026-07-24T10:00:00Z",
        )

        val handle = cache.openEncrypted("out-cache-stream")

        assertNotNull(handle)
        assertEquals(12, handle.encryptedSize)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), cache.readEncryptedChunk(handle, offset = 0, maxBytes = 5))
        assertContentEquals(byteArrayOf(6, 7, 8, 9, 10), cache.readEncryptedChunk(handle, offset = 5, maxBytes = 5))
        assertContentEquals(byteArrayOf(11, 12), cache.readEncryptedChunk(handle, offset = 10, maxBytes = 5))
        assertNull(cache.readEncryptedChunk(handle, offset = 12, maxBytes = 5))
    }

    @Test
    fun putOutgoingEncryptedFileCopiesSpoolIntoCacheFile() = runBlocking {
        val files = MemoryMediaFileStore()
        val cache = AttachmentCache(newStore(), files)
        files.seed("mem://picker-spool", byteArrayOf(9, 8, 7, 6))

        val record = cache.putOutgoingEncryptedFile(
            cacheId = "out-cache-file",
            encryptedPath = "mem://picker-spool",
            encryptedSize = 4,
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-file",
            fileName = "report.pdf",
            mimeType = "application/pdf",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        assertEquals("mem://out-cache-file", record.encryptedPath)
        assertFalse(files.contains("mem://picker-spool"))
        assertContentEquals(byteArrayOf(9, 8, 7, 6), cache.readEncrypted("out-cache-file"))
    }

    @Test
    fun trimDeletesExpiredAndOldNonImmuneFiles() = runBlocking {
        val files = MemoryMediaFileStore()
        val cache = AttachmentCache(newStore(), files)
        cache.putDownloadedEncrypted(
            cacheId = "expired",
            attachmentId = "remote-expired",
            encryptedBytes = ByteArray(40) { 1 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-expired",
            fileName = null,
            mimeType = null,
            plaintextSize = 40,
            expiresAt = "2026-07-24T09:00:00Z",
            createdAt = "2026-07-24T08:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "old",
            attachmentId = "remote-old",
            encryptedBytes = ByteArray(40) { 2 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-old",
            fileName = null,
            mimeType = null,
            plaintextSize = 40,
            createdAt = "2026-07-24T08:30:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "new",
            attachmentId = "remote-new",
            encryptedBytes = ByteArray(40) { 3 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-new",
            fileName = null,
            mimeType = null,
            plaintextSize = 40,
            createdAt = "2026-07-24T10:59:00Z",
        )

        val deleted = cache.trim(
            policy = AttachmentRetentionPolicy(maxBytes = 40, immunityMs = 5 * 60 * 1000L),
            nowMs = parseIsoTimestampMillis("2026-07-24T11:00:00Z") ?: error("bad timestamp"),
            nowIso = "2026-07-24T11:00:00Z",
        )

        assertEquals(2, deleted)
        assertNull(cache.metadata.find("expired"))
        assertNull(cache.metadata.find("old"))
        assertNotNull(cache.metadata.find("new"))
        assertFalse(files.contains("mem://expired"))
        assertFalse(files.contains("mem://old"))
    }

    @Test
    fun defaultTrimCapsOldFileCount() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        repeat(AttachmentRetentionPolicy.DEFAULT_MAX_FILES + 1) { index ->
            cache.putDownloadedEncrypted(
                cacheId = "old-$index",
                attachmentId = "remote-old-$index",
                encryptedBytes = byteArrayOf(index.toByte()),
                chatId = "chat-1",
                ownerAccountId = "user-1",
                messageId = "msg-old-$index",
                fileName = null,
                mimeType = null,
                plaintextSize = 1,
                createdAt = "2026-07-24T08:00:00Z",
            )
        }

        val deleted = cache.trim(
            nowMs = parseIsoTimestampMillis("2026-07-24T11:00:00Z") ?: error("bad timestamp"),
            nowIso = "2026-07-24T11:00:00Z",
        )

        assertEquals(1, deleted)
        assertEquals(AttachmentRetentionPolicy.DEFAULT_MAX_FILES, cache.stats().fileCount)
    }

    @Test
    fun trimAppliesStorageOptimizerControls() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        cache.putDownloadedEncrypted(
            cacheId = "old-image",
            attachmentId = "remote-old-image",
            encryptedBytes = ByteArray(40) { 1 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-old-image",
            fileName = "old.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-24T08:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "new-image",
            attachmentId = "remote-new-image",
            encryptedBytes = ByteArray(40) { 2 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-new-image",
            fileName = "new.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-24T10:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "kept-chat-image",
            attachmentId = "remote-kept-chat-image",
            encryptedBytes = ByteArray(40) { 3 },
            chatId = "keep-chat",
            ownerAccountId = "user-1",
            messageId = "msg-kept-chat-image",
            fileName = "kept.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-24T07:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "old-video",
            attachmentId = "remote-old-video",
            encryptedBytes = ByteArray(40) { 4 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-old-video",
            fileName = "clip.mp4",
            mimeType = "video/mp4",
            plaintextSize = 40,
            createdAt = "2026-07-24T07:00:00Z",
        )
        cache.putOutgoingEncrypted(
            cacheId = "pending-image",
            encryptedBytes = ByteArray(40) { 5 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-image",
            fileName = "pending.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-24T07:00:00Z",
        )

        val deleted = cache.trim(
            policy = AttachmentRetentionPolicy(
                maxBytes = 1_000,
                immunityMs = 0,
                maxFiles = 1,
                mimeTypePrefixes = setOf("image/"),
                excludedChatIds = setOf("keep-chat"),
            ),
            nowMs = parseIsoTimestampMillis("2026-07-24T11:00:00Z") ?: error("bad timestamp"),
            nowIso = "2026-07-24T11:00:00Z",
        )

        assertEquals(1, deleted)
        assertNull(cache.metadata.find("old-image"))
        assertNotNull(cache.metadata.find("new-image"))
        assertNotNull(cache.metadata.find("kept-chat-image"))
        assertNotNull(cache.metadata.find("old-video"))
        assertNotNull(cache.metadata.find("pending-image"))
        assertEquals(4, cache.stats().fileCount)
        assertEquals(160, cache.stats().totalBytes)
    }

    @Test
    fun trimChatOnlyDeletesEligibleFilesForSelectedChat() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        cache.putDownloadedEncrypted(
            cacheId = "old-selected-chat",
            attachmentId = "remote-old-selected-chat",
            encryptedBytes = ByteArray(40) { 1 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-old-selected-chat",
            fileName = "old.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-01T10:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "old-other-chat",
            attachmentId = "remote-old-other-chat",
            encryptedBytes = ByteArray(40) { 2 },
            chatId = "chat-2",
            ownerAccountId = "user-1",
            messageId = "msg-old-other-chat",
            fileName = "other.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-01T10:00:00Z",
        )
        cache.putDownloadedEncrypted(
            cacheId = "new-selected-chat",
            attachmentId = "remote-new-selected-chat",
            encryptedBytes = ByteArray(40) { 3 },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "msg-new-selected-chat",
            fileName = "new.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 40,
            createdAt = "2026-07-23T10:00:00Z",
        )

        val deleted = cache.trimChat(
            chatId = "chat-1",
            policy = AttachmentRetentionPolicy(
                maxBytes = Long.MAX_VALUE,
                immunityMs = 0,
                maxAgeMs = 7L * 24L * 60L * 60L * 1000L,
            ),
            nowMs = parseIsoTimestampMillis("2026-07-24T11:00:00Z") ?: error("bad timestamp"),
            nowIso = "2026-07-24T11:00:00Z",
        )

        assertEquals(1, deleted)
        assertNull(cache.metadata.find("old-selected-chat"))
        assertNotNull(cache.metadata.find("old-other-chat"))
        assertNotNull(cache.metadata.find("new-selected-chat"))
        Unit
    }

    @Test
    fun relinkMessage_updatesMetadataAfterPendingPromotedToServerId() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-relink",
            encryptedBytes = byteArrayOf(9, 8, 7),
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-relink",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        cache.metadata.relinkMessage(
            fromMessageId = "pending-relink",
            toMessageId = "server-42",
            accessedAt = "2026-07-24T10:02:00Z",
        )

        val record = cache.metadata.find("out-cache-relink")
        assertEquals("server-42", record?.messageId)
    }

    @Test
    fun linkMessage_updatesMetadataByCacheId() = runBlocking {
        val cache = AttachmentCache(newStore(), MemoryMediaFileStore())
        cache.putOutgoingEncrypted(
            cacheId = "out-cache-link",
            encryptedBytes = byteArrayOf(3, 2, 1),
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-link",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 3,
            createdAt = "2026-07-24T10:00:00Z",
        )

        cache.metadata.linkMessage(
            cacheId = "out-cache-link",
            messageId = "server-77",
            accessedAt = "2026-07-24T10:03:00Z",
        )

        val record = cache.metadata.find("out-cache-link")
        assertEquals("server-77", record?.messageId)
    }
}

private class MemoryMediaFileStore : MediaFileStore {
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String {
        val path = "mem://$cacheId"
        files[path] = bytes.copyOf()
        return path
    }

    override suspend fun copyEncrypted(sourcePath: String, cacheId: String): String {
        val bytes = files[sourcePath] ?: error("missing source")
        return writeEncrypted(cacheId, bytes)
    }

    override suspend fun readEncrypted(path: String): ByteArray? = files[path]?.copyOf()

    override suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray? {
        require(offset >= 0) { "offset must be >= 0" }
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val bytes = files[path] ?: return null
        if (offset >= bytes.size) return null
        val start = offset.toInt()
        val end = minOf(start + maxBytes, bytes.size)
        return bytes.copyOfRange(start, end)
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    override suspend fun exists(path: String): Boolean = path in files

    fun contains(path: String): Boolean = path in files

    fun seed(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
    }

    fun raw(path: String): ByteArray? = files[path]
}

private class VersionedMemoryMediaFileStore : MediaFileStore {
    private val files = mutableMapOf<String, ByteArray>()
    private var writeCount = 0

    override suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String {
        writeCount++
        val path = "mem://$cacheId-$writeCount"
        files[path] = bytes.copyOf()
        return path
    }

    override suspend fun copyEncrypted(sourcePath: String, cacheId: String): String {
        val bytes = files[sourcePath] ?: error("missing source")
        return writeEncrypted(cacheId, bytes)
    }

    override suspend fun readEncrypted(path: String): ByteArray? = files[path]?.copyOf()

    override suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray? {
        require(offset >= 0) { "offset must be >= 0" }
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val bytes = files[path] ?: return null
        if (offset >= bytes.size) return null
        val start = offset.toInt()
        val end = minOf(start + maxBytes, bytes.size)
        return bytes.copyOfRange(start, end)
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    override suspend fun exists(path: String): Boolean = path in files

    fun contains(path: String): Boolean = path in files
}
