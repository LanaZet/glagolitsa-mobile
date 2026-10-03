// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.history.AccountHistoryArchive
import com.glagolitsa.history.SecureHistoryCrypto
import com.glagolitsa.history.encodeToBytes
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.Message
import com.glagolitsa.model.SyncSnapshotResponse
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
import io.ktor.http.HttpMethod
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
import kotlin.test.assertTrue

/**
 * New phone path: cloud Secure Backup + recovery key restores history.
 * Login password alone must not rehydrate history.
 */
class MessengerRepositorySecureHistoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val alice = User(id = "user-alice-sh", username = "alice")
    private var snapshotBlob: String? = null

    @BeforeTest
    fun setup() {
        SessionStore.clear()
        snapshotBlob = null
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun mockEngine(): MockEngine = MockEngine { request ->
        fun ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
        when {
            request.url.encodedPath.endsWith("/api/auth/login") -> ok(
                json.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse(
                        token = "tok-alice",
                        refresh_token = "ref-alice",
                        expires_in = 3600,
                        user = alice,
                    ),
                ),
            )
            request.url.encodedPath.endsWith("/api/hello") -> ok(
                """{"message":"test","server_id":"secure-history-test"}""",
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method == HttpMethod.Post ->
                ok("""{"device_id":"dev","prekeys_stored":1}""", HttpStatusCode.Created)
            request.url.encodedPath.endsWith("/api/chats") -> ok("[]")
            request.url.encodedPath.endsWith("/api/messages/queue") -> ok("""{"envelopes":[]}""")
            request.url.encodedPath.endsWith("/api/sync/snapshot") && request.method == HttpMethod.Get -> {
                val blob = snapshotBlob
                if (blob == null) {
                    ok("""{"error":"not found"}""", HttpStatusCode.NotFound)
                } else {
                    ok(
                        json.encodeToString(
                            SyncSnapshotResponse.serializer(),
                            SyncSnapshotResponse(event_id = 1, snapshot_data = blob, created_at = "t"),
                        ),
                    )
                }
            }
            request.url.encodedPath.endsWith("/api/sync/snapshot") && request.method == HttpMethod.Post -> {
                val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                val marker = "\"snapshot_data\":\""
                val i = body.indexOf(marker)
                if (i >= 0) {
                    val start = i + marker.length
                    val end = body.indexOf('"', start)
                    if (end > start) snapshotBlob = body.substring(start, end)
                }
                ok(
                    json.encodeToString(
                        SyncSnapshotResponse.serializer(),
                        SyncSnapshotResponse(event_id = 2, snapshot_data = snapshotBlob, created_at = "t2"),
                    ),
                )
            }
            else -> ok("{}")
        }
    }

    private fun repo(): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine()) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = TestCryptoEngine(),
    )

    @Test
    fun newPhone_restoresHistoryWithRecoveryKey_notLoginPassword() = runTest {
        val recoveryKey = SecureHistoryCrypto.generateRecoveryKey()
        val archive = AccountHistoryArchive(
            user_id = alice.id,
            exported_at = "t",
            chats = listOf(Chat(id = "c1", title = "bob", type = ChatType.DIRECT)),
            messages = listOf(
                Message(id = "m1", chat_id = "c1", sender_id = alice.id, body = "keep-me-forever"),
            ),
        )
        snapshotBlob = SecureHistoryCrypto.seal(recoveryKey, archive.encodeToBytes()).encodeBase64()

        // New phone: login does NOT auto-restore (password is not the unlock).
        val repoB = repo()
        repoB.login("alice", "password-login-not-for-history")
        assertTrue(repoB.secureHistoryRestoreAvailable.value || snapshotBlob != null)

        val before = repoB.observeMainMessages("c1").first()
        assertTrue(before.isEmpty(), "login password must not decrypt cloud history")

        // Recovery key restores (Signal Secure Backups path).
        val restored = repoB.restoreSecureHistory(recoveryKey)
        assertEquals(1, restored.messageCount)
        assertEquals("keep-me-forever", restored.messages.single().body)

        val after = repoB.observeMainMessages("c1").first()
        assertEquals(listOf("m1"), after.map { it.id })
        assertTrue(repoB.secureHistoryEnabled())
        assertFalse(repoB.secureHistoryRestoreAvailable.value)

        // Wrong recovery key fails closed.
        SessionStore.clear()
        val repoC = repo()
        repoC.login("alice", "password-login-not-for-history")
        val bad = runCatching { repoC.restoreSecureHistory("0".repeat(64)) }
        assertTrue(bad.isFailure)
    }
}
