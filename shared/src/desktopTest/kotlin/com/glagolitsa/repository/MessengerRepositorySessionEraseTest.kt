// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.Message
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.SignedPreKeyMaterial
import com.glagolitsa.model.User
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import com.glagolitsa.util.encodeBase64
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
import kotlinx.coroutines.test.runTest
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
 * Logout / hard wipe / multi-account session erase matrix (user-visible security).
 */
class MessengerRepositorySessionEraseTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var previousHome: String? = null

    private val alice = User(id = "user-alice-erase", username = "alice", email = null)
    private val bobUser = User(id = "user-bob-erase", username = "bob", email = null)

    @BeforeTest
    fun setup() {
        SessionStore.clear()
        previousHome = System.getProperty("user.home")
        val tmp = kotlin.io.path.createTempDirectory("glag-repo-erase").toFile()
        System.setProperty("user.home", tmp.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
        previousHome?.let { System.setProperty("user.home", it) }
        previousHome = null
    }

    private fun authJson(user: User) = json.encodeToString(
        AuthResponse.serializer(),
        AuthResponse(token = "tok-${user.id}", user = user, refresh_token = "ref-${user.id}"),
    )

    private fun mockEngine(): MockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/api/auth/login") -> {
                val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                val user = if (body.contains("\"bob\"")) bobUser else alice
                respond(
                    content = authJson(user),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            path.endsWith("/api/hello") -> respond(
                content = """{"message":"test","server_id":"session-erase-test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            path.endsWith("/api/devices") -> respond(
                content = """{"device_id":"dev","prekeys_stored":1}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            path.endsWith("/api/chats") -> respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            else -> respond("{}", HttpStatusCode.OK)
        }
    }

    private fun repository(
        secure: SecureSessionStore = SecureSessionStore(),
        crypto: TestCryptoEngine = TestCryptoEngine(),
        driverFactory: DatabaseDriverFactory = DatabaseDriverFactory(),
    ): MessengerRepository = MessengerRepository(
        driverFactory = driverFactory,
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = secure,
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine()) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = crypto,
    )

    @Test
    fun logout_soft_clearsMemoryKeepsStoredCredentialsAndCrypto() = runTest {
        val crypto = TestCryptoEngine()
        val secure = SecureSessionStore()
        val repo = repository(secure = secure, crypto = crypto)
        repo.login("alice", "password123")
        crypto.ensureDeviceIdentity(alice.id)
        assertNotNull(SessionStore.token.value)
        assertNotNull(secure.loadForUser(alice.id))

        repo.logout(wipeCrypto = false)

        assertNull(SessionStore.token.value)
        assertNull(SessionStore.user.value)
        assertNull(secure.activeUserId())
        assertNotNull(secure.loadForUser(alice.id), "soft logout keeps restore credentials")
        assertEquals(0, crypto.clearDeviceIdentityCount, "soft logout must not wipe Signal identity")
        assertNotNull(crypto.ensureDeviceIdentity(alice.id))
    }

    @Test
    fun logout_hard_wipesCryptoAndStoredCredentials() = runTest {
        val crypto = TestCryptoEngine()
        val secure = SecureSessionStore()
        val key = ByteArray(32) { 1 }.encodeBase64()
        val bundle = DeviceKeyBundle(
            device_id = "remote-1",
            account_id = bobUser.id,
            registration_id = 1,
            identity_public_key = key,
            signed_prekey = SignedPreKeyMaterial(1, key, key, 1),
            pq_prekey = PqPreKeyMaterial(1, key, key, 1),
            one_time_prekey = OneTimePreKeyMaterial(1, key),
        )
        val repo = repository(secure = secure, crypto = crypto)
        repo.login("alice", "password123")
        crypto.ensureSession(alice.id, bobUser.id, "remote-1", bundle)
        assertTrue(crypto.hasSession(alice.id, bobUser.id, "remote-1"))

        repo.logout(wipeCrypto = true)

        assertNull(SessionStore.token.value)
        assertNull(secure.loadForUser(alice.id), "hard logout removes stored credentials")
        assertNull(secure.activeUserId())
        assertEquals(1, crypto.clearDeviceIdentityCount)
        assertFalse(crypto.hasSession(alice.id, bobUser.id, "remote-1"))
    }

    @Test
    fun softLogout_allowsSwitchBackToSavedAccount() = runTest {
        val secure = SecureSessionStore()
        val r1 = repository(secure = secure)
        r1.login("alice", "password123")
        r1.logout(wipeCrypto = false)

        val r2 = repository(secure = secure)
        assertTrue(r2.savedAccounts().any { it.id == alice.id })
        assertTrue(r2.switchToSavedAccount(alice.id))
        assertEquals(alice.id, SessionStore.user.value?.id)
    }

    @Test
    fun hardLogout_removesFromSavedAccounts() = runTest {
        val secure = SecureSessionStore()
        val r1 = repository(secure = secure)
        r1.login("alice", "password123")
        r1.logout(wipeCrypto = true)

        val r2 = repository(secure = secure)
        assertTrue(r2.savedAccounts().none { it.id == alice.id })
        assertFalse(r2.switchToSavedAccount(alice.id))
    }

    @Test
    fun resetSession_onCryptoEngine_dropsPartnerSession() = runTest {
        val crypto = TestCryptoEngine()
        val key = ByteArray(32) { 2 }.encodeBase64()
        val bundle = DeviceKeyBundle(
            device_id = "dev-b",
            account_id = bobUser.id,
            registration_id = 1,
            identity_public_key = key,
            signed_prekey = SignedPreKeyMaterial(1, key, key, 1),
            pq_prekey = PqPreKeyMaterial(1, key, key, 1),
            one_time_prekey = OneTimePreKeyMaterial(1, key),
        )
        crypto.ensureSession(alice.id, bobUser.id, "dev-b", bundle)
        assertTrue(crypto.hasSession(alice.id, bobUser.id, "dev-b"))
        assertNotNull(crypto.encryptMessage(alice.id, bobUser.id, "dev-b", "hi".encodeToByteArray()))

        crypto.resetSession(alice.id, bobUser.id, "dev-b")

        assertEquals(1, crypto.resetSessionCount)
        assertFalse(crypto.hasSession(alice.id, bobUser.id, "dev-b"))
        assertNull(crypto.encryptMessage(alice.id, bobUser.id, "dev-b", "hi".encodeToByteArray()))
    }

    @Test
    fun purgeExpiredMessages_isSafeWhenEmpty() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        repo.purgeExpiredMessages()
        assertNotNull(SessionStore.user.value)
        return@runTest
    }

    @Test
    fun hardLogout_doesNotWipeLocalMessageHistory() = runTest {
        val factory = DatabaseDriverFactory()
        val secure = SecureSessionStore()
        val crypto = TestCryptoEngine()
        val seed = LocalDataStore(factory)
        seed.switchAccount(alice.id)
        seed.saveChat(Chat(id = "c1", title = "t", type = ChatType.DIRECT))
        seed.saveMessage(Message(id = "m1", chat_id = "c1", sender_id = alice.id, body = "keep-me"))
        seed.close()

        val repo = repository(secure = secure, crypto = crypto, driverFactory = factory)
        repo.login("alice", "password123")
        repo.logout(wipeCrypto = true)
        assertEquals(1, crypto.clearDeviceIdentityCount)
        assertNull(secure.loadForUser(alice.id))

        // Re-open same account DB — history must still be there (no silent wipe).
        val reopen = LocalDataStore(factory)
        reopen.switchAccount(alice.id)
        assertEquals(listOf("m1"), reopen.observeMainMessages("c1").first().map { it.id })
        assertEquals("keep-me", reopen.observeMainMessages("c1").first().first().body)
        reopen.close()
    }

    @Test
    fun eraseLocalHistory_requiresUserConfirmation() = runTest {
        val repo = repository()
        repo.login("alice", "password123")
        val rejected = runCatching { repo.eraseLocalHistory(userConfirmed = false) }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun eraseLocalHistory_withConfirmation_wipesActiveDb() = runTest {
        val factory = DatabaseDriverFactory()
        val seed = LocalDataStore(factory)
        seed.switchAccount(alice.id)
        seed.saveChat(Chat(id = "c1", title = "t", type = ChatType.DIRECT))
        seed.saveMessage(Message(id = "m1", chat_id = "c1", sender_id = alice.id, body = "gone"))
        seed.close()

        val repo = repository(driverFactory = factory)
        repo.login("alice", "password123")
        repo.eraseLocalHistory(userConfirmed = true)

        val reopen = LocalDataStore(factory)
        reopen.switchAccount(alice.id)
        assertTrue(reopen.observeMainMessages("c1").first().isEmpty())
        reopen.close()
    }
}
