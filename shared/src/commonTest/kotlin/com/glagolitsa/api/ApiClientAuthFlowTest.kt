// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientAuthFlowTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun login_succeedsWithToken() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = json.encodeToString(
                        AuthResponse.serializer(),
                        AuthResponse(
                            token = "access-token",
                            user = User(id = "u1", username = "alice", email = null),
                        ),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val response = client(engine).login(
            com.glagolitsa.model.LoginRequest(username = "alice", password = "secret"),
        )
        assertEquals("access-token", response.token)
        assertEquals("alice", response.user.username)
    }

    @Test
    fun login_wrongPasswordThrowsUnauthorized() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val error = assertFailsWith<ApiException> {
            client(engine).login(
                com.glagolitsa.model.LoginRequest(username = "alice", password = "bad"),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, error.status)
    }

    @Test
    fun registerDevice_calledWithBearerToken() = runTest {
        var sawAuth = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/devices") -> {
                    sawAuth = request.headers[HttpHeaders.Authorization] == "Bearer tok-1"
                    respond(
                        content = """{"device_id":"dev-1","prekeys_stored":1,"one_time_prekey_ids":[1]}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        client(engine).registerDevice(
            token = "tok-1",
            deviceId = "dev-1",
            request = com.glagolitsa.model.RegisterDeviceRequest(
                device_id = "dev-1",
                registration_id = 1,
                identity_public_key = "aGVsbG8=",
                signed_prekey = com.glagolitsa.model.SignedPreKeyMaterial(1, "aGVsbG8=", "aGVsbG8=", 1),
                pq_prekey = com.glagolitsa.model.PqPreKeyMaterial(1, "aGVsbG8=", "aGVsbG8=", 1),
                one_time_prekeys = emptyList(),
            ),
        )
        assertTrue(sawAuth)
    }
}