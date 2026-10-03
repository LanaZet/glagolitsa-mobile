// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.RoundtripTestCryptoEngine
import com.glagolitsa.crypto.TestCryptoEngine
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.OneTimePreKeyMaterial
import com.glagolitsa.model.PqPreKeyMaterial
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.SignedPreKeyMaterial
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import com.glagolitsa.util.encodeBase64
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two phones in one DM: marco ↔ Polo.
 *
 * Failures we hit in the field:
 * 1) Login on phone B auto-revoked phone A as "ghost" → A stuck reauth → outbox "SENDING"
 * 2) reauthRequired blocks processOutbox forever while UI still accepts send
 * 3) Signal multi-device keeps both actives linked until explicit unlink
 */
class MessengerRepositoryMultiDeviceSendTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val marco = User(id = "user-marco", username = "marco", email = null)
    private val frick = User(id = "user-frick", username = "Polo", email = null)
    private val chatId = "chat-marco-frick"
    private val chat = Chat(
        id = chatId,
        title = "Polo",
        type = ChatType.DIRECT,
        member_ids = listOf(marco.id, frick.id),
    )
    private val chatJson = json.encodeToString(Chat.serializer(), chat)

    private val frickKey = ByteArray(32) { 11 }.encodeBase64()
    private val frickDeviceId = "frick-device-1"
    private val frickMailbox = "mailbox-frick-1"

    private val ownDevicesByUser = mutableMapOf<String, MutableList<UserDevice>>()
    private val revokeCalls = mutableListOf<String>()
    private val registerCalls = mutableListOf<String>()
    private val capturedRelays = mutableListOf<RelayMessageRequest>()

    @BeforeTest
    fun reset() {
        SessionStore.clear()
        ownDevicesByUser.clear()
        revokeCalls.clear()
        registerCalls.clear()
        capturedRelays.clear()
    }

    @AfterTest
    fun cleanup() {
        SessionStore.clear()
    }

    private fun device(
        id: String,
        status: String,
        key: String = "k-$id",
    ) = UserDevice(
        device_id = id,
        mailbox_token = "mb-$id",
        registration_id = 1,
        identity_public_key = key,
        device_status = status,
    )

    private fun mockEngine(account: User): MockEngine = MockEngine { request ->
        fun respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

        when {
            request.url.encodedPath.endsWith("/api/auth/login") ||
                request.url.encodedPath.endsWith("/api/auth/refresh") -> respondJson(
                json.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse(
                        token = "tok-${account.id}",
                        refresh_token = "refresh-${account.id}",
                        expires_in = 3_600,
                        user = account,
                    ),
                ),
            )
            request.url.encodedPath.endsWith("/api/hello") -> respondJson(
                """{"message":"test","server_id":"multi-device-send-test"}""",
            )
            request.url.encodedPath.endsWith("/api/devices") && request.method == HttpMethod.Post -> {
                val postedId = request.headers["X-Device-Id"]
                    ?.takeIf { it.isNotBlank() }
                    ?: "test-device-${account.id}"
                registerCalls.add(postedId)
                val list = ownDevicesByUser.getOrPut(account.id) { mutableListOf() }
                val existing = list.firstOrNull { it.device_id == postedId }
                if (existing == null) {
                    list += device(postedId, "active")
                } else {
                    list.replaceAll { d ->
                        if (d.device_id == postedId) d.copy(device_status = "active") else d
                    }
                }
                respondJson(
                    """{"device_id":"$postedId","prekeys_stored":1,"one_time_prekey_ids":[301]}""",
                    HttpStatusCode.Created,
                )
            }
            request.url.encodedPath.contains("/api/devices/") && request.method == HttpMethod.Delete -> {
                val id = request.url.encodedPath.removePrefix("/api/devices/").substringBefore('/')
                revokeCalls.add(id)
                ownDevicesByUser[account.id]?.replaceAll { d ->
                    if (d.device_id == id) d.copy(device_status = "revoked") else d
                }
                respondJson("""{"status":"revoked"}""")
            }
            request.url.encodedPath.contains("/api/devices/") &&
                request.url.encodedPath.endsWith("/confirm") -> {
                val id = request.url.encodedPath
                    .substringAfter("/api/devices/")
                    .substringBefore("/confirm")
                ownDevicesByUser[account.id]?.replaceAll { d ->
                    if (d.device_id == id) d.copy(device_status = "active") else d
                }
                respondJson("""{"status":"active"}""")
            }
            request.url.encodedPath.contains("/users/") &&
                request.url.encodedPath.endsWith("/devices") -> {
                val path = request.url.encodedPath
                val devices = when {
                    path.contains(frick.id) -> listOf(
                        UserDevice(
                            device_id = frickDeviceId,
                            mailbox_token = frickMailbox,
                            registration_id = 42,
                            identity_public_key = frickKey,
                            device_status = "active",
                        ),
                    )
                    path.contains(account.id) ->
                        ownDevicesByUser[account.id].orEmpty()
                    else -> emptyList()
                }
                respondJson(
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UserDevice.serializer()),
                        devices,
                    ),
                )
            }
            request.url.encodedPath.endsWith("/api/chats") -> respondJson("[$chatJson]")
            request.url.encodedPath.endsWith("/bundle") -> {
                val deviceId = request.url.encodedPath.removeSuffix("/bundle").substringAfterLast("/")
                respondJson(
                    json.encodeToString(
                        DeviceKeyBundle.serializer(),
                        DeviceKeyBundle(
                            device_id = deviceId,
                            account_id = frick.id,
                            registration_id = 42,
                            identity_public_key = frickKey,
                            signed_prekey = SignedPreKeyMaterial(1001, frickKey, frickKey, 1_700_000_000_000),
                            pq_prekey = PqPreKeyMaterial(2001, frickKey, frickKey, 1_700_000_000_000),
                            one_time_prekey = OneTimePreKeyMaterial(301, frickKey),
                        ),
                    ),
                )
            }
            request.url.encodedPath.endsWith("/api/messages/relay") -> {
                val relay = json.decodeFromString(RelayMessageRequest.serializer(), request.bodyText())
                capturedRelays.add(relay)
                respondJson(
                    json.encodeToString(
                        RelayMessageResponse.serializer(),
                        RelayMessageResponse(
                            enqueued = relay.envelopes.size,
                            envelope_ids = relay.envelopes.indices.map { "env-md-${it + 1}" },
                        ),
                    ),
                    HttpStatusCode.Created,
                )
            }
            request.url.encodedPath.endsWith("/api/messages/queue") -> respondJson(
                """{"envelopes":[]}""",
            )
            request.url.encodedPath.endsWith("/api/mailbox/rotate") -> respondJson(
                json.encodeToString(
                    RotateMailboxResponse.serializer(),
                    RotateMailboxResponse(device_id = "dev", mailbox_token = "mb"),
                ),
            )
            else -> respondJson("{}")
        }
    }

    private fun repository(
        account: User,
        crypto: TestCryptoEngine = TestCryptoEngine(),
    ): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine(account)) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = crypto,
    )

    private fun dmRepository(
        account: User,
        crypto: RoundtripTestCryptoEngine = RoundtripTestCryptoEngine(),
    ): MessengerRepository = MessengerRepository(
        driverFactory = DatabaseDriverFactory().companionInMemory(),
        cryptoEngineFactory = CryptoEngineFactory(),
        secureSession = SecureSessionStore(),
        api = ApiClient(
            baseUrl = "https://api.test",
            httpClient = HttpClient(mockEngine(account)) {
                install(ContentNegotiation) { json(json) }
            },
        ),
        cryptoEngine = crypto,
    )

    private suspend fun messageByBody(repo: MessengerRepository, body: String) =
        repo.observeMainMessages(chatId)
            .first { messages -> messages.any { it.body.contains(body) } }
            .single { it.body.contains(body) }

    // --- Signal multi-device: second phone must not unlink the first ---

    @Test
    fun secondPhoneLogin_doesNotRevokeFirstPhone_bothStayActive() = runTest {
        val phoneA = TestCryptoEngine(installLabel = "phoneA")
        val phoneB = TestCryptoEngine(installLabel = "phoneB")
        ownDevicesByUser[marco.id] = mutableListOf()

        // Phone A (e.g. physical) — first install for marco.
        SessionStore.clear()
        repository(marco, phoneA).login("marco", "password123")
        val idA = phoneA.currentDeviceId(marco.id)!!
        assertTrue(idA.contains("phoneA"), "expected distinct phoneA device id, got $idA")
        assertTrue(
            ownDevicesByUser[marco.id].orEmpty().any {
                it.device_id == idA && it.device_status == "active"
            },
            "phone A active after login: ${ownDevicesByUser[marco.id]}",
        )

        // Phone B (e.g. emulator / second handset) logs in while A is already active.
        SessionStore.clear()
        revokeCalls.clear()
        registerCalls.clear()
        repository(marco, phoneB).login("marco", "password123")
        val idB = phoneB.currentDeviceId(marco.id)!!
        assertTrue(idB.contains("phoneB"), "expected distinct phoneB device id, got $idB")
        assertTrue(idA != idB, "two phones must have distinct DeviceIDs")

        assertFalse(
            revokeCalls.contains(idA),
            "Signal multi-device: login on B must not revoke A. revokeCalls=$revokeCalls",
        )
        assertTrue(revokeCalls.isEmpty(), "no auto-revoke on multi-device login: $revokeCalls")
        assertTrue(
            ownDevicesByUser[marco.id].orEmpty().any {
                it.device_id == idA && it.device_status == "active"
            },
            "phone A still active on server: ${ownDevicesByUser[marco.id]}",
        )
        assertTrue(
            ownDevicesByUser[marco.id].orEmpty().any {
                it.device_id == idB && it.device_status == "active"
            } || registerCalls.contains(idB),
            "phone B registered/active: devices=${ownDevicesByUser[marco.id]} registers=$registerCalls",
        )
        assertNotNull(SessionStore.token.value)
        assertFalse(SessionStore.reauthRequired.value)
    }

    @Test
    fun threeDeviceLogins_neverAutoRevokeSiblings() = runTest {
        ownDevicesByUser[marco.id] = mutableListOf()
        val engines = listOf(
            TestCryptoEngine(installLabel = "p1"),
            TestCryptoEngine(installLabel = "p2"),
            TestCryptoEngine(installLabel = "p3"),
        )
        val ids = mutableListOf<String>()

        for (engine in engines) {
            SessionStore.clear()
            revokeCalls.clear()
            repository(marco, engine).login("marco", "password123")
            ids += engine.currentDeviceId(marco.id)!!
            assertTrue(revokeCalls.isEmpty(), "auto-revoke on login #$ids: $revokeCalls")
        }

        assertEquals(3, ids.toSet().size, "three distinct DeviceIDs: $ids")
        val actives = ownDevicesByUser[marco.id].orEmpty()
            .filter { (it.device_status ?: "active") == "active" }
            .map { it.device_id }
            .toSet()
        assertTrue(
            ids.all { it in actives },
            "expected all phones active; ids=$ids actives=$actives full=${ownDevicesByUser[marco.id]}",
        )
        assertEquals(0, revokeCalls.size)
    }

    // --- Outbox / SENDING stuck when reauthRequired (field bug) ---

    @Test
    fun processOutbox_whenReauthRequired_doesNotRelay_keepsMessageSending() = runTest {
        ownDevicesByUser[marco.id] = mutableListOf()
        val repo = dmRepository(marco)
        repo.login("marco", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, frick.id)

        // Enqueue + deliver once to prove path works.
        val ok = repo.sendMessage(chatId, "before_reauth")
        assertEquals(MessageStatus.SENT, ok.status)
        assertEquals(1, capturedRelays.size)
        capturedRelays.clear()

        // Simulate field state after sibling device revoked us / refresh died.
        SessionStore.markReauthRequired()
        assertTrue(SessionStore.reauthRequired.value)
        assertEquals(null, SessionStore.token.value)

        val stuck = repo.sendMessage(chatId, "stuck_sending")
        assertEquals(0, capturedRelays.size, "must not relay while reauth required")
        assertEquals(
            MessageStatus.SENDING,
            messageByBody(repo, "stuck_sending").status,
            "UI shows SENDING until re-login drains outbox",
        )
        assertEquals(MessageStatus.SENDING, stuck.status)

        // processOutbox alone still deferred.
        repo.processOutbox()
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENDING, messageByBody(repo, "stuck_sending").status)
    }

    @Test
    fun afterReLogin_clearsReauth_andDrainsStuckOutbox() = runTest {
        ownDevicesByUser[marco.id] = mutableListOf()
        val crypto = RoundtripTestCryptoEngine()
        val secure = SecureSessionStore()
        val repo = MessengerRepository(
            driverFactory = DatabaseDriverFactory().companionInMemory(),
            cryptoEngineFactory = CryptoEngineFactory(),
            secureSession = secure,
            api = ApiClient(
                baseUrl = "https://api.test",
                httpClient = HttpClient(mockEngine(marco)) {
                    install(ContentNegotiation) { json(json) }
                },
            ),
            cryptoEngine = crypto,
        )

        repo.login("marco", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, frick.id)

        SessionStore.markReauthRequired()
        repo.sendMessage(chatId, "queued_while_reauth")
        assertEquals(0, capturedRelays.size)
        assertEquals(MessageStatus.SENDING, messageByBody(repo, "queued_while_reauth").status)

        // User re-enters password (same install) — Signal: full re-auth then jobs retry.
        SessionStore.clear()
        // Keep crypto + local DB; only session store was cleared above for login.
        // Local chats still in SQLite for this account after switchAccount on login.
        repo.login("marco", "password123")
        assertFalse(SessionStore.reauthRequired.value)
        assertNotNull(SessionStore.token.value)

        // Re-bind chat map after login (chat list restore).
        repo.syncChats()
        repo.ensureDmSession(chatId, frick.id)
        repo.processOutbox()

        assertTrue(
            capturedRelays.isNotEmpty() ||
                messageByBody(repo, "queued_while_reauth").status == MessageStatus.SENT,
            "after re-login outbox should drain; relays=${capturedRelays.size} " +
                "status=${messageByBody(repo, "queued_while_reauth").status}",
        )
    }

    @Test
    fun marcoToFrick_happyPath_deliversWhenSessionActive() = runTest {
        ownDevicesByUser[marco.id] = mutableListOf()
        val repo = dmRepository(marco)
        repo.login("marco", "password123")
        repo.syncChats()
        repo.ensureDmSession(chatId, frick.id)

        val sent = repo.sendMessage(chatId, "hello_frick")
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(1, capturedRelays.size)
        assertFalse(SessionStore.reauthRequired.value)
        assertTrue(revokeCalls.isEmpty(), "send path must not revoke own devices: $revokeCalls")
    }

    @Test
    fun secondPhoneLogin_thenSend_stillWorksWithoutRevokingFirst() = runTest {
        val phoneA = TestCryptoEngine(installLabel = "phoneA")
        val phoneBCrypto = RoundtripTestCryptoEngine(installLabel = "phoneB")
        ownDevicesByUser[marco.id] = mutableListOf()

        SessionStore.clear()
        repository(marco, phoneA).login("marco", "password123")
        val idA = phoneA.currentDeviceId(marco.id)!!

        SessionStore.clear()
        revokeCalls.clear()
        val repoB = dmRepository(marco, phoneBCrypto)
        repoB.login("marco", "password123")
        assertFalse(revokeCalls.contains(idA), "B must not revoke A before send")

        repoB.syncChats()
        repoB.ensureDmSession(chatId, frick.id)
        val sent = repoB.sendMessage(chatId, "from_phone_b")
        assertEquals(MessageStatus.SENT, sent.status)
        assertTrue(capturedRelays.isNotEmpty())
        assertFalse(revokeCalls.contains(idA), "B must not revoke A during send: $revokeCalls")
        assertTrue(
            ownDevicesByUser[marco.id].orEmpty().any {
                it.device_id == idA && it.device_status == "active"
            },
            "A remains active after B send: ${ownDevicesByUser[marco.id]}",
        )
    }
}

private fun HttpRequestData.bodyText(): String =
    (body as? io.ktor.http.content.TextContent)?.text ?: ""
