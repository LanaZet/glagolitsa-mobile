// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.api.ApiClient
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.db.LocalDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AttachmentTransferManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newCache(files: MediaFileStore = MemoryTransferMediaFileStore()): AttachmentCache =
        AttachmentCache(
            LocalDataStore(DatabaseDriverFactory().companionInMemory()),
            files,
        )

    private fun client(engine: MockEngine): ApiClient =
        ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            },
        )

    @Test
    fun uploadOutgoingStreamsCachedBlobAndMarksUploaded() = runBlocking {
        val cache = newCache()
        cache.putOutgoingEncrypted(
            cacheId = "out-cache",
            encryptedBytes = ByteArray((1024 * 1024) + 3) { (it % 251).toByte() },
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "pending-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 100,
            createdAt = "2026-07-24T10:00:00Z",
        )
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when {
                request.url.encodedPath == "/api/attachments" && request.method == HttpMethod.Post -> respondJson(
                    """{"attachment_id":"remote-1","expires_at":"2026-07-25T10:00:00Z"}""",
                )
                request.url.encodedPath == "/api/attachments/remote-1" && request.method == HttpMethod.Put -> {
                    val complete = request.headers["X-Upload-Complete"] ?: "true"
                    respondJson(
                        content = if (complete == "true") {
                            """{"attachment_id":"remote-1","size_bytes":1048579,"size_bucket":1048579}"""
                        } else {
                            "{}"
                        },
                        status = if (complete == "true") HttpStatusCode.OK else HttpStatusCode.Accepted,
                    )
                }
                else -> respondJson("{}", HttpStatusCode.NotFound)
            }
        }

        val attachmentId = AttachmentTransferManager(client(engine), cache).uploadOutgoing(
            token = "token-1",
            cacheId = "out-cache",
            legacyEncryptedBytes = null,
        )

        val record = cache.metadata.find("out-cache")
        assertEquals("remote-1", attachmentId)
        assertEquals("remote-1", record?.attachmentId)
        assertEquals(AttachmentTransferState.UPLOADED, record?.state)
        assertEquals(
            listOf(
                "/api/media/upload-slots",
                "/api/attachments",
                "/api/attachments/remote-1",
                "/api/attachments/remote-1",
            ),
            requestedPaths,
        )
    }

    @Test
    fun loadOrDownloadCachesIncomingBlobAndReusesIt() = runBlocking {
        val encryptedBlob = byteArrayOf(9, 8, 7, 6)
        var downloads = 0
        val cache = newCache()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/attachments/remote-2" && request.method == HttpMethod.Get -> {
                    downloads++
                    respond(
                        content = encryptedBlob,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                    )
                }
                else -> respondJson("{}", HttpStatusCode.NotFound)
            }
        }
        val manager = AttachmentTransferManager(
            api = client(engine),
            cache = cache,
            nowIso = { "2026-07-24T10:00:00Z" },
        )

        val first = manager.loadOrDownload(
            token = "token-1",
            attachmentId = "remote-2",
            cacheId = "remote-cache",
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "message-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 4,
        )
        val second = manager.loadOrDownload(
            token = "token-1",
            attachmentId = "remote-2",
            cacheId = "remote-cache",
            chatId = "chat-1",
            ownerAccountId = "user-1",
            messageId = "message-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plaintextSize = 4,
        )

        val record = cache.metadata.find("remote-cache")
        assertContentEquals(encryptedBlob, first)
        assertContentEquals(encryptedBlob, second)
        assertEquals(1, downloads)
        assertNotNull(record)
        assertEquals(AttachmentTransferState.DOWNLOADED, record.state)
    }

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

private class MemoryTransferMediaFileStore : MediaFileStore {
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
}
