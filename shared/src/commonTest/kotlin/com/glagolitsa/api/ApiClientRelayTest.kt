// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.MessageQueueResponse
import com.glagolitsa.model.QueuedEnvelope
import com.glagolitsa.model.RelayEnvelopeRequest
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientRelayTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun relayMessage_sendsAuthAndDeviceHeaders() = runTest {
        var sawAuth = false
        var sawDevice = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/messages/relay") -> {
                    sawAuth = request.headers[HttpHeaders.Authorization] == "Bearer tok-1"
                    sawDevice = request.headers["X-Device-Id"] == "dev-1"
                    respond(
                        content = json.encodeToString(
                            RelayMessageResponse.serializer(),
                            RelayMessageResponse(enqueued = 1, envelope_ids = listOf("env-1")),
                        ),
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }

        val response = client(engine).relayMessage(
            token = "tok-1",
            deviceId = "dev-1",
            request = RelayMessageRequest(
                client_message_id = "pending-1",
                envelopes = listOf(
                    RelayEnvelopeRequest(
                        mailbox_token = "mailbox-1",
                        envelope_type = 3,
                        ciphertext = "ZmFrZQ==",
                    ),
                ),
            ),
        )

        assertTrue(sawAuth)
        assertTrue(sawDevice)
        assertEquals(1, response.enqueued)
        assertEquals(listOf("env-1"), response.envelope_ids)
    }

    @Test
    fun fetchMessageQueue_requiresDeviceHeader() = runTest {
        var sawDevice = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/messages/queue") -> {
                    sawDevice = request.headers["X-Device-Id"] == "dev-2"
                    respond(
                        content = json.encodeToString(
                            MessageQueueResponse.serializer(),
                            MessageQueueResponse(
                                envelopes = listOf(
                                    QueuedEnvelope(
                                        envelope_id = "env-1",
                                        mailbox_token = "mailbox-1",
                                        envelope_type = 3,
                                        ciphertext = "ZmFrZQ==",
                                        size_bucket = 16,
                                    ),
                                ),
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }

        val response = client(engine).fetchMessageQueue(token = "tok-2", deviceId = "dev-2", limit = 10)
        assertTrue(sawDevice)
        assertEquals(1, response.envelopes.size)
        assertEquals("env-1", response.envelopes.first().envelope_id)
    }

    @Test
    fun relayMessage_forbiddenWithoutDeviceRegistration() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"account pending device registration"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val error = assertFailsWith<ApiException> {
            client(engine).relayMessage(
                token = "tok-1",
                deviceId = "dev-1",
                request = RelayMessageRequest(
                    envelopes = listOf(
                        RelayEnvelopeRequest(
                            mailbox_token = "mailbox-1",
                            envelope_type = 3,
                            ciphertext = "ZmFrZQ==",
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Forbidden, error.status)
    }

    @Test
    fun ackMessageQueue_returnsDeletedCount() = runTest {
        var sawDevice = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/messages/queue/ack") -> {
                    sawDevice = request.headers["X-Device-Id"] == "dev-3"
                    respond(
                        content = """{"deleted":1}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }

        val response = client(engine).ackMessageQueue(
            token = "tok-3",
            deviceId = "dev-3",
            request = AckMessageQueueRequest(envelope_ids = listOf("env-9")),
        )
        assertTrue(sawDevice)
        assertEquals(1, response.deleted)
    }
}