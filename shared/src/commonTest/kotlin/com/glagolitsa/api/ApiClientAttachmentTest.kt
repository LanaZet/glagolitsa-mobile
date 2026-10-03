// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.util.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiClientAttachmentTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun uploadAttachment_readsLargeBodyByChunks() = runTest {
        val source = ByteArray((2 * CHUNK_SIZE) + 17) { (it % 251).toByte() }
        val expectedChunks = listOf(
            source.copyOfRange(0, CHUNK_SIZE),
            source.copyOfRange(CHUNK_SIZE, 2 * CHUNK_SIZE),
            source.copyOfRange(2 * CHUNK_SIZE, source.size),
        )
        val readOffsets = mutableListOf<Long>()
        val readMaxBytes = mutableListOf<Int>()
        var requestIndex = 0
        val engine = MockEngine { request ->
            val index = requestIndex++
            assertEquals("/api/attachments/att-1", request.url.encodedPath)
            assertEquals("Bearer tok-1", request.headers[HttpHeaders.Authorization])
            assertEquals((index * CHUNK_SIZE).toString(), request.headers["X-Upload-Offset"])
            assertEquals((index == expectedChunks.lastIndex).toString(), request.headers["X-Upload-Complete"])
            assertEquals(sha256Hex(expectedChunks[index]), request.headers["X-Chunk-SHA256"])
            respond(
                content = if (index == expectedChunks.lastIndex) {
                    """{"attachment_id":"att-1","size_bytes":${source.size},"size_bucket":${source.size}}"""
                } else {
                    "{}"
                },
                status = if (index == expectedChunks.lastIndex) HttpStatusCode.OK else HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val info = client(engine).uploadAttachment(
            token = "tok-1",
            attachmentId = "att-1",
            encryptedSize = source.size.toLong(),
            readChunk = { offset, maxBytes ->
                readOffsets += offset
                readMaxBytes += maxBytes
                val start = offset.toInt()
                val end = minOf(start + maxBytes, source.size)
                source.copyOfRange(start, end)
            },
        )

        assertEquals("att-1", info.attachment_id)
        assertEquals(source.size.toLong(), info.size_bytes)
        assertEquals(listOf(0L, CHUNK_SIZE.toLong(), (2 * CHUNK_SIZE).toLong()), readOffsets)
        assertEquals(listOf(CHUNK_SIZE, CHUNK_SIZE, 17), readMaxBytes)
        assertEquals(3, requestIndex)
    }

    private companion object {
        const val CHUNK_SIZE = 1 * 1024 * 1024
    }
}
