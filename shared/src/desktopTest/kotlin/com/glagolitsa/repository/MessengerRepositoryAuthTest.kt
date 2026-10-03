// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.auth.DeviceRegistrationException
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.User
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessengerRepositoryAuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun resetSession() {
        SessionStore.clear()
    }

    @AfterTest
    fun cleanup() = runTest {
        SessionStore.clear()
    }

    private fun authResponse(username: String = "flow-user") = json.encodeToString(
        AuthResponse.serializer(),
        AuthResponse(
            token = "tok-flow",
            user = User(id = "user-flow-1", username = username, email = null),
        ),
    )

    private fun repository(
        engine: MockEngine,
        crypto: TestCryptoEngine = TestCryptoEngine(),
    ): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = crypto,
    )

    @Test
    fun login_withoutDeviceRegistration_throwsAndClearsSession() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = authResponse(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"auth-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/devices") -> respond(
                    content = """{"error":"account pending device registration"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("[]", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)

        assertFailsWith<DeviceRegistrationException> {
            repo.login("flow-user", "password123")
        }
        assertNull(SessionStore.token.value)
    }

    @Test
    fun login_withDeviceRegistration_persistsSession() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = authResponse(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"auth-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/devices") -> respond(
                    content = """{"device_id":"test-device-user-flow-1","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/chats") -> respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)

        repo.login("flow-user", "password123")
        assertNotNull(SessionStore.token.value)
        assertNotNull(SessionStore.user.value)
        kotlin.test.assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun reauthLogin_forKnownAccountSendsDeviceId() = runTest {
        var loginDeviceId: String? = null
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> {
                    val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                    loginDeviceId = json.parseToJsonElement(body)
                        .jsonObject["device_id"]
                        ?.jsonPrimitive
                        ?.content
                    respond(
                        content = authResponse(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"auth-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/devices") -> respond(
                    content = """{"device_id":"test-device-user-flow-1","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/chats") -> respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)
        SessionStore.setUserOnly(User(id = "user-flow-1", username = "flow-user", email = null))

        repo.login("flow-user", "password123")

        assertEquals("test-device-user-flow-1", loginDeviceId)
    }

    @Test
    fun ensureUserProfiles_refetchesKnownUserWhenAvatarIsMissing() = runTest {
        val bobWithAvatar = User(
            id = "user-bob",
            username = "bob",
            email = null,
            avatar_url = "data:image/png;base64,avatar",
        )
        var usersByIdsCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/users/ids") -> {
                    usersByIdsCalls += 1
                    respond(
                        content = json.encodeToString(ListSerializer(User.serializer()), listOf(bobWithAvatar)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("[]", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)
        SessionStore.setSession("tok-flow", User(id = "user-flow-1", username = "flow-user"), "refresh-flow")
        repo.rememberUsers(listOf(User(id = "user-bob", username = "bob", email = null)))

        assertNull(repo.avatarUrlForUser("user-bob"))

        repo.ensureUserProfiles(listOf("user-bob"))
        repo.ensureUserProfiles(listOf("user-bob"))

        assertEquals(1, usersByIdsCalls)
        assertEquals(bobWithAvatar.avatar_url, repo.avatarUrlForUser("user-bob"))
    }

    @Test
    fun ensureUserProfiles_refetchesKnownUserWhenCustomStatusIsMissing() = runTest {
        val bobWithStatus = User(
            id = "user-bob",
            username = "bob",
            email = null,
            status = "popik",
            avatar_url = "data:image/png;base64,avatar",
        )
        var usersByIdsCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/users/ids") -> {
                    usersByIdsCalls += 1
                    respond(
                        content = json.encodeToString(ListSerializer(User.serializer()), listOf(bobWithStatus)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("[]", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)
        SessionStore.setSession("tok-flow", User(id = "user-flow-1", username = "flow-user"), "refresh-flow")
        repo.rememberUsers(
            listOf(
                User(
                    id = "user-bob",
                    username = "bob",
                    email = null,
                    avatar_url = "data:image/png;base64,old-avatar",
                ),
            ),
        )

        repo.ensureUserProfiles(listOf("user-bob"))

        assertEquals(1, usersByIdsCalls)
        assertEquals("popik", repo.knownUserProfiles.value["user-bob"]?.status)
    }

    @Test
    fun updateProfile_doesNotApplyCustomStatusLocallyWhenServerFails() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/users/me") -> respond(
                    content = """{"error":"temporary failure"}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> respond("[]", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)
        SessionStore.setSession("tok-flow", User(id = "user-flow-1", username = "flow-user"), "refresh-flow")

        assertFailsWith<com.glagolitsa.api.ApiException> {
            repo.updateProfile(
                com.glagolitsa.model.ProfileUpdateInput(
                    displayName = "",
                    position = "",
                    status = "popik",
                    bio = "",
                    avatarUrl = null,
                    presence = "online",
                ),
            )
        }

        assertNull(SessionStore.user.value?.status)
    }

    /**
     * Signal: credentials are published only after device enroll. Publishing earlier races
     * bootstrap refresh and can mark reauth while login is still finishing.
     */
    @Test
    fun login_publishesAccessTokenOnlyAfterDeviceEnroll() = runTest {
        val events = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/auth/login") -> {
                    events += "login"
                    respond(
                        content = authResponse(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/api/hello") -> respond(
                    content = """{"message":"test","server_id":"auth-test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/api/devices") &&
                    request.method == io.ktor.http.HttpMethod.Post -> {
                    // Token must still be unpublished while device enroll runs.
                    events += if (SessionStore.token.value == null) {
                        "device-enroll-before-publish"
                    } else {
                        "device-enroll-after-publish"
                    }
                    respond(
                        content = """{"device_id":"test-device-user-flow-1","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        val repo = repository(engine)
        repo.login("flow-user", "password123")

        kotlin.test.assertTrue(
            events.contains("device-enroll-before-publish"),
            "device enroll must run before SessionStore token is published: $events",
        )
        kotlin.test.assertFalse(events.contains("device-enroll-after-publish"))
        assertNotNull(SessionStore.token.value)
        kotlin.test.assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun login_wrongPassword_doesNotPersistSession() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repo = repository(engine)

        assertFailsWith<com.glagolitsa.api.ApiException> {
            repo.login("flow-user", "wrong")
        }
        assertNull(SessionStore.token.value)
    }
}
