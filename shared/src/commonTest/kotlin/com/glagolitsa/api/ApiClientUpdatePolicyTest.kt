// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

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

class ApiClientUpdatePolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(engine: MockEngine) = ApiClient(
        baseUrl = "https://api.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        },
    )

    @Test
    fun getClientUpdatePolicy_decodesPolicy() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = """{"android":{"latest_version":"0.2.0","latest_build":2}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val policy = client(engine).getClientUpdatePolicy()

        assertTrue(path.endsWith("/api/client/update-policy"))
        assertEquals("0.2.0", policy.android.latest_version)
        assertEquals(2, policy.android.latest_build)
    }

    @Test
    fun getClientUpdatePolicy_404TextThrowsApiException() = runTest {
        val engine = MockEngine {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val error = assertFailsWith<ApiException> {
            client(engine).getClientUpdatePolicy()
        }

        assertEquals(HttpStatusCode.NotFound, error.status)
    }
}
