// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.api.ApiException
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.User
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionNavigationPolicy
import com.glagolitsa.session.SessionStore
import com.glagolitsa.session.StoredCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session restore + "do not kick user out of chat" matrix.
 *
 * Structure follows practices from:
 * - Element X: restore stored credentials → still logged in; soft reauth keeps local data
 * - Mattermost mobile: cold start / process death keeps session when credentials valid
 * - Telegram clients: proactive/maintain refresh without full logout
 * - Signal: unauthorized mid-flow suspends token, keeps local identity + history
 *
 * Primary product invariants:
 * 1) Valid stored session → restoreSession true, user + token, chats still local
 * 2) Expired access + valid refresh → refresh, stay logged in
 * 3) Dead refresh → soft reauth, user + local chats kept (not hard wipe)
 * 4) Transient 401 / clearAccessToken → must not look like full logout for navigation policy
 */
class MessengerRepositorySessionRestoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var secureSession: SecureSessionStore
    private var previousHome: String? = null
    private val alice = User(id = "user-alice", username = "alice", email = null)
    private val bob = User(id = "user-bob", username = "bob", email = null)
    private val chat = Chat(
        id = "chat-alice-bob",
        title = "bob",
        type = ChatType.DIRECT,
        member_ids = listOf(alice.id, bob.id),
    )

    private var refreshBehavior: RefreshBehavior = RefreshBehavior.Unauthorized
    private var refreshCalls = 0
    private var helloServerId = "test-server"

    private enum class RefreshBehavior {
        Unauthorized,
        Success,
        /** Transport/timeout style failure (must not crash UI / reinstall cold start). */
        NetworkError,
    }

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        previousHome = System.getProperty("user.home")
        val tmp = kotlin.io.path.createTempDirectory("glag-session-restore").toFile()
        System.setProperty("user.home", tmp.absolutePath)
        secureSession = SecureSessionStore()
        refreshBehavior = RefreshBehavior.Unauthorized
        refreshCalls = 0
        helloServerId = "test-server"
        runBlocking { secureSession.clear() }
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
        runBlocking { runCatching { secureSession.clear() } }
        previousHome?.let { System.setProperty("user.home", it) }
        previousHome = null
    }

    // --- Happy path cold start (Mattermost / Element) ---

    @Test
    fun restoreSession_withValidStoredCredentials_restoresOnlineSessionAndChats() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        // Simulate process death: memory wiped, secure store still has credentials.
        SessionStore.clear()
        assertNull(SessionStore.user.value)

        val restored = repo.restoreSession()

        assertTrue(restored)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertNotNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
        assertTrue(SessionNavigationPolicy.initialRouteIsMain(sessionRestored = true))
        assertFalse(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = SessionStore.token.value != null,
                reauthRequired = false,
            ),
        )
    }

    @Test
    fun restoreSession_withExpiredAccessAndValidRefresh_refreshesAndStaysLoggedIn() = runBlocking {
        refreshBehavior = RefreshBehavior.Success
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-ok",
                expiresAtEpochMs = currentTimeMillis() - 60_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.clear()

        val restored = repo.restoreSession()

        assertTrue(restored)
        assertTrue(refreshCalls >= 1, "expired access must call /auth/refresh")
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertEquals("access-refreshed", SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
        assertTrue(
            SessionNavigationPolicy.shouldKeepAuthenticatedShell(
                hasUser = true,
                reauthRequired = false,
            ),
        )
    }

    // --- Soft failure paths already critical for product ---

    @Test
    fun restoreSession_whenRefreshIsUnauthorized_routesToSoftReauthAndKeepsLocalChats() = runBlocking {
        val repo = repository()

        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-stale",
                expiresAtEpochMs = currentTimeMillis() - 60_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.clear()

        val restored = repo.restoreSession()

        assertTrue(restored)
        assertNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertTrue(SessionStore.reauthRequired.value)
        assertNull(secureSession.load()?.refreshToken)
        assertEquals("", secureSession.load()?.accessToken)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
        // Dead refresh must ask for password again, but local history stays available on disk.
        assertTrue(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = false,
                reauthRequired = true,
            ),
        )
    }

    @Test
    fun processMessageQueue_whenRefreshIsUnauthorizedRoutesToSoftReauth() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-stale",
                expiresAtEpochMs = currentTimeMillis() - 60_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("stale-access", alice, "refresh-stale")

        repo.processMessageQueue(chat.id, force = true)

        assertNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertTrue(SessionStore.reauthRequired.value)
        assertNull(secureSession.load()?.refreshToken)
        assertEquals("", secureSession.load()?.accessToken)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun recoverAuthFailure_forcesRefreshEvenWhenStaleTokenIsStillInMemory() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-stale",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("stale-access", alice, "refresh-stale")

        val handled = repo.recoverAuthFailure(ApiException(HttpStatusCode.Unauthorized, "unauthorized"))

        assertTrue(handled)
        assertNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals("", secureSession.load()?.accessToken)
        assertEquals("refresh-stale", secureSession.load()?.refreshToken)
    }

    @Test
    fun recoverAuthFailure_doesNotRepublishDeadAccessTokenOnNextRecover() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-stale",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("stale-access", alice, "refresh-stale")

        assertTrue(repo.recoverAuthFailure(ApiException(HttpStatusCode.Unauthorized, "unauthorized")))
        assertNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals("", secureSession.load()?.accessToken)
        assertEquals("refresh-stale", secureSession.load()?.refreshToken)

        SessionStore.clearAccessToken()
        val recovered = repo.tryRecoverSession(forceRefresh = false)

        assertFalse(recovered)
        assertNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertTrue(SessionStore.reauthRequired.value)
    }

    @Test
    fun recoverAuthFailure_withValidRefresh_restoresTokenWithoutLeavingShell() = runBlocking {
        refreshBehavior = RefreshBehavior.Success
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "stale-access",
                refreshToken = "refresh-ok",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("stale-access", alice, "refresh-ok")

        val handled = repo.recoverAuthFailure(ApiException(HttpStatusCode.Unauthorized, "unauthorized"))

        assertTrue(handled)
        assertEquals("access-refreshed", SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
        assertFalse(
            SessionNavigationPolicy.shouldLeaveChatForAuth(
                hasUser = true,
                hasAccessToken = true,
                reauthRequired = false,
            ),
            "successful 401 recovery must keep user in chat",
        )
    }

    @Test
    fun tryRecoverSession_restoresValidStoredAccessTokenWithoutRefresh() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "valid-stored-access",
                refreshToken = null,
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setUserOnly(alice)
        SessionStore.clearAccessToken()

        val recovered = repo.tryRecoverSession()

        assertTrue(recovered)
        assertEquals("valid-stored-access", SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
    }

    /**
     * Reinstall via adb install -r keeps EncryptedSharedPreferences session.
     * If /auth/refresh times out, app must not crash — keep stored access when still valid.
     */
    @Test
    fun tryRecoverSession_whenRefreshTimesOut_keepsStoredAccessAndDoesNotThrow() = runBlocking {
        refreshBehavior = RefreshBehavior.NetworkError
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "still-valid-access",
                refreshToken = "refresh-ok",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setUserOnly(alice)
        SessionStore.clearAccessToken()

        val recovered = repo.tryRecoverSession(forceRefresh = true)

        assertTrue(recovered, "must fall back to stored access after refresh timeout")
        assertEquals("still-valid-access", SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun bootstrapRestoredSession_whenRefreshTimesOut_doesNotThrow() = runBlocking {
        refreshBehavior = RefreshBehavior.NetworkError
        val repo = repository()
        repo.login("alice", "password")
        secureSession.save(
            boundCredentials(
                accessToken = "still-valid-access",
                refreshToken = "refresh-ok",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("still-valid-access", alice, "refresh-ok")

        // Was crashing MainActivity via uncaught HttpRequestTimeoutException in LaunchedEffect.
        repo.bootstrapRestoredSession()

        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
    }

    /**
     * Signal-style single credential authority: wiping only SessionStore.token must not create a
     * split brain (withAuth works from disk, outbox thinks “no session”). Any auth probe publishes
     * disk credentials back into SessionStore.
     */
    @Test
    fun authPath_publishesDiskAccessTokenIntoSessionStore_whenMemoryCleared() = runBlocking {
        refreshBehavior = RefreshBehavior.Success
        val repo = repository()
        repo.login("alice", "password")
        assertNotNull(SessionStore.token.value)

        val stored = secureSession.load()!!
        secureSession.save(
            stored.copy(
                accessToken = "disk-access-still-valid",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
            ),
        )
        SessionStore.clearAccessToken()
        SessionStore.clearReauthRequired()
        assertNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)

        // processMessageQueue → withAuth → SessionTokenProvider.publishAccessToken
        repo.processMessageQueue(force = true)

        assertEquals(
            "disk-access-still-valid",
            SessionStore.token.value,
            "SessionTokenProvider must be the single source of truth for access tokens",
        )
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun bootstrap_afterFreshLogin_doesNotForceRefreshWhenAccessStillValid() = runBlocking {
        refreshBehavior = RefreshBehavior.Unauthorized
        val repo = repository()
        repo.login("alice", "password")
        val tokenBefore = SessionStore.token.value
        assertNotNull(tokenBefore)
        val refreshBefore = refreshCalls

        // Fresh login: access not expired — bootstrap must not hammer /auth/refresh
        // (failed refresh would mark reauth and break outbox while UI still looks logged in).
        repo.bootstrapRestoredSession()

        assertEquals(tokenBefore, SessionStore.token.value)
        assertEquals(refreshBefore, refreshCalls, "must not force-refresh a fresh access token")
        assertFalse(SessionStore.reauthRequired.value)
    }

    /**
     * Login must leave reauth clear and a usable access token so outbox/queue can run
     * (field: ✓ on sender but reauth deferred outbox / no receive poll).
     */
    @Test
    fun login_leavesSessionActiveForSendAndReceive() = runBlocking {
        refreshBehavior = RefreshBehavior.Success
        val repo = repository()
        repo.login("alice", "password")

        assertFalse(SessionStore.reauthRequired.value)
        assertNotNull(SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        // Stale concurrent recover with dead refresh token must not apply if tokens differ
        // (covered in refreshAccessToken stale rejection); session stays usable for jobs.
        assertTrue(
            SessionNavigationPolicy.shouldKeepAuthenticatedShell(
                hasUser = true,
                reauthRequired = false,
            ),
        )
    }

    @Test
    fun maintainSession_whenTokenHealthy_doesNotClearUserOrChats() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        val tokenBefore = SessionStore.token.value
        assertNotNull(tokenBefore)

        repo.maintainSession()

        assertEquals(tokenBefore, SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun maintainSession_whenAccessNearExpiry_refreshesProactivelyLikeTelegram() = runBlocking {
        refreshBehavior = RefreshBehavior.Success
        val repo = repository()
        repo.login("alice", "password")

        // Within SESSION_PROACTIVE_REFRESH_SKEW_MS (60s) but still "has token in memory".
        secureSession.save(
            boundCredentials(
                accessToken = SessionStore.token.value ?: "access-alice",
                refreshToken = "refresh-ok",
                expiresAtEpochMs = currentTimeMillis() + 30_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession(
            SessionStore.token.value ?: "access-alice",
            alice,
            "refresh-ok",
        )

        repo.maintainSession()

        assertTrue(refreshCalls >= 1, "proactive maintainSession should refresh near expiry")
        assertEquals("access-refreshed", SessionStore.token.value)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun maintainSession_whenNearExpiryRefreshIsUnauthorized_keepsUsableAccessToken() = runBlocking {
        refreshBehavior = RefreshBehavior.Unauthorized
        val repo = repository()
        repo.login("alice", "password")

        secureSession.save(
            boundCredentials(
                accessToken = "access-alice",
                refreshToken = "refresh-stale",
                expiresAtEpochMs = currentTimeMillis() + 30_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.setSession("access-alice", alice, "refresh-stale")

        repo.maintainSession()

        assertTrue(refreshCalls >= 1, "near-expiry session should try proactive refresh")
        assertEquals("access-alice", SessionStore.token.value)
        assertEquals("access-alice", secureSession.load()?.accessToken)
        assertEquals(alice.id, SessionStore.user.value?.id)
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun restoreSession_withoutCredentialsButWithCachedUser_doesNotTrustUnboundOfflineShell() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        // Wipe secure credentials but leave local DB. Local history may remain on disk,
        // but a cached user alone is not a server-bound session.
        secureSession.clear()
        SessionStore.clear()

        val restored = repo.restoreSession()

        assertFalse(restored, "cached local user alone must not restore a server-bound session")
        assertNull(SessionStore.user.value)
        assertNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun restoreSession_withLegacyUnboundCredentials_doesNotCallOldServerTokens() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        secureSession.save(
            StoredCredentials(
                accessToken = "legacy-access",
                refreshToken = "legacy-refresh",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
            ),
        )
        SessionStore.clear()
        refreshCalls = 0

        val restored = repo.restoreSession()

        assertFalse(restored, "unbound legacy credentials must not restore after backend reset")
        assertEquals(0, refreshCalls, "legacy refresh token must not be sent to the current server")
        assertNull(SessionStore.user.value)
        assertNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun restoreSession_whenServerIdentityChanges_clearsBoundSession() = runBlocking {
        val repo = repository()
        repo.login("alice", "password")
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })

        secureSession.save(
            boundCredentials(
                accessToken = "old-server-access",
                refreshToken = "old-server-refresh",
                expiresAtEpochMs = currentTimeMillis() + 3_600_000L,
                userId = alice.id,
                username = alice.username,
                serverId = "old-server",
            ),
        )
        SessionStore.clear()
        refreshCalls = 0

        val restored = repo.restoreSession()

        assertFalse(restored, "credentials bound to another server must be rejected")
        assertEquals(0, refreshCalls, "refresh token from another server must not be used")
        assertNull(SessionStore.user.value)
        assertNull(SessionStore.token.value)
        assertNull(secureSession.loadForUser(alice.id))
        assertEquals(listOf(chat.id), repo.observeChats().first { it.isNotEmpty() }.map { it.id })
    }

    private fun repository(): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = secureSession,
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine()) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = TestCryptoEngine(),
    )

    private fun boundCredentials(
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMs: Long,
        userId: String? = null,
        username: String? = null,
        apiBaseUrl: String = "https://api.test",
        serverId: String = "test-server",
        serverConfirmedAtEpochMs: Long = currentTimeMillis(),
    ): StoredCredentials = StoredCredentials(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMs = expiresAtEpochMs,
        userId = userId,
        username = username,
        apiBaseUrl = apiBaseUrl,
        serverId = serverId,
        serverConfirmedAtEpochMs = serverConfirmedAtEpochMs,
    )

    private fun mockEngine(): MockEngine = MockEngine { request ->
        fun respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

        when {
            request.url.encodedPath.endsWith("/api/hello") -> respondJson(
                """{"message":"test","server_id":"$helloServerId"}""",
            )
            request.url.encodedPath.endsWith("/api/auth/login") -> respondJson(
                json.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse(
                        token = "access-alice",
                        refresh_token = "refresh-alice",
                        expires_in = 3_600,
                        user = alice,
                    ),
                ),
            )
            request.url.encodedPath.endsWith("/api/auth/refresh") -> {
                refreshCalls++
                when (refreshBehavior) {
                    RefreshBehavior.Unauthorized -> respondJson(
                        """{"error":"session expired"}""",
                        HttpStatusCode.Unauthorized,
                    )
                    RefreshBehavior.Success -> respondJson(
                        json.encodeToString(
                            AuthResponse.serializer(),
                            AuthResponse(
                                token = "access-refreshed",
                                refresh_token = "refresh-rotated",
                                expires_in = 3_600,
                                user = alice,
                            ),
                        ),
                    )
                    RefreshBehavior.NetworkError ->
                        error("connection timeout simulating HttpRequestTimeoutException")
                }
            }
            request.url.encodedPath.endsWith("/api/users/me") -> {
                val auth = request.headers[HttpHeaders.Authorization].orEmpty()
                if (auth.contains("stale-access")) {
                    respondJson("""{"error":"session expired"}""", HttpStatusCode.Unauthorized)
                } else {
                    respondJson(json.encodeToString(User.serializer(), alice))
                }
            }
            request.url.encodedPath.endsWith("/api/devices") && request.method.value == "POST" -> respondJson(
                """{"device_id":"test-device-user-alice","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                HttpStatusCode.Created,
            )
            request.url.encodedPath.endsWith("/api/messages/queue") -> respondJson(
                """{"envelopes":[]}""",
            )
            request.url.encodedPath.endsWith("/api/chats") -> respondJson(
                json.encodeToString(ListSerializer(Chat.serializer()), listOf(chat)),
            )
            else -> respondJson("{}")
        }
    }
}
