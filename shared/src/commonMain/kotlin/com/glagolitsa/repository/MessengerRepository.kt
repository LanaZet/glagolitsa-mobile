// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.api.ApiException
import com.glagolitsa.auth.AccountRecoveryKey
import com.glagolitsa.passkey.PasskeyClient
import com.glagolitsa.passkey.PasskeyRecordStore
import com.glagolitsa.auth.DeviceRegistrationException
import com.glagolitsa.auth.RegisterProgress
import com.glagolitsa.auth.RegistrationPow
import com.glagolitsa.auth.RegistrationValidation
import com.glagolitsa.auth.RegistrationValidationException
import com.glagolitsa.backup.CloudBackupInfo
import com.glagolitsa.backup.EncryptedBackupExporter
import com.glagolitsa.backup.EncryptedBackupImporter
import com.glagolitsa.backup.EncryptedBackupResult
import com.glagolitsa.history.AccountHistoryArchive
import com.glagolitsa.history.SecureHistoryCrypto
import com.glagolitsa.history.decodeAccountHistoryArchive
import com.glagolitsa.history.encodeToBytes
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.currentIsoTimestamp
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.formatIsoFromMillis
import com.glagolitsa.parseIsoTimestampMillis
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.crypto.EncryptedPayload
import com.glagolitsa.crypto.FileAttachmentCrypto
import com.glagolitsa.crypto.SIGNAL_ENVELOPE_SENDER_KEY
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.jobs.BackgroundSyncBridge

import com.glagolitsa.jobs.OutboxJobRecord
import com.glagolitsa.jobs.OutboxJobStatus
import com.glagolitsa.jobs.OutboxJobType
import com.glagolitsa.jobs.OutboxProcessingResult
import com.glagolitsa.jobs.OutboxRecoveryResult
import com.glagolitsa.jobs.OutboxSendPayload
import com.glagolitsa.jobs.OUTBOX_MAX_ATTEMPTS
import com.glagolitsa.jobs.OutboxRecoverableErrors
import com.glagolitsa.jobs.decodeOutboxPayload
import com.glagolitsa.jobs.encodeOutboxPayload
import com.glagolitsa.jobs.outboxRetryDelayMs
import com.glagolitsa.jobs.scheduleBackgroundSync
import com.glagolitsa.log.AppLog
import com.glagolitsa.media.AttachmentCache
import com.glagolitsa.media.AttachmentCacheStats
import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.media.AttachmentMediaValidator
import com.glagolitsa.media.AttachmentRetentionPolicy
import com.glagolitsa.media.AttachmentThumbnailer
import com.glagolitsa.media.AttachmentTransferManager
import com.glagolitsa.media.attachmentCacheIdForOutgoing
import com.glagolitsa.media.attachmentCacheIdForRemote
import com.glagolitsa.media.loadForeverMediaCacheChatIds
import com.glagolitsa.media.resolveAttachmentKind
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.UndeliveredMessagePolicy
import com.glagolitsa.crypto.SafetyNumberInfo
import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatDeletePolicy
import com.glagolitsa.model.ChatListSearch
import com.glagolitsa.model.ChatUpdatedData
import com.glagolitsa.model.ChatVisibility
import com.glagolitsa.model.ClientUpdatePolicy
import com.glagolitsa.model.CreateChannelRequest
import com.glagolitsa.model.CreateChatRequest
import com.glagolitsa.model.CreateDMRequest
import com.glagolitsa.model.CreateGroupInviteRequest
import com.glagolitsa.model.GroupInviteDto
import com.glagolitsa.model.GroupInvitePreviewDto
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.MembershipChangeDto
import com.glagolitsa.model.UpdateChatRequest
import com.glagolitsa.model.UpdateGroupSettingsRequest
import com.glagolitsa.model.GlagolitsaInviteLink
import com.glagolitsa.model.GlagolitsaInviteTarget
import com.glagolitsa.model.parseInviteToken
import com.glagolitsa.model.EnvelopeNewData
import com.glagolitsa.model.FavoriteMessageItem
import com.glagolitsa.model.FavoriteMessageRef
import com.glagolitsa.model.FavoriteMessagesLogic
import com.glagolitsa.model.LoginRequest
import com.glagolitsa.model.RecoveryCompleteRequest
import com.glagolitsa.model.RecoveryCompleteResponse
import com.glagolitsa.model.RecoverySetupRequest
import com.glagolitsa.model.RecoveryStatusResponse
import com.glagolitsa.model.RecoveryTicketResponse
import com.glagolitsa.model.RecoveryVerifyRequest
import com.glagolitsa.model.TrustedRecoveryApproveRequest
import com.glagolitsa.model.TrustedRecoveryPendingItem
import com.glagolitsa.model.TrustedRecoveryPollRequest
import com.glagolitsa.model.TrustedRecoveryStartRequest
import com.glagolitsa.model.TrustedRecoveryStartResponse
import com.glagolitsa.model.WebAuthnBeginRequest
import com.glagolitsa.model.WebAuthnFinishRequest
import com.glagolitsa.security.LocalAuthAvailability
import com.glagolitsa.security.LocalAuthResult
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageEditPolicy
import com.glagolitsa.model.MessagePinPolicy
import com.glagolitsa.model.MessageReaction
import com.glagolitsa.model.MessageReplyPolicy
import com.glagolitsa.model.MessageRelationDraft
import com.glagolitsa.model.MessageDeliverySemantics
import com.glagolitsa.model.MessageNewData
import com.glagolitsa.model.MessagesPageResponse
import com.glagolitsa.model.MediaContentMode
import com.glagolitsa.model.MediaFileInfo
import com.glagolitsa.model.ReactionSummary
import com.glagolitsa.model.canPublishPosts
import com.glagolitsa.model.ReadReceiptPolicy
import com.glagolitsa.model.SendDedupPolicy
import com.glagolitsa.model.NotificationPreferences
import com.glagolitsa.model.NotificationSettingsPolicy
import com.glagolitsa.model.UpdateNotificationPreferencesRequest
import com.glagolitsa.model.toggleMine
import com.glagolitsa.model.MESSAGE_VISIBILITY_MAIN
import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.CallControlEventData
import com.glagolitsa.model.CallPresenceData
import com.glagolitsa.model.PresenceUserData
import com.glagolitsa.model.QueuedEnvelope
import com.glagolitsa.model.RefreshRequest
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.RegisterPushTokenRequest
import com.glagolitsa.model.RegisterRequest
import com.glagolitsa.model.ReplenishPrekeysRequest
import com.glagolitsa.model.RelayEnvelopeRequest
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.SaveSyncSnapshotRequest
import com.glagolitsa.model.SendMessageRequest
import com.glagolitsa.model.ThreadBranchSummary
import com.glagolitsa.util.sha256Hex
import com.glagolitsa.util.decodeBase64
import com.glagolitsa.util.encodeBase64
import com.glagolitsa.model.ProfileUpdateInput
import com.glagolitsa.model.UpdateProfileRequest
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.model.buildThreadBranchSummaries
import com.glagolitsa.model.deliveryStatus
import com.glagolitsa.model.displayLabel
import com.glagolitsa.model.isSending
import com.glagolitsa.model.sortedForChat
import com.glagolitsa.metadata.PairwiseIdStore
import com.glagolitsa.metadata.PAYLOAD_KIND_DM_DELETE
import com.glagolitsa.metadata.PAYLOAD_KIND_DM_READ_RECEIPT
import com.glagolitsa.metadata.PAYLOAD_KIND_GROUP_SKDM
import com.glagolitsa.metadata.SealedAttachmentRef
import com.glagolitsa.metadata.SealedDmPayload
import com.glagolitsa.metadata.SealedGroupPayload
import com.glagolitsa.metadata.decodeSealedDmPayload
import com.glagolitsa.metadata.decodeSealedGroupPayload
import com.glagolitsa.metadata.encodeSealedDmPayload
import com.glagolitsa.metadata.encodeSealedGroupPayload
import com.glagolitsa.metadata.extractPairwiseId
import com.glagolitsa.metadata.prependPairwiseId
import com.glagolitsa.model.WsEvent
import com.glagolitsa.model.WsEventType
import com.glagolitsa.platform.AppRuntimeInfo
import com.glagolitsa.push.PushTokenProvider
import com.glagolitsa.push.PushWakeCoordinator
import com.glagolitsa.push.PushWakeResult
import com.glagolitsa.platform.NetworkPathMonitor
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionStore
import com.glagolitsa.session.SESSION_PROACTIVE_REFRESH_SKEW_MS
import com.glagolitsa.session.BiometricUnlockCandidate
import com.glagolitsa.session.NotAuthenticatedException
import com.glagolitsa.session.SessionExpiredException
import com.glagolitsa.session.SessionTokenProvider
import com.glagolitsa.session.StoredCredentials
import com.glagolitsa.session.hasUsableAccessToken
import com.glagolitsa.session.isAuthRefreshFailure
import com.glagolitsa.session.isExpired
import com.glagolitsa.session.requiresRefresh
import io.ktor.http.HttpStatusCode
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.api.WebSocketTransport
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

data class OwnDeviceList(
    val currentDeviceId: String,
    val devices: List<UserDevice>,
)

private fun deviceStatusSortOrder(status: String?): Int =
    when (status?.lowercase() ?: "active") {
        "active" -> 0
        "pending" -> 1
        "revoked" -> 2
        else -> 3
    }

internal fun incomingRelayMessageId(clientMessageId: String?, envelopeId: String): String =
    clientMessageId?.takeIf { it.isNotBlank() } ?: envelopeId

/**
 * Единая точка доступа к API и локальному кэшу (SQLDelight).
 *
 * Поток данных: API/relay → decrypt → LocalDataStore → Flow → UI.
 * SessionStore держит текущую сессию в памяти для быстрого доступа.
 */
class MessengerRepository(
    driverFactory: DatabaseDriverFactory,
    cryptoEngineFactory: CryptoEngineFactory? = null,
    private val secureSession: SecureSessionStore,
    private val api: ApiClient = ApiClient(),
    private val pushTokenProvider: PushTokenProvider = PushTokenProvider(),
    cryptoEngine: CryptoEngine? = null,
    attachmentCache: AttachmentCache? = null,
) {
    private val local: LocalDataStore by lazy { LocalDataStore(driverFactory) }
    private val persistentAttachmentCache: AttachmentCache by lazy {
        attachmentCache ?: AttachmentCache(local)
    }
    private val attachmentTransferManager: AttachmentTransferManager by lazy {
        AttachmentTransferManager(api, persistentAttachmentCache)
    }
    private val pairwiseIds: PairwiseIdStore by lazy { PairwiseIdStore(local) }
    private val crypto: CryptoEngine by lazy {
        cryptoEngine
        ?: cryptoEngineFactory?.create()
        ?: error("CryptoEngineFactory or CryptoEngine must be provided")
    }
    private val hasMoreByChat = mutableMapOf<String, Boolean>()

    private val passkeys: PasskeyClient by lazy {
        PasskeyClient(object : PasskeyRecordStore {
            override suspend fun loadJson(): String = local.loadSetting(KEY_PASSKEYS)
            override suspend fun saveJson(value: String) {
                local.saveSetting(KEY_PASSKEYS, value)
            }
        })
    }

    private val auth = SessionTokenProvider(secureSession) {
        refreshAccessToken(SessionStore.user.value ?: error("Not authenticated"))
    }

    val calls = CallController(api, { crypto }, { usernameForSender(it) }, auth)
    val presence = PresenceController(api, { crypto }, auth)

    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val incomingMessages: SharedFlow<Message> = _incomingMessages.asSharedFlow()

    private val _identityKeyChanges = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val identityKeyChanges: SharedFlow<String> = _identityKeyChanges.asSharedFlow()

    private val _untrustedPartnerIds = MutableStateFlow<Set<String>>(emptySet())
    val untrustedPartnerIds = _untrustedPartnerIds.asStateFlow()

    /** Local reactions by chatId → messageId → reactions (client-side until server API). */
    private val _reactionsByChat =
        MutableStateFlow<Map<String, Map<String, List<MessageReaction>>>>(emptyMap())

    private val attachmentPreviewStore = AttachmentPreviewStore()
    val attachmentPreviews: StateFlow<Map<String, AttachmentPreview>> = attachmentPreviewStore.state

    private val _webSocketConnectionState = MutableStateFlow(RealtimeConnectionState.Hidden)

    /**
     * Status-line connection plate: only a short process flash (connect/reconnect),
     * never a permanent status. Offline is silent; reauth stays sticky.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionBannerState: Flow<ConnectionBannerState> = combine(
        _webSocketConnectionState,
        NetworkPathMonitor.state,
        SessionStore.reauthRequired,
    ) { wsState, networkState, reauthRequired ->
        reduceConnectionBannerState(wsState, networkState, reauthRequired)
    }
        .distinctUntilChanged()
        .flatMapLatest { banner ->
            when (val presentation = presentConnectionBannerSequence(banner)) {
                is ConnectionBannerPresentation.Immediate -> flowOf(presentation.state)
                is ConnectionBannerPresentation.Transient -> flow {
                    delay(presentation.showAfterMs)
                    emit(presentation.shown)
                    delay(presentation.hideAfterMs)
                    emit(ConnectionBannerState(ConnectionBannerKind.Hidden))
                }
            }
        }
        .distinctUntilChanged()

    private var webSocketJob: Job? = null
    private var prekeyWatchdogJob: Job? = null
    private val messageQueueMutex = Mutex()
    private val outboxMutex = Mutex()
    /** Serialize refresh so concurrent bootstrap/outbox cannot rotate the same refresh token twice. */
    private val sessionRefreshMutex = Mutex()
    private val sendDedupMutex = Mutex()
    private var lastSendKey: String? = null
    private var lastSendAtMs: Long = 0L
    private var lastSendPendingId: String? = null
    private val knownUsernames = mutableMapOf<String, String>()
    private val knownUsers = mutableMapOf<String, User>()
    private val hydratedPublicProfileIds = mutableSetOf<String>()
    private val _knownUserProfiles = MutableStateFlow<Map<String, User>>(emptyMap())
    private val dmPartnerByChatId = mutableMapOf<String, String>()
    private val chatMemberIds = mutableMapOf<String, List<String>>()
    private val chatMembersRevision = MutableStateFlow(0)

    val knownUserProfiles: StateFlow<Map<String, User>> = _knownUserProfiles.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts = _unreadCounts.asStateFlow()

    private var activeChatId: String? = null
    private var lastQueuePollMs = 0L
    private var lastMediaCacheMaintenanceMs = 0L
    private val readReceiptsSent = mutableSetOf<String>()

    // --- Session ---

    fun observeChats(): Flow<List<Chat>> =
        combine(local.observeChats(), chatMembersRevision) { chats, _ ->
            chats.map { chat ->
                val cached = chatMemberIds[chat.id].orEmpty()
                chat.copy(member_ids = cached.ifEmpty { chat.member_ids })
            }
        }

    fun observeMessages(chatId: String): Flow<List<Message>> = local.observeMessages(chatId)
    fun observeMainMessages(chatId: String): Flow<List<Message>> = local.observeMainMessages(chatId)
    fun observeOutboxErrors(chatId: String): Flow<Map<String, String>> = local.observeOutboxErrors(chatId)
    fun observeThreadMessages(chatId: String, threadRootId: String): Flow<List<Message>> =
        local.observeThreadMessages(chatId, threadRootId)

    suspend fun restoreFastSessionShell(): Boolean {
        val shell = secureSession.loadShell() ?: return false
        val userId = shell.userId.takeIf { it.isNotBlank() } ?: return false
        val credentials = secureSession.loadForUser(userId)
        if (credentials?.isTrustedForCurrentServer() != true) {
            logSync("restoreFastSessionShell: skip unbound session user=$userId")
            return false
        }
        SessionStore.setUserOnly(
            User(
                id = userId,
                username = userId,
            ),
        )
        logSync("restoreFastSessionShell: user=$userId")
        return true
    }

    suspend fun clearFastSessionShell() {
        secureSession.clearShell()
    }

    suspend fun biometricUnlockCandidate(): BiometricUnlockCandidate? {
        val credentials = resolveStoredCredentials() ?: return null
        if (!credentials.isTrustedForCurrentServer()) return null
        val userId = credentials.userId
            ?: secureSession.activeUserId()
            ?: return null
        if (!secureSession.isBiometricUnlockEnabled(userId)) return null
        if (credentials.accessToken.isBlank() && credentials.refreshToken.isNullOrBlank()) return null
        return BiometricUnlockCandidate(
            userId = userId,
            username = credentials.username?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun restoreBiometricUnlockedSession(): Boolean {
        val candidate = biometricUnlockCandidate() ?: return false
        secureSession.setActiveUser(candidate.userId)
        return restoreLocalSessionShell()
    }

    suspend fun isBiometricUnlockEnabledForCurrentUser(): Boolean {
        val userId = SessionStore.user.value?.id ?: return false
        return secureSession.isBiometricUnlockEnabled(userId)
    }

    suspend fun setBiometricUnlockEnabledForCurrentUser(enabled: Boolean): Boolean {
        val userId = SessionStore.user.value?.id ?: return false
        secureSession.setBiometricUnlockEnabled(userId, enabled)
        return true
    }

    /** Local chat lookup (no network). Used by send path branching (channel vs e2e). */
    suspend fun getLocalChat(chatId: String): Chat? = local.findChat(chatId)

    /**
     * Whether the current user may publish root posts to a channel.
     * Uses creator_id fast-path, then GET /api/groups/{id} for admin role.
     */
    suspend fun canPublishChannelPosts(chatId: String): Boolean {
        val me = SessionStore.user.value?.id ?: return false
        val chat = local.findChat(chatId)
        if (chat != null && !chat.isChannel) return false
        // Fast path: creator always publishes.
        if (chat?.creator_id == me) return true
        return runCatching {
            auth.withAuth { token ->
                val group = api.getGroup(token, chatId)
                group.canPublishPosts(me)
            }
        }.getOrElse {
            // Network/API glitch: still allow composer if we created this chat locally
            // (creator_id missing on list shells) — server remains source of truth on send.
            chat?.creator_id.isNullOrBlank()
        }
    }

    /**
     * Pull root + replies for a channel thread into local store (open messages).
     * Does not touch e2e group/DM decryption paths.
     */
    suspend fun refreshChannelThread(chatId: String, threadRootId: String) {
        val chat = local.findChat(chatId) ?: return
        if (!chat.isChannel) return
        auth.withAuth { token ->
            val page = api.listMessages(token, chatId, limit = 100)
            page.messages
                .filter { it.id == threadRootId || it.thread_root_id == threadRootId }
                .forEach { msg ->
                    local.saveMessage(msg.copy(status = MessageStatus.SENT), status = MessageStatus.SENT)
                }
        }
    }
    fun observeThreadBranchSummaries(chatId: String): Flow<Map<String, ThreadBranchSummary>> =
        local.observeMainMessages(chatId).combine(
            local.observeThreadBranchMessages(chatId),
            ::buildThreadBranchSummaries,
        )

    fun hasMoreMessages(chatId: String): Boolean = hasMoreByChat[chatId] != false

    /**
     * Rehydrates typed media previews from persistent metadata/thumbnail cache when in-memory
     * preview state has been dropped.
     */
    suspend fun restoreImagePreviews(chatId: String, messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        persistentAttachmentCache.metadata.repairMessageLinksForChat(
            chatId = chatId,
            accessedAt = nowIso(),
        )
        val cachedIds = attachmentPreviewStore.state.value.keys
        val missingIds = messageIds - cachedIds
        if (missingIds.isEmpty()) return
        val records = persistentAttachmentCache.metadata.listForChat(chatId)
        if (records.isEmpty()) return
        records.forEach { record ->
            val messageId = record.messageId ?: return@forEach
            if (messageId !in missingIds) return@forEach
            val kind = AttachmentKind.fromWireName(record.kind)
                .takeUnless { it == AttachmentKind.UNKNOWN }
                ?: resolveAttachmentKind(record.mimeType, record.fileName)
            val thumbnail = persistentAttachmentCache.readThumbnail(record.cacheId)
            if (kind == AttachmentKind.IMAGE && thumbnail == null) return@forEach
            attachmentPreviewStore.cache(
                messageId = messageId,
                bytes = if (kind == AttachmentKind.IMAGE) thumbnail ?: ByteArray(0) else ByteArray(0),
                fileName = record.fileName,
                mimeType = record.mimeType,
                kind = kind,
                thumbnailBytes = thumbnail,
                width = record.width,
                height = record.height,
                durationMs = record.durationMs,
                waveform = decodeWaveform(record.waveform),
            )
        }
    }

    /**
     * Disk-only cold-start shell (no network). It may open Main only for credentials
     * already bound to this API URL and a previously confirmed server identity.
     * [restoreSessionOnline] / [bootstrapRestoredSession] then refresh in the background.
     */
    suspend fun restoreLocalSessionShell(): Boolean {
        val credentials = resolveStoredCredentials()
        val shell = secureSession.loadShell()
        val accountId = credentials?.userId ?: shell?.userId
        if (credentials?.isTrustedForCurrentServer() != true) {
            logSync("restoreLocalSessionShell: skip unbound session user=${accountId.orEmpty()}")
            return false
        }
        accountId?.let { local.switchAccount(it) }
        val cachedUser = local.loadCachedUser()
            ?: local.loadLegacySession()?.user
            ?: credentials?.cachedUser()

        if (cachedUser != null) {
            publishLocalSessionShell(cachedUser, credentials)
            return true
        }

        // Tokens without a profile cannot open Main offline; avoid blocking splash on network.
        logSync("restoreLocalSessionShell: credentials without cached user")
        return false
    }

    private fun publishLocalSessionShell(user: User, credentials: StoredCredentials?) {
        // Keep this path cheap: no Signal identity, cache hydration, purge, or network.
        if (credentials != null &&
            credentials.hasAccessToken() &&
            !credentials.isExpired()
        ) {
            SessionStore.setSession(
                credentials.accessToken,
                user,
                credentials.refreshToken,
            )
            logSync("restoreLocalSessionShell: local token user=${user.id}")
        } else {
            SessionStore.setUserOnly(user)
            logSync("restoreLocalSessionShell: offline user=${user.id}")
        }
    }

    /**
     * Network half of cold start. Safe after [restoreLocalSessionShell] already painted Main.
     * Never blocks the splash screen.
     */
    suspend fun restoreSessionOnline(): Boolean {
        val credentials = resolveStoredCredentials() ?: return SessionStore.user.value != null
        if (!credentials.isTrustedForCurrentServer()) {
            logSync("restoreSession: unbound stored session — reauth required")
            markSessionReauthRequired()
            return false
        }
        if (!verifyStoredServerBinding(credentials)) {
            logSync("restoreSession: server identity changed — local session cleared")
            return false
        }
        val cachedUser = SessionStore.user.value
            ?: local.loadCachedUser()
            ?: local.loadLegacySession()?.user
            ?: credentials.cachedUser()
        return when (recoverStoredSession(cachedUser, credentials)) {
            SessionRecoveryResult.Recovered -> true
            SessionRecoveryResult.OfflinePending -> cachedUser != null || SessionStore.user.value != null
            SessionRecoveryResult.ReauthRequired -> cachedUser != null || SessionStore.user.value != null
        }
    }

    /**
     * Full restore (local shell + online). Prefer the split APIs at cold start so the
     * splash does not wait on refresh/timeouts.
     */
    suspend fun restoreSession(): Boolean {
        if (!restoreLocalSessionShell()) return false
        val credentials = resolveStoredCredentials()
        if (credentials == null) {
            return SessionStore.user.value != null
        }
        return restoreSessionOnline()
    }

    /**
     * Проактивный refresh и восстановление токена (фон, как в Telegram).
     * Temporary access loss or near-expiry refresh must not clear user identity —
     * only [tryRecoverSession] / reauth paths may mark reauthRequired.
     */
    suspend fun maintainSession() {
        if (SessionStore.reauthRequired.value) return
        SessionStore.user.value ?: local.loadCachedUser() ?: return
        val credentials = resolveStoredCredentials()
        val needsProactiveRefresh =
            credentials?.requiresRefresh(SESSION_PROACTIVE_REFRESH_SKEW_MS) == true
        // Healthy online session: nothing to do.
        if (SessionStore.token.value != null && !needsProactiveRefresh) return
        // Near expiry with token still in memory still needs forceRefresh (Telegram-style).
        tryRecoverSession(
            forceRefresh = SessionStore.token.value == null || needsProactiveRefresh,
        )
    }

    private suspend fun bindOfflineUser(user: User) {
        local.switchAccount(user.id)
        SessionStore.setUserOnly(user)
        crypto.ensureDeviceIdentity(user.id)
        loadCachedKnownUsers()
        loadCachedChatMembers()
        loadUntrustedPartners()
        loadUnreadCounts()
        purgeExpiredMessages()
    }

    private enum class SessionRecoveryResult {
        Recovered,
        OfflinePending,
        ReauthRequired,
    }

    private suspend fun recoverStoredSession(
        cachedUser: User?,
        credentials: StoredCredentials,
    ): SessionRecoveryResult {
        val user = cachedUser ?: credentials.cachedUser() ?: runCatching {
            if (credentials.requiresRefresh()) return@runCatching null
            if (!credentials.hasAccessToken()) return@runCatching null
            withTimeout(RESTORE_SESSION_REFRESH_TIMEOUT_MS) {
                api.getMyProfile(credentials.accessToken)
            }.also { local.saveCachedUser(it) }
        }.getOrNull()

        if (user == null) {
            markSessionReauthRequired()
            return SessionRecoveryResult.ReauthRequired
        }

        bindOfflineUser(user)

        return try {
            if (tryRecoverSession(forceRefresh = credentials.requiresRefresh() || credentials.isExpired())) {
                logSync("restoreSession: online user=${user.id}")
                SessionRecoveryResult.Recovered
            } else {
                logSync("restoreSession: token pending user=${user.id}")
                SessionRecoveryResult.OfflinePending
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isAuthRefreshFailure()) {
                deferSessionRecovery(
                    "restoreSession: refresh unauthorized",
                    requireReauthWhenAccessDead = true,
                )
                SessionRecoveryResult.OfflinePending
            } else {
                logSync("restoreSession: api error status=${e.status} — stay offline")
                SessionRecoveryResult.OfflinePending
            }
        } catch (e: Exception) {
            logSync("restoreSession: transient error ${e::class.simpleName}: ${e.message}")
            SessionRecoveryResult.OfflinePending
        }
    }

    /**
     * Never throws for network/timeouts — cold start / reinstall must not crash the UI
     * when /api/auth/refresh is slow or offline (seen on Xiaomi after adb install -r).
     * Only cancellation is rethrown.
     */
    suspend fun tryRecoverSession(forceRefresh: Boolean = false): Boolean {
        if (SessionStore.reauthRequired.value) return false
        val credentials = resolveStoredCredentials() ?: return false
        if (!credentials.isTrustedForCurrentServer()) {
            logSync("session: stored credentials are not bound to current server")
            return false
        }
        if (!verifyStoredServerBinding(credentials)) return false
        credentials.userId?.let { local.switchAccount(it) }
        val user = SessionStore.user.value
            ?: local.loadCachedUser()
            ?: credentials.cachedUser()
            ?: return false
        if (!forceRefresh && SessionStore.token.value != null) return true

        if (!forceRefresh && credentials.hasAccessToken() && !credentials.isExpired()) {
            SessionStore.setSession(credentials.accessToken, user, credentials.refreshToken)
            logSync("session: restored stored access token user=${user.id}")
            return true
        }

        if (!credentials.refreshToken.isNullOrBlank()) {
            return try {
                refreshAccessToken(user, force = forceRefresh)
                logSync("session: refreshed user=${user.id}")
                true
            } catch (e: SessionExpiredException) {
                deferSessionRecovery(
                    "session: refresh unavailable user=${user.id}",
                    requireReauthWhenAccessDead = true,
                )
                false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isAuthRefreshFailure()) {
                    deferSessionRecovery(
                        "session: refresh rejected user=${user.id}: ${e.message}",
                        requireReauthWhenAccessDead = true,
                    )
                    false
                } else {
                    logSync("session: refresh api error user=${user.id}: ${e.message}")
                    false
                }
            } catch (e: Exception) {
                // HttpRequestTimeoutException and other transport errors must not kill MainActivity.
                logSync("session: refresh transient error user=${user.id}: ${e::class.simpleName}: ${e.message}")
                // After adb install -r session data stays; use stored access if present so UI can open.
                fallBackToStoredAccessToken(user, credentials)
            }
        }

        if (!credentials.hasAccessToken()) {
            return false
        }

        return try {
            withTimeout(RESTORE_SESSION_REFRESH_TIMEOUT_MS) {
                api.getMyProfile(credentials.accessToken)
            }
            SessionStore.setSession(credentials.accessToken, user, credentials.refreshToken)
            logSync("session: access token still valid user=${user.id}")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isAuthRefreshFailure()) {
                deferSessionRecovery(
                    "session: profile check unauthorized user=${user.id}",
                    requireReauthWhenAccessDead = true,
                )
                false
            } else {
                logSync("session: profile check api error user=${user.id}: ${e.message}")
                fallBackToStoredAccessToken(user, credentials)
            }
        } catch (e: Exception) {
            logSync("session: profile check transient error user=${user.id}: ${e::class.simpleName}: ${e.message}")
            fallBackToStoredAccessToken(user, credentials)
        }
    }

    /** Prefer a maybe-stale access token over crashing or empty session on network blips. */
    private fun fallBackToStoredAccessToken(user: User, credentials: StoredCredentials): Boolean {
        if (SessionStore.token.value != null) {
            logSync("session: keep in-memory access token after recover failure user=${user.id}")
            return true
        }
        if (credentials.hasAccessToken() && !credentials.isExpired()) {
            SessionStore.setSession(credentials.accessToken, user, credentials.refreshToken)
            logSync("session: fall back to stored access token user=${user.id}")
            return true
        }
        return false
    }

    private suspend fun deferSessionRecovery(
        reason: String,
        requireReauthWhenAccessDead: Boolean = false,
    ) {
        val credentials = resolveStoredCredentials()
        val user = SessionStore.user.value
            ?: local.loadCachedUser()
            ?: credentials?.cachedUser()
        if (user != null && credentials?.hasUsableAccessToken() == true) {
            SessionStore.setSession(credentials.accessToken, user, credentials.refreshToken)
            logSync("$reason — keeping usable access token")
            return
        }
        if (user != null && requireReauthWhenAccessDead) {
            logSync("$reason — reauth required")
            markSessionReauthRequired()
            return
        }
        logSync("$reason — keeping local shell")
    }

    private suspend fun markSessionReauthRequired() {
        invalidateStoredCredentialsForReauth()
        SessionStore.markReauthRequired()
        stopWebSocket()
        stopPrekeyWatchdog()
        _webSocketConnectionState.value = RealtimeConnectionState.Failed
        logSync("session: reauth required")
    }

    private suspend fun invalidateStoredCredentialsForReauth() {
        val stored = secureSession.load() ?: return
        secureSession.save(
            stored.copy(
                accessToken = "",
                refreshToken = null,
                expiresAtEpochMs = 0L,
            ),
        )
        SessionStore.clearRefreshToken()
    }

    suspend fun bootstrapRestoredSession() {
        loadEditedMessageIds()
        val user = SessionStore.user.value ?: return
        // After fresh login the access token is already valid — do NOT always forceRefresh.
        // Unconditional refresh right after login races / invalidates the just-issued refresh
        // token (server rotates) and marks reauth → outbox "no active session" while UI still
        // sends (tests always keep SessionStore.token, so they never hit this).
        val credentials = resolveStoredCredentials()
        val forceRefresh = SessionStore.token.value == null ||
            credentials?.requiresRefresh() == true ||
            credentials?.isExpired() == true
        val recovered = runCatching { tryRecoverSession(forceRefresh = forceRefresh) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                logSync("bootstrap: recover threw user=${user.id}: ${it.message}")
            }
            .getOrDefault(false)
        if (!recovered) {
            logSync("bootstrap: skip — no valid session for user=${user.id}")
            return
        }
        runCatching {
            auth.withAuth { token ->
                ensureDeviceRegistered(token, failIfUnavailable = false)
                registerPushTokenIfNeeded(token)
                rotateMailboxIfNeeded(token)
                runCatching { syncChats() }
                runCatching { runPrekeyWatchdog() }
                runCatching { logStaleRelayQueuesIfAny(token, user.id) }
                runCatching { drainPendingSync() }
                runCatching { syncLinkedDevices() }
                // Recover failed/stuck sends so temporary outages or zombie-device dirt
                // do not leave the user's messages permanently unsent.
                runCatching { retryUndeliveredMessages() }
                    .onFailure { logSync("bootstrap outbox recovery failed: ${it.message}") }
                runCatching { maintainMediaCache(force = true) }
                    .onFailure { logSync("media cache maintenance failed: ${it.message}") }
            }
        }.onFailure { err ->
            if (err is kotlinx.coroutines.CancellationException) throw err
            if (err.isAuthRefreshFailure()) {
                markSessionReauthRequired()
            } else {
                logSync("bootstrap failed user=${user.id}: ${err.message}")
            }
        }
    }

    fun isPartnerUntrusted(partnerId: String): Boolean =
        partnerId in _untrustedPartnerIds.value

    suspend fun acknowledgeIdentityChange(partnerUserId: String) {
        val user = SessionStore.user.value ?: return
        auth.withAuth { token ->
            val devices = api.listUserDevices(token, partnerUserId)
            for (device in devices) {
                val deviceId = device.device_id
                val pending = local.loadSetting(identityPendingKey(partnerUserId, deviceId))
                if (pending.isNotBlank()) {
                    local.saveSetting(identityTrustKey(partnerUserId, deviceId), pending)
                    local.saveSetting(identityPendingKey(partnerUserId, deviceId), "")
                }
                crypto.trustRemoteIdentity(user.id, partnerUserId, deviceId)
            }
            val legacyPending = local.loadSetting(identityPendingKey(partnerUserId))
            if (legacyPending.isNotBlank()) {
                local.saveSetting(identityTrustKey(partnerUserId), legacyPending)
                local.saveSetting(identityPendingKey(partnerUserId), "")
            }
            removeUntrustedPartner(partnerUserId)
            val chatId = dmPartnerByChatId.entries.firstOrNull { it.value == partnerUserId }?.key
            processMessageQueue(chatId)
        }
    }

    suspend fun purgeExpiredMessages() {
        local.purgeExpiredMessages(nowIso())
    }

    suspend fun runPrekeyWatchdog() {
        val user = SessionStore.user.value ?: return
        try {
            auth.withAuth { token ->
                val identity = crypto.ensureDeviceIdentity(user.id)
                replenishPrekeysIfNeeded(token, identity.deviceId, user.id)
                rotateSignedPreKeyIfNeeded(token, identity.deviceId, user.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            handleAuthFailure(e)
        }
    }

    private suspend fun rotateSignedPreKeyIfNeeded(token: String, deviceId: String, accountId: String) {
        val lastRotation = local.loadSetting(KEY_LAST_SIGNED_PREKEY_ROTATION).toLongOrNull() ?: 0L
        if (currentTimeMillis() - lastRotation < SIGNED_PREKEY_ROTATION_INTERVAL_MS) return
        val rotation = crypto.buildSignedPreKeyRotation(accountId) ?: return
        api.rotateSignedPreKey(token, deviceId, rotation)
        local.saveSetting(KEY_LAST_SIGNED_PREKEY_ROTATION, currentTimeMillis().toString())
    }

    fun startPrekeyWatchdog(scope: CoroutineScope) {
        prekeyWatchdogJob?.cancel()
        prekeyWatchdogJob = scope.launch {
            while (isActive) {
                runPrekeyWatchdog()
                delay(PREKEY_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    fun stopPrekeyWatchdog() {
        prekeyWatchdogJob?.cancel()
        prekeyWatchdogJob = null
    }

    suspend fun exportEncryptedBackup(passphrase: CharArray, uploadToCloud: Boolean = true): EncryptedBackupResult {
        val result = EncryptedBackupExporter.export(passphrase)
        local.saveSetting(KEY_LAST_BACKUP_AT, result.createdAt)
        local.saveSetting(KEY_LAST_BACKUP_SIZE, result.bytes.size.toString())
        if (uploadToCloud) {
            runCatching { uploadEncryptedBackupToCloud(result) }
        }
        return result
    }

    suspend fun register(
        form: RegistrationValidation.Form,
        onProgress: ((RegisterProgress) -> Unit)? = null,
    ) {
        val fieldErrors = RegistrationValidation.validate(form)
        if (fieldErrors.isNotEmpty()) {
            throw RegistrationValidationException(fieldErrors)
        }
        val username = RegistrationValidation.normalizeUsername(form.username)
        val sanitizedPassword = RegistrationValidation.sanitizeCredentialInput(form.password)
        val email = form.email.trim().lowercase().takeIf { it.isNotBlank() }
        AppLog.debug("registration: start username_len=${username.length} email_present=${email != null}")
        try {
            onProgress?.invoke(RegisterProgress.PROOF_OF_WORK)
            AppLog.debug("registration: fetching proof-of-work challenge")
            val pow = api.fetchPowChallenge()
            AppLog.debug("registration: proof-of-work challenge difficulty=${pow.difficulty}")
            val powSolution = withContext(Dispatchers.Default) {
                RegistrationPow.solve(pow.challenge, pow.difficulty)
            } ?: error("Не удалось решить proof-of-work")
            AppLog.debug("registration: proof-of-work solved")
            onProgress?.invoke(RegisterProgress.CREATING_ACCOUNT)
            AppLog.debug("registration: posting account create request")
            val response = api.register(
                RegisterRequest(
                    username = username,
                    email = email,
                    password = sanitizedPassword,
                    pow_challenge_id = pow.challenge_id,
                    pow_solution = powSolution,
                ),
            )
            AppLog.debug("registration: account created user=${response.user.id} expires_in=${response.expires_in}")
            persistSession(
                token = response.token,
                user = response.user,
                refreshToken = response.refresh_token,
                expiresInSeconds = response.expires_in,
                enrollDeviceBeforePublish = true,
            )
            AppLog.debug("registration: session persisted user=${response.user.id}")
            runCatching { syncChats() }
                .onSuccess { AppLog.debug("registration: initial sync completed") }
                .onFailure { AppLog.warning("registration: initial sync failed: ${it.message}") }
            // 201 = аккаунт создан; не бросаем из-за sync/device — иначе повтор даст 202.
            AppLog.debug("registration: completed")
        } catch (t: Throwable) {
            AppLog.warning("registration: failed: ${t.message}")
            throw t
        }
    }

    /**
     * Signal-style login: password auth → device enroll → only then publish session.
     * Publishing SessionStore mid-enroll races UI bootstrap/refresh and can mark reauth
     * against a half-open session (seen on device: login then immediate "reauth required").
     */
    suspend fun login(username: String, password: String) {
        val normalizedUsername = RegistrationValidation.normalizeUsername(username)
        val sanitizedPassword = RegistrationValidation.sanitizeCredentialInput(password)
        val loginDeviceId = knownAccountIdForLogin(normalizedUsername)
            ?.let { accountId -> crypto.ensureDeviceIdentity(accountId).deviceId }
        val response = api.login(
            LoginRequest(
                username = normalizedUsername,
                password = sanitizedPassword,
                device_id = loginDeviceId,
            ),
        )
        SessionStore.clearReauthRequired()
        persistSession(
            token = response.token,
            user = response.user,
            refreshToken = response.refresh_token,
            expiresInSeconds = response.expires_in,
            enrollDeviceBeforePublish = true,
        )
        // Belt: any concurrent recover must not leave reauth stuck after successful login.
        SessionStore.clearReauthRequired()
        runCatching { syncChats() }
        runCatching { drainPendingSync() }
        // Pull partner envelopes onto this device (Signal: fetch mailbox after register).
        runCatching { processMessageQueue(force = true) }
        runCatching {
            auth.withAuth { token ->
                val user = SessionStore.user.value ?: return@withAuth
                logStaleRelayQueuesIfAny(token, user.id)
            }
        }
        // Signal-style: login password NEVER unlocks history (no offline crack loophole).
        // If Secure Backup exists in cloud and local history is empty, UI must ask recovery key.
        runCatching { refreshSecureHistoryRestoreHint() }
            .onFailure { logSync("secure history hint: ${it.message}") }
        // If this install already has recovery key (re-login same phone), push/pull stays automatic.
        runCatching { uploadSecureHistoryIfEnabled() }
            .onFailure { logSync("secure history upload after login: ${it.message}") }
    }

    suspend fun setupAccountRecoveryKey(): String {
        val key = AccountRecoveryKey.generate()
        auth.withAuth { token ->
            api.setupRecovery(
                token,
                RecoverySetupRequest(recovery_key = AccountRecoveryKey.normalize(key)),
            )
        }
        local.saveSetting(KEY_ACCOUNT_RECOVERY_KEY_CREATED, "1")
        return key
    }

    suspend fun accountRecoveryStatus(): RecoveryStatusResponse =
        auth.withAuth { token -> api.recoveryStatus(token) }

    suspend fun localAccountRecoveryCreated(): Boolean =
        local.loadSetting(KEY_ACCOUNT_RECOVERY_KEY_CREATED).isNotBlank()

    suspend fun verifyAccountRecovery(username: String, recoveryKey: String): RecoveryTicketResponse {
        val normalizedUsername = RegistrationValidation.normalizeUsername(username)
        AccountRecoveryKey.validate(recoveryKey)?.let { error(it) }
        return api.verifyRecovery(
            RecoveryVerifyRequest(
                username = normalizedUsername,
                recovery_key = AccountRecoveryKey.normalize(recoveryKey),
            ),
        )
    }

    suspend fun completeAccountRecovery(recoveryToken: String, newPassword: String): RecoveryCompleteResponse {
        RegistrationValidation.validatePassword(newPassword)?.let { error(it) }
        return api.completeRecovery(
            RecoveryCompleteRequest(
                recovery_token = recoveryToken.trim(),
                new_password = RegistrationValidation.sanitizeCredentialInput(newPassword),
            ),
        )
    }

    suspend fun startTrustedAccountRecovery(username: String): TrustedRecoveryStartResponse =
        api.startTrustedRecovery(
            TrustedRecoveryStartRequest(
                username = RegistrationValidation.normalizeUsername(username),
            ),
        )

    suspend fun pollTrustedAccountRecovery(challengeId: String): RecoveryTicketResponse =
        api.pollTrustedRecovery(TrustedRecoveryPollRequest(challenge_id = challengeId.trim()))

    suspend fun pendingTrustedAccountRecoveries(): List<TrustedRecoveryPendingItem> =
        auth.withAuth { token -> api.pendingTrustedRecovery(token).challenges }

    suspend fun approveTrustedAccountRecovery(challengeId: String) {
        auth.withAuth { token ->
            api.approveTrustedRecovery(
                token,
                TrustedRecoveryApproveRequest(challenge_id = challengeId.trim()),
            )
        }
    }

    suspend fun registerPasskey(authenticator: LocalAuthenticator) {
        confirmPasskeyUser(authenticator, "Подтвердите создание ключа телефона")
        auth.withAuth { token ->
            val options = api.webAuthnRegisterBegin(token)
            val credential = passkeys.create(options)
            api.webAuthnRegisterFinish(
                token,
                WebAuthnFinishRequest(
                    session_id = options.session_id,
                    credential = credential,
                ),
            )
        }
    }

    suspend fun loginWithPasskey(username: String, authenticator: LocalAuthenticator) {
        val normalized = RegistrationValidation.normalizeUsername(username)
        require(normalized.isNotBlank()) { "Введите логин" }
        confirmPasskeyUser(authenticator, "Подтвердите вход ключом телефона")
        val options = api.webAuthnLoginBegin(WebAuthnBeginRequest(username = normalized))
        val credential = passkeys.get(options)
        val response = api.webAuthnLoginFinish(
            WebAuthnFinishRequest(
                session_id = options.session_id,
                username = normalized,
                credential = credential,
            ),
        )
        SessionStore.clearReauthRequired()
        persistSession(
            token = response.token,
            user = response.user,
            refreshToken = response.refresh_token,
            expiresInSeconds = response.expires_in,
            enrollDeviceBeforePublish = true,
        )
        SessionStore.clearReauthRequired()
        runCatching { syncChats() }
        runCatching { drainPendingSync() }
        runCatching { processMessageQueue(force = true) }
        runCatching { refreshSecureHistoryRestoreHint() }
        runCatching { uploadSecureHistoryIfEnabled() }
    }

    suspend fun recoverWithPasskey(
        username: String,
        authenticator: LocalAuthenticator,
    ): RecoveryTicketResponse {
        val normalized = RegistrationValidation.normalizeUsername(username)
        require(normalized.isNotBlank()) { "Введите логин" }
        confirmPasskeyUser(authenticator, "Подтвердите восстановление ключом телефона")
        val options = api.webAuthnRecoveryBegin(WebAuthnBeginRequest(username = normalized))
        val credential = passkeys.get(options)
        return api.webAuthnRecoveryFinish(
            WebAuthnFinishRequest(
                session_id = options.session_id,
                username = normalized,
                credential = credential,
            ),
        )
    }

    private suspend fun confirmPasskeyUser(authenticator: LocalAuthenticator, reason: String) {
        when (authenticator.availability()) {
            LocalAuthAvailability.Available -> when (val result = authenticator.authenticate(reason)) {
                LocalAuthResult.Success -> Unit
                LocalAuthResult.Cancelled -> error("Подтверждение отменено")
                is LocalAuthResult.Failed -> error(result.message ?: "Не удалось подтвердить ключ телефона")
            }
            else -> Unit
        }
    }

    private suspend fun knownAccountIdForLogin(normalizedUsername: String): String? {
        fun matches(username: String?): Boolean =
            runCatching { RegistrationValidation.normalizeUsername(username.orEmpty()) == normalizedUsername }
                .getOrDefault(false)

        SessionStore.user.value
            ?.takeIf { matches(it.username) }
            ?.id
            ?.let { return it }

        return secureSession.loadAll()
            .firstOrNull { credentials -> credentials.userId != null && matches(credentials.username) }
            ?.userId
    }

    /**
     * End auth session for the current user.
     *
     * **Never erases chat history.** History is only removed via
     * [eraseLocalHistory] with explicit user confirmation.
     *
     * @param wipeCrypto when true, also delete Signal identity/sessions and stored credentials
     * (hard logout / unlink this install). Message history stays on disk unless the user
     * separately confirms erase.
     */
    suspend fun logout(wipeCrypto: Boolean = false) {
        runCatching { uploadSecureHistoryIfEnabled() }
        stopWebSocket()
        clearLocalAccountState(
            accountIds = listOfNotNull(SessionStore.user.value?.id),
            wipeCrypto = wipeCrypto,
        )
        _secureHistoryRestoreAvailable.value = false
    }

    /**
     * Explicit local history erase (chats, messages, outbox, settings for active account DB).
     * **Requires** [userConfirmed] = true. No automatic caller may pass true.
     *
     * Does not log out and does not wipe Signal identity by itself — pair with
     * [logout] if the user asked for a full factory reset of this install.
     */
    suspend fun eraseLocalHistory(userConfirmed: Boolean) {
        require(userConfirmed) {
            "eraseLocalHistory requires userConfirmed=true — history must not be wiped without permission"
        }
        logSync("local history erase: user confirmed")
        persistentAttachmentCache.clearAll()
        local.clear(userConfirmedErase = true)
        clearMemoryCaches()
        loadUnreadCounts()
    }

    suspend fun savedAccounts(): List<User> =
        secureSession.loadAll()
            .mapNotNull { it.cachedUser() }
            .distinctBy { it.id }
            .sortedBy { it.username.lowercase() }

    suspend fun switchToSavedAccount(userId: String): Boolean {
        val credentials = secureSession.loadForUser(userId) ?: return false
        clearTransientLocalState()
        secureSession.setActiveUser(userId)
        local.switchAccount(userId)
        return when (recoverStoredSession(credentials.cachedUser(), credentials)) {
            SessionRecoveryResult.Recovered -> true
            SessionRecoveryResult.OfflinePending -> credentials.cachedUser() != null
            SessionRecoveryResult.ReauthRequired -> credentials.cachedUser() != null
        }
    }

    /** Кэш username по id — для подписей в групповых чатах. */
    fun rememberUsers(users: List<User>) {
        var changed = false
        users.forEach { user ->
            if (knownUsers[user.id] != user) {
                knownUsers[user.id] = user
                changed = true
            }
            knownUsernames[user.id] = user.displayLabel()
        }
        if (changed) {
            _knownUserProfiles.value = knownUsers.toMap()
        }
    }

    private suspend fun rememberUsersPersisted(users: List<User>) {
        rememberUsers(users)
        persistKnownUsers()
    }

    fun usernameForSender(senderId: String): String? = knownUsernames[senderId]

    fun avatarUrlForUser(userId: String): String? = knownUsers[userId]?.avatar_url

    /**
     * Hydrate knownUsers with public profile cards for chat-list / chat-info.
     * Old caches may have avatar_url but no about/bio; avatar alone is not enough.
     */
    suspend fun ensureUserProfiles(userIds: List<String>) {
        val missing = userIds
            .map { it.trim() }
            .filter { id ->
                id.isNotEmpty() && !hasPublicProfileCard(id)
            }
            .distinct()
        if (missing.isEmpty()) return
        refreshUserProfiles(missing)
    }

    /** Force-refresh profile cards when UI needs current status/bio, not just an avatar cache. */
    suspend fun refreshUserProfiles(userIds: List<String>) {
        val ids = userIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (ids.isEmpty()) return
        auth.withAuth { token ->
            val users = api.getUsersByIds(token, ids)
            if (users.isNotEmpty()) {
                hydratedPublicProfileIds += users.map { it.id }
                rememberUsersPersisted(users)
            }
        }
    }

    private fun hasPublicProfileCard(userId: String): Boolean {
        val user = knownUsers[userId] ?: return false
        return userId in hydratedPublicProfileIds || !user.status.isNullOrBlank() || !user.bio.isNullOrBlank()
    }

    // --- Profile ---

    /** Подтягивает профиль с сервера. При ошибке вызывающий код может игнорировать — форма останется из кэша. */
    suspend fun syncProfile(): User =
        auth.withAuth { token -> persistCurrentUser(api.getMyProfile(token), token) }

    suspend fun loadNavBarOrder(): List<MainTab> = local.loadNavBarOrder()

    suspend fun saveNavBarOrder(order: List<MainTab>) = local.saveNavBarOrder(order)

    /**
     * Сохраняет профиль на сервере.
     * Локально применяем только ответ сервера: bio/about должен быть виден другим пользователям.
     */
    suspend fun updateProfile(input: ProfileUpdateInput): User {
        SessionStore.user.value ?: error("Not authenticated")
        val normalized = input.normalized()
        val request = UpdateProfileRequest(
            display_name = normalized.displayName,
            status = normalized.status,
            bio = normalized.bio,
            avatar_url = normalized.avatarUrl,
            presence = normalized.presence,
            position = normalized.position,
        )
        return auth.withAuth { token ->
            val user = api.updateMyProfile(token, request)
            persistCurrentUser(user, token)
        }
    }

    suspend fun loadProfileSetting(key: String, defaultValue: String = ""): String =
        local.loadSetting(key, defaultValue)

    suspend fun loadProfileSettingsByPrefix(prefix: String): Map<String, String> =
        local.loadSettingsByPrefix(prefix)

    suspend fun saveProfileSetting(key: String, value: String) =
        local.saveSetting(key, value)

    suspend fun mediaCacheStats(chatId: String? = null): AttachmentCacheStats =
        persistentAttachmentCache.stats(chatId)

    suspend fun clearMediaCache() =
        persistentAttachmentCache.clearAll()

    suspend fun clearMediaCacheForChat(chatId: String) =
        persistentAttachmentCache.clearChat(chatId)

    suspend fun trimMediaCache(policy: AttachmentRetentionPolicy = AttachmentRetentionPolicy()): Int =
        persistentAttachmentCache.trim(
            policy.copy(excludedChatIds = policy.excludedChatIds + loadForeverMediaCacheChatIds()),
        )

    suspend fun trimMediaCacheForChat(chatId: String, policy: AttachmentRetentionPolicy): Int =
        persistentAttachmentCache.trimChat(chatId, policy)

    private suspend fun maintainMediaCache(force: Boolean = false): Int {
        val now = currentTimeMillis()
        if (!force && now - lastMediaCacheMaintenanceMs < MEDIA_CACHE_MAINTENANCE_INTERVAL_MS) return 0
        lastMediaCacheMaintenanceMs = now
        return trimMediaCache()
    }

    /** Account-level notification preferences (messages / calls / push text visibility). */
    suspend fun getNotificationPreferences(): NotificationPreferences =
        auth.withAuth { token ->
            api.getNotificationPreferences(token).also { cacheNotificationPreferences(it) }
        }

    suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequest,
    ): NotificationPreferences =
        auth.withAuth { token ->
            api.updateNotificationPreferences(token, request).also { cacheNotificationPreferences(it) }
        }

    internal suspend fun cachedNotificationPreferences(): NotificationPreferences? {
        val raw = local.loadSetting(KEY_NOTIFICATION_PREFS, "")
        if (raw.isBlank()) return null
        return runCatching { profileJson.decodeFromString(NotificationPreferences.serializer(), raw) }
            .getOrNull()
    }

    private suspend fun cacheNotificationPreferences(prefs: NotificationPreferences) {
        local.saveSetting(
            KEY_NOTIFICATION_PREFS,
            profileJson.encodeToString(NotificationPreferences.serializer(), prefs),
        )
    }

    suspend fun listOwnDevices(): OwnDeviceList {
        val current = SessionStore.user.value ?: error("Not authenticated")
        return auth.withAuth { token ->
            val identity = crypto.ensureDeviceIdentity(current.id)
            val devices = api.listUserDevices(token, current.id)
                .sortedWith(
                    compareBy<UserDevice> { it.device_id != identity.deviceId }
                        .thenBy { deviceStatusSortOrder(it.device_status) }
                        .thenBy { it.device_id },
                )
            OwnDeviceList(
                currentDeviceId = identity.deviceId,
                devices = devices,
            )
        }
    }

    /**
     * Public client update policy (no auth). Source of truth for soft/hard update + install URLs.
     */
    suspend fun fetchClientUpdatePolicy(): ClientUpdatePolicy =
        api.getClientUpdatePolicy()

    // --- Chats ---

    suspend fun syncChats() {
        auth.withAuth { token ->
            syncChatsWithToken(token)
        }
    }

    private suspend fun syncChatsWithToken(token: String) {
        val chats = api.listChats(token)
        chatMemberIds.keys.retainAll(chats.map { it.id }.toSet())
        cacheDmPartnerNames(chats)
        cacheChatMembers(chats)
        local.replaceChats(chats)
    }

    suspend fun markChatOpened(chatId: String) {
        activeChatId = chatId
        if (_unreadCounts.value.containsKey(chatId)) {
            _unreadCounts.value = _unreadCounts.value - chatId
            persistUnreadCounts()
        }
    }

    suspend fun markVisibleMessagesRead(chatId: String, messages: List<Message>) {
        val user = SessionStore.user.value ?: return
        val chat = local.findChat(chatId) ?: return
        if (!chat.isDirectMessage) return
        if (!AppLifecycle.isInForeground() || activeChatId != chatId) return
        val incomingIds = messages
            .asSequence()
            .filter { it.chat_id == chatId && it.sender_id != user.id }
            .map { it.pending_id ?: it.id }
            .filter { it.isNotBlank() && readReceiptsSent.add(it) }
            .toList()
        if (!ReadReceiptPolicy.shouldSendReadReceipts(
                isForeground = true,
                activeChatId = activeChatId,
                chatId = chatId,
                incomingIds = incomingIds,
            )
        ) {
            return
        }
        sendDmReadReceipt(chat, incomingIds)
    }

    fun clearActiveChat() {
        activeChatId = null
    }

    /** Private multi-party group (legacy /api/chats; e2e group path). */
    suspend fun createGroup(
        title: String,
        memberIds: List<String> = emptyList(),
        avatarUrl: String? = null,
    ): Chat =
        auth.withAuth { token ->
            val creatorId = SessionStore.user.value?.id
            val chat = api.createChat(
                token,
                CreateChatRequest(
                    title = title.trim(),
                    member_ids = memberIds,
                    avatar_url = avatarUrl?.takeIf { it.isNotBlank() },
                ),
            ).copy(creator_id = creatorId)
            local.saveChat(chat, revealHidden = true)
            syncChats()
            local.findChat(chat.id) ?: chat
        }

    suspend fun createChannel(
        title: String,
        description: String = "",
        visibility: String = ChatVisibility.PRIVATE,
        slug: String? = null,
        avatarUrl: String? = null,
    ): Chat =
        auth.withAuth { token ->
            val creatorId = SessionStore.user.value?.id
            val response = api.createChannel(
                token,
                CreateChannelRequest(
                    title = title.trim(),
                    description = description.trim().ifBlank { null },
                    visibility = visibility,
                    slug = slug?.trim()?.ifBlank { null },
                    avatar_url = avatarUrl?.takeIf { it.isNotBlank() },
                ),
            )
            // Stamp creator so only this user sees creator-owned invite controls.
            val chat = response.toChat(creatorId = creatorId)
            local.saveChat(chat, revealHidden = true)
            syncChats()
            local.findChat(chat.id) ?: chat
        }

    suspend fun checkChannelSlugAvailable(slug: String): Boolean =
        auth.withAuth { token ->
            api.checkChannelSlugAvailable(token, slug).available
        }

    suspend fun joinChannelBySlug(slug: String): Chat =
        auth.withAuth { token ->
            val chat = api.joinChannelBySlug(token, slug).toChat()
            local.saveChat(chat, revealHidden = true)
            syncChats()
            local.findChat(chat.id) ?: chat
        }

    /**
     * Open a pasted / in-message invite. Private chats/channels join by link only.
     */
    suspend fun openInviteLink(raw: String): Chat {
        return when (val target = GlagolitsaInviteLink.parse(raw)) {
            is GlagolitsaInviteTarget.ChannelSlug -> joinChannelBySlug(target.slug)
            is GlagolitsaInviteTarget.JoinToken -> {
                val change = joinChatInvite(raw)
                if (change.pending_approval) {
                    error("Заявка отправлена. Владелец должен подтвердить вход.")
                }
                syncChats()
                getLocalChat(change.group_id)
                    ?: error("Не удалось открыть чат после вступления")
            }
            is GlagolitsaInviteTarget.CallId -> {
                val session = calls.joinGroupCall(target.callId)
                val chatId = session.chat_id?.takeIf { it.isNotBlank() }
                    ?: error("В этой ссылке нет группового чата")
                syncChats()
                getLocalChat(chatId)
                    ?: error("Не удалось открыть чат звонка")
            }
            null -> error("Неверная ссылка приглашения")
        }
    }

    suspend fun loadGroup(groupId: String): GroupResponse =
        auth.withAuth { token -> api.getGroup(token, groupId) }

    suspend fun updateGroupPermissions(groupId: String, request: UpdateGroupSettingsRequest): GroupResponse =
        auth.withAuth { token -> api.updateGroupSettings(token, groupId, request) }

    suspend fun updateConversationIcon(chatId: String, avatarUrl: String): Chat =
        auth.withAuth { token ->
            val chat = api.updateChat(token, chatId, UpdateChatRequest(avatar_url = avatarUrl))
            local.saveChat(chat, revealHidden = true)
            chat
        }

    suspend fun createChatInvite(
        groupId: String,
        title: String = "",
        expiresInHours: Int = 168,
        requiresApproval: Boolean = false,
    ): GroupInviteDto =
        auth.withAuth { token ->
            api.createGroupInvite(
                token,
                groupId,
                CreateGroupInviteRequest(
                    title = title.trim().ifBlank { null },
                    expires_in_hours = expiresInHours,
                    requires_approval = requiresApproval,
                ),
            )
        }

    suspend fun listChatInvites(groupId: String): List<GroupInviteDto> =
        auth.withAuth { token -> api.listGroupInvites(token, groupId) }

    suspend fun revokeChatInvite(groupId: String, inviteId: String) {
        auth.withAuth { token -> api.revokeGroupInvite(token, groupId, inviteId) }
    }

    suspend fun previewChatInvite(raw: String): GroupInvitePreviewDto {
        val tokenValue = parseInviteToken(raw) ?: error("Неверная ссылка приглашения")
        return auth.withAuth { token -> api.previewGroupInvite(token, tokenValue) }
    }

    suspend fun joinChatInvite(raw: String): MembershipChangeDto {
        val tokenValue = parseInviteToken(raw) ?: error("Неверная ссылка приглашения")
        return auth.withAuth { token ->
            val change = api.joinGroupInvite(token, tokenValue)
            if (!change.pending_approval) {
                runCatching { syncChats() }
            }
            change
        }
    }

    /** Leave channel/group; remove from local list. */
    suspend fun muteChannelSubscriber(chatId: String, userId: String, durationMinutes: Int) {
        auth.withAuth { token ->
            api.muteGroupMember(token, chatId, userId, durationMinutes)
        }
    }

    suspend fun unmuteChannelSubscriber(chatId: String, userId: String) {
        auth.withAuth { token ->
            api.unmuteGroupMember(token, chatId, userId)
        }
    }

    suspend fun kickChannelSubscriber(chatId: String, userId: String) {
        auth.withAuth { token ->
            api.removeGroupMember(token, chatId, userId)
        }
    }

    suspend fun banChannelSubscriber(chatId: String, userId: String, reason: String? = null) {
        auth.withAuth { token ->
            api.banGroupMember(token, chatId, userId, reason)
        }
    }

    suspend fun updateChannelSubscriberRole(chatId: String, userId: String, role: String) {
        auth.withAuth { token ->
            api.updateGroupMemberRole(token, chatId, userId, role)
        }
    }

    suspend fun leaveChat(chatId: String) {
        auth.withAuth { token ->
            api.leaveGroup(token, chatId)
            chatMemberIds.remove(chatId)
            local.deleteChatFromList(chatId)
            local.saveSetting(channelFollowingKey(chatId), "0")
        }
    }

    suspend fun isChannelFollowing(chatId: String): Boolean =
        local.loadSetting(channelFollowingKey(chatId), "1") == "1"

    suspend fun setChannelFollowing(chatId: String, following: Boolean) {
        local.saveSetting(channelFollowingKey(chatId), if (following) "1" else "0")
    }

    /** Open channel feed (root posts). */
    suspend fun listChannelPosts(chatId: String, before: String? = null, limit: Int = 50): MessagesPageResponse =
        auth.withAuth { token ->
            api.listChannelPosts(token, chatId, before = before, limit = limit)
        }

    /** Open channel gallery (posts with media metadata). */
    suspend fun listChannelGallery(chatId: String, before: String? = null, limit: Int = 50): MessagesPageResponse =
        auth.withAuth { token ->
            api.listChannelGallery(token, chatId, before = before, limit = limit)
        }

    /**
     * Publish a server-visible channel post (no e2e). Admin-only on server.
     * Does not use the group sender-key outbox path.
     *
     * @param media open-path uploads from [uploadChannelPhoto] (max 10), with optional thumbs.
     */
    suspend fun sendChannelPost(
        chatId: String,
        body: String,
        pendingId: String = "pending-${Random.nextLong()}",
        threadRootId: String? = null,
        mediaFileIds: List<String> = emptyList(),
        media: List<MediaFileInfo> = emptyList(),
    ): Message {
        val chat = local.findChat(chatId) ?: error("Chat not found")
        require(chat.isChannel) { "sendChannelPost is only for channels" }
        val trimmed = body.trim()
        val mediaItems: List<Pair<String, String?>> = when {
            media.isNotEmpty() -> media.map { it.file_id to it.thumb_file_id }
            mediaFileIds.isNotEmpty() -> mediaFileIds.map { it to null }
            else -> emptyList()
        }
        require(trimmed.isNotEmpty() || mediaItems.isNotEmpty()) { "body or media required" }
        require(mediaItems.size <= 10) { "max 10 images per post" }
        val selfId = SessionStore.user.value?.id ?: error("No user")
        val metadata = if (mediaItems.isEmpty()) {
            null
        } else {
            buildChannelMediaMetadata(mediaItems)
        }
        // Prefer real text; media-only posts use a short visible caption fallback.
        val bodyToSend = when {
            trimmed.isNotEmpty() -> trimmed
            mediaItems.isNotEmpty() -> "📷"
            else -> error("body or media required")
        }
        val optimistic = Message(
            id = pendingId,
            chat_id = chatId,
            sender_id = selfId,
            body = bodyToSend,
            pending_id = pendingId,
            status = MessageStatus.SENDING,
            thread_root_id = threadRootId,
            metadata = metadata,
        )
        local.saveMessage(optimistic, status = MessageStatus.SENDING)
        return auth.withAuth { token ->
            val sent = api.sendMessage(
                token,
                chatId,
                SendMessageRequest(
                    body = bodyToSend,
                    pending_id = pendingId,
                    thread_root_id = threadRootId,
                    metadata = metadata,
                ),
            )
            local.saveMessage(sent.copy(status = MessageStatus.SENT), status = MessageStatus.SENT)
            sent
        }
    }

    /**
     * Open media path for channels only (plaintext photo).
     * Never call this for DM/group e2e attachments.
     */
    suspend fun uploadChannelPhoto(
        chatId: String,
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): MediaFileInfo {
        val chat = local.findChat(chatId) ?: error("Chat not found")
        require(chat.isChannel) { "uploadChannelPhoto is only for channels" }
        require(bytes.isNotEmpty()) { "empty photo" }
        return auth.withAuth { token ->
            val slot = api.createOpenMediaSlot(token, chatId, mimeType = mimeType)
            require(slot.content_mode == null || slot.content_mode == MediaContentMode.OPEN) {
                "server did not open an open media slot"
            }
            api.uploadOpenMedia(token, slot.file_id, bytes)
        }
    }

    /** Download open channel photo bytes (member). Not for e2e attachment cache. */
    suspend fun downloadChannelPhoto(fileId: String): ByteArray =
        auth.withAuth { token ->
            api.downloadOpenMedia(token, fileId)
        }

    /**
     * @param media list of full file_id + optional server thumb_file_id from open upload.
     */
    private fun buildChannelMediaMetadata(
        mediaItems: List<Pair<String, String?>>,
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        val media = kotlinx.serialization.json.buildJsonArray {
            mediaItems.forEachIndexed { index, (id, thumbId) ->
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("file_id", kotlinx.serialization.json.JsonPrimitive(id))
                        put("cover", kotlinx.serialization.json.JsonPrimitive(index == 0))
                        if (!thumbId.isNullOrBlank()) {
                            put("thumb_file_id", kotlinx.serialization.json.JsonPrimitive(thumbId))
                        }
                    },
                )
            }
        }
        return mapOf(
            "kind" to kotlinx.serialization.json.JsonPrimitive("creative_post"),
            "media" to media,
        )
    }

    suspend fun setChannelReaction(messageId: String, emoji: String): List<ReactionSummary> =
        auth.withAuth { token ->
            api.setMessageReaction(token, messageId, emoji).summary
        }

    suspend fun clearChannelReaction(messageId: String): List<ReactionSummary> =
        auth.withAuth { token ->
            api.clearMessageReaction(token, messageId).summary
        }

    private fun channelFollowingKey(chatId: String): String = "channel.following.$chatId"

    /**
     * Discover public channels for Chats search (any user, not only members).
     * Private channels never returned by the server for this endpoint.
     */
    suspend fun searchPublicChannels(query: String): List<Chat> =
        auth.withAuth { token ->
            if (!ChatListSearch.shouldSearchPublicChannels(query)) return@withAuth emptyList()
            api.searchPublicChannels(token, query.trim())
                .map { it.toChat() }
                // Server should only return public channels; re-check so a bad payload
                // never surfaces private groups/channels as "discover" hits.
                .filter { ChatListSearch.isPubliclyDiscoverable(it) }
        }

    suspend fun searchUsers(query: String): List<User> = searchPeople(query)

    /**
     * People search for Chats tab (Signal ContactSearch + Telegram people section).
     * Debounce is UI-owned (~200 ms). Match/rank rules: [ChatListSearch].
     */
    suspend fun searchPeople(query: String): List<User> =
        auth.withAuth { token ->
            val normalized = RegistrationValidation.normalizeUsername(query)
            if (normalized.isBlank()) return@withAuth emptyList()

            val selfId = SessionStore.user.value?.id
            val localHits = ChatListSearch.matchLocalPeople(knownUsers.values, query, selfId)

            if (!ChatListSearch.shouldQueryRemotePeople(query)) {
                return@withAuth localHits.distinctBy { it.id }.take(ChatListSearch.PEOPLE_RESULT_LIMIT)
            }

            val remote = runCatching {
                if (RegistrationValidation.isUsernameUsable(normalized)) {
                    api.lookupUser(token, normalized)?.let { listOf(it) }
                        ?: api.autocompleteUsers(token, normalized)
                } else {
                    api.autocompleteUsers(token, normalized)
                }
            }.getOrElse {
                api.searchUsers(token, normalized)
            }

            rememberUsersPersisted(remote + localHits)
            ChatListSearch.mergeAndRankPeople(localHits, remote, query)
        }

    suspend fun openDM(userId: String): Chat =
        auth.withAuth { token ->
            val chat = api.createDM(token, CreateDMRequest(userId))
            local.saveChat(chat, revealHidden = true)
            rememberDmPartner(chat)
            SessionStore.user.value?.id?.let { selfId ->
                pairwiseIds.getOrCreate(selfId, userId)
            }
            ensureDmSession(chat.id, userId)
            syncChats()
            chat
        }

    suspend fun ensureDmSession(chatId: String, partnerUserId: String) {
        val user = SessionStore.user.value ?: return
        auth.withAuth { token ->
            ensureDeviceRegistered(token)
            val devices = api.listUserDevices(token, partnerUserId)
            logSync("ensureDmSession chat=$chatId partner=$partnerUserId devices=${devices.size}")
            for (device in devices) {
                checkPartnerIdentityKey(partnerUserId, device.device_id, device.identity_public_key)
                if (isPartnerUntrusted(partnerUserId)) continue
                crypto.trustRemoteIdentity(user.id, partnerUserId, device.device_id)
                val bundle = api.getDeviceBundle(token, device.device_id)
                val ok = crypto.ensureSession(user.id, partnerUserId, device.device_id, bundle)
                logSync(
                    "ensureDmSession device=${device.device_id} status=${device.device_status ?: "unknown"} ok=$ok",
                )
            }
        }
    }

    suspend fun loadSafetyNumber(partnerUserId: String): SafetyNumberInfo {
        val user = SessionStore.user.value ?: error("Not authenticated")
        return auth.withAuth { token ->
            ensureDeviceRegistered(token)
            val devices = api.listUserDevices(token, partnerUserId)
            val device = devices.firstOrNull() ?: error("У контакта нет зарегистрированных устройств")
            crypto.safetyNumber(
                accountId = user.id,
                remoteAccountId = partnerUserId,
                remoteIdentityPublicKey = device.identity_public_key.decodeBase64(),
                remoteRegistrationId = device.registration_id,
            ) ?: error("Не удалось вычислить код безопасности")
        }
    }

    fun dmPartnerFor(chatId: String): String? = dmPartnerByChatId[chatId]

    // --- Messages ---

    suspend fun syncMessages(chatId: String) {
        var chat = local.findChat(chatId)
        if (chat == null) {
            runCatching { syncChats() }
            chat = local.findChat(chatId)
        }
        if (chat?.isDirectMessage == true) {
            hasMoreByChat[chatId] = false
            runCatching { syncChats() }
            val hydrated = local.findChat(chatId) ?: chat
            runCatching { resolveDmPartnerId(hydrated) }
                .onSuccess { partnerId -> ensureDmSession(chatId, partnerId) }
            processMessageQueue(chatId, force = true)
            return
        }
        auth.withAuth { token ->
            val page = api.listMessages(token, chatId)
            hasMoreByChat[chatId] = page.has_more
            val decrypted = page.messages.map { decryptStoredMessagePreservingOwnPlaintext(it) }
            local.replaceMessages(chatId, decrypted.sortedForChat())
        }
    }

    suspend fun loadOlderMessages(chatId: String, beforeMessageId: String) {
        val chat = local.findChat(chatId)
        if (chat?.isDirectMessage == true) {
            hasMoreByChat[chatId] = false
            return
        }
        auth.withAuth { token ->
            val page = api.listMessages(token, chatId, before = beforeMessageId)
            hasMoreByChat[chatId] = page.has_more
            val decrypted = page.messages.map { decryptStoredMessagePreservingOwnPlaintext(it) }
            local.prependMessages(decrypted.sortedForChat())
        }
    }

    suspend fun sendMessage(
        chatId: String,
        body: String,
        pendingId: String = "pending-${Random.nextLong()}",
        relation: MessageRelationDraft? = null,
        expiresAtSec: Long? = null,
    ): Message {
        val trimmed = body.trim()
        // Collapse repeated taps before draft recomposes empty.
        val dedupedPending = sendDedupMutex.withLock {
            val now = currentTimeMillis()
            if (SendDedupPolicy.isDuplicate(lastSendKey, lastSendAtMs, chatId, trimmed, now)) {
                logSync("sendMessage deduped chat=$chatId pending=${lastSendPendingId}")
                return@withLock lastSendPendingId
            }
            lastSendKey = SendDedupPolicy.key(chatId, trimmed)
            lastSendAtMs = now
            lastSendPendingId = pendingId
            null
        }
        if (dedupedPending != null) {
            return findEnqueuedMessage(dedupedPending)
                ?: local.findMessageByPendingId(dedupedPending)
                ?: error("Duplicate send collapsed; original message not found")
        }

        runCatching { runPrekeyWatchdog() }
            .onFailure {
                if (!recoverAuthFailure(it)) throw it
            }
        val chat = requireSendableChat(local.findChat(chatId) ?: error("Chat not found"))
        val resolvedRelation = enrichReplyPreview(
            MessageReplyPolicy.normalizeRelationForChat(chat, relation),
        )
        val jobType = if (chat.isDirectMessage) OutboxJobType.SEND_DM else OutboxJobType.SEND_GROUP
        enqueueSendJob(
            chat = chat,
            body = trimmed,
            pendingId = pendingId,
            jobType = jobType,
            relation = resolvedRelation,
            expiresAtSec = expiresAtSec,
        )
        if (chat.isDirectMessage) {
            dmPartnerByChatId[chat.id]?.let { partnerId ->
                runCatching { ensureDmSession(chat.id, partnerId) }
            }
        }
        processOutbox(maxJobs = 1)
        if (chat.isDirectMessage) {
            runCatching { processMessageQueue(chatId, force = true) }
        }
        return findEnqueuedMessage(pendingId)
    }

    private suspend fun requireSendableChat(chat: Chat): Chat {
        if (chat.isDirectMessage) return chat
        val selfId = SessionStore.user.value?.id ?: error("No user")
        val cachedMembers = chat.member_ids.ifEmpty { chatMemberIds[chat.id].orEmpty() }
        if (selfId in cachedMembers) {
            return chat.copy(member_ids = cachedMembers)
        }

        runCatching { syncChats() }
            .onFailure { logSync("send membership refresh failed chat=${chat.id}: ${it.message}") }
        val refreshed = local.findChat(chat.id)
            ?: error("У вас нет доступа к этому чату")
        val refreshedMembers = refreshed.member_ids.ifEmpty { chatMemberIds[refreshed.id].orEmpty() }
        if (selfId !in refreshedMembers) {
            error("У вас нет доступа к отправке в этот чат")
        }
        return refreshed.copy(member_ids = refreshedMembers)
    }

    private suspend fun enrichReplyPreview(relation: MessageRelationDraft?): MessageRelationDraft? {
        if (relation == null || relation.replyToMessageId.isNullOrBlank()) return relation
        if (relation.replyPreviewSenderId != null && relation.replyPreviewBody != null) return relation
        val replyToMessageId = relation.replyToMessageId ?: return relation
        val parent = local.findMessageById(replyToMessageId)
            ?: local.findMessageByPendingId(replyToMessageId)
            ?: return relation
        return relation.copy(
            replyPreviewSenderId = relation.replyPreviewSenderId ?: parent.sender_id,
            replyPreviewBody = relation.replyPreviewBody ?: parent.body,
        )
    }

    suspend fun deleteMessage(chatId: String, messageId: String) {
        val chat = local.findChat(chatId)
        val localMessage = local.findMessageById(messageId) ?: local.findMessageByPendingId(messageId)
        if (chat?.isDirectMessage == true) {
            sendDmDeleteMarker(
                chat,
                listOfNotNull(messageId, localMessage?.id, localMessage?.pending_id),
            )
        }
        runCatching {
            auth.withAuth { token -> api.deleteMessage(token, chatId, messageId) }
        }
        local.deleteMessageById(localMessage?.id ?: messageId)
        refreshChatPreviewFromLocal(chatId)
        runCatching { syncMessages(chatId) }
    }

    suspend fun deleteChatFromList(chatId: String) {
        local.deleteChatFromList(chatId)
    }

    /** Owner wipe, member leave, or local DM hide. */
    suspend fun removeConversation(chat: Chat, membershipRole: String? = null) {
        val me = SessionStore.user.value?.id
        when (ChatDeletePolicy.action(chat, me, membershipRole)) {
            ChatDeletePolicy.Action.PermanentDelete -> {
                auth.withAuth { token -> api.deleteGroup(token, chat.id) }
                local.deleteChatFromList(chat.id)
            }
            ChatDeletePolicy.Action.Leave -> leaveChat(chat.id)
            ChatDeletePolicy.Action.HideLocally -> local.deleteChatFromList(chat.id)
        }
    }

    suspend fun cancelSendingMessage(chatId: String, messageId: String): Boolean {
        val me = SessionStore.user.value?.id ?: return false
        val message = local.findMessageById(messageId)
            ?: local.findMessageByPendingId(messageId)
            ?: return false
        if (message.chat_id != chatId || message.sender_id != me || !message.isSending()) {
            return false
        }
        val pendingId = message.pending_id?.takeIf { it.isNotBlank() } ?: message.id
        local.deleteOutboxJob(pendingId)
        local.deleteMessageById(message.id)
        if (pendingId != message.id) {
            local.deleteMessageById(pendingId)
        }
        attachmentPreviewStore.remove(message.id)
        attachmentPreviewStore.remove(pendingId)
        refreshChatPreviewFromLocal(chatId)
        logSync("outbox send cancelled message=${message.id} pending=$pendingId")
        return true
    }

    fun observeAllReactions(): StateFlow<Map<String, Map<String, List<MessageReaction>>>> =
        _reactionsByChat.asStateFlow()

    fun observeReactions(chatId: String): Flow<Map<String, List<MessageReaction>>> =
        observeAllReactions().map { it[chatId].orEmpty() }

    suspend fun loadMessageReactions(chatId: String) {
        val raw = local.loadSetting(reactionSettingsKey(chatId), "")
        val parsed = if (raw.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                reactionJson.decodeFromString(reactionMapSerializer, raw)
            }.getOrDefault(emptyMap())
        }
        _reactionsByChat.value = _reactionsByChat.value + (chatId to parsed)
    }

    suspend fun toggleMessageReaction(chatId: String, messageId: String, emoji: String) {
        val userId = SessionStore.user.value?.id ?: return
        val chatMap = _reactionsByChat.value[chatId].orEmpty().toMutableMap()
        val next = chatMap[messageId].orEmpty().toggleMine(emoji, userId)
        if (next.isEmpty()) chatMap.remove(messageId) else chatMap[messageId] = next
        _reactionsByChat.value = _reactionsByChat.value + (chatId to chatMap)
        local.saveSetting(
            reactionSettingsKey(chatId),
            reactionJson.encodeToString(reactionMapSerializer, chatMap),
        )
    }

    private fun reactionSettingsKey(chatId: String) = "msg_reactions:$chatId"

    suspend fun addMessageToFavorites(message: Message) {
        val refs = loadFavoriteMessageRefs()
        val updated = FavoriteMessagesLogic.add(
            refs = refs,
            messageId = message.id,
            chatId = message.chat_id,
            addedAt = nowIso(),
        )
        if (updated === refs) return
        saveFavoriteMessageRefs(updated)
    }

    /** Bumped after pin/unpin so chat UI can reload the pinned strip. */
    private val _pinnedMessagesRevision = MutableStateFlow(0)
    val pinnedMessagesRevision: StateFlow<Int> = _pinnedMessagesRevision.asStateFlow()

    /** Message ids that were locally edited (show “изм.” in the bubble). */
    private val _editedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val editedMessageIds: StateFlow<Set<String>> = _editedMessageIds.asStateFlow()

    suspend fun pinMessage(message: Message) {
        val chatId = message.chat_id
        val messageId = MessagePinPolicy.referenceId(message)
        val refs = loadPinnedMessageRefs(chatId)
        val updated = FavoriteMessagesLogic.add(
            refs = refs,
            messageId = messageId,
            chatId = chatId,
            addedAt = nowIso(),
        )
        if (updated === refs) return
        savePinnedMessageRefs(chatId, updated)
        _pinnedMessagesRevision.value += 1
    }

    suspend fun unpinMessage(chatId: String, message: Message) {
        val refs = loadPinnedMessageRefs(chatId)
        val ids = MessagePinPolicy.candidateIds(message)
        val updated = refs.filterNot { it.messageId in ids }
        if (updated.size == refs.size) return
        savePinnedMessageRefs(chatId, updated)
        _pinnedMessagesRevision.value += 1
    }

    suspend fun isMessagePinned(chatId: String, message: Message): Boolean {
        val refs = loadPinnedMessageRefs(chatId)
        if (refs.isEmpty()) return false
        val ids = MessagePinPolicy.candidateIds(message)
        return refs.any { it.messageId in ids }
    }

    /**
     * Most recently pinned messages for [chatId] (newest pin first), resolved from local store.
     */
    suspend fun listPinnedMessages(chatId: String, limit: Int = 20): List<Message> {
        val refs = loadPinnedMessageRefs(chatId)
        if (refs.isEmpty()) return emptyList()
        val items = mutableListOf<Message>()
        for (ref in refs) {
            if (items.size >= limit) break
            val message = local.findMessageById(ref.messageId) ?: continue
            items += message
        }
        return items
    }

    suspend fun countPinnedMessages(chatId: String): Int =
        loadPinnedMessageRefs(chatId).size

    /**
     * Telegram-style edit: update own message body locally and mark as edited.
     * Empty body / unchanged body are rejected by [MessageEditPolicy].
     */
    suspend fun editMessage(message: Message, newBody: String) {
        val me = SessionStore.user.value?.id
            ?: error("Нужно войти в аккаунт")
        if (!MessageEditPolicy.canEdit(message, me)) {
            error("Это сообщение нельзя редактировать")
        }
        if (!MessageEditPolicy.canCommitEdit(message, newBody)) {
            error("Текст не изменился")
        }
        val trimmed = MessageEditPolicy.normalizeBody(newBody)
        val stored = local.findMessageById(message.id)
            ?: message.pending_id?.let { local.findMessageByPendingId(it) }
            ?: message
        val status = stored.status?.takeIf { it.isNotBlank() } ?: MessageStatus.SENT
        local.saveMessage(stored.copy(body = trimmed), status = status)
        markMessageEdited(stored.id)
        stored.pending_id?.takeIf { it.isNotBlank() }?.let { markMessageEdited(it) }
    }

    fun isMessageEdited(message: Message): Boolean {
        val ids = _editedMessageIds.value
        if (message.id in ids) return true
        val pending = message.pending_id
        return !pending.isNullOrBlank() && pending in ids
    }

    private suspend fun markMessageEdited(messageId: String) {
        if (messageId.isBlank()) return
        val next = _editedMessageIds.value + messageId
        if (next == _editedMessageIds.value) return
        _editedMessageIds.value = next
        local.saveSetting(
            KEY_EDITED_MESSAGE_IDS,
            favoritesJson.encodeToString(editedIdsSerializer, next.toList()),
        )
    }

    private suspend fun loadEditedMessageIds() {
        val raw = local.loadSetting(KEY_EDITED_MESSAGE_IDS, "")
        if (raw.isBlank()) {
            _editedMessageIds.value = emptySet()
            return
        }
        _editedMessageIds.value = runCatching {
            favoritesJson.decodeFromString(editedIdsSerializer, raw).toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun removeMessageFromFavorites(messageId: String) {
        val refs = loadFavoriteMessageRefs()
        val updated = FavoriteMessagesLogic.remove(refs, messageId)
        if (updated.size == refs.size) return
        saveFavoriteMessageRefs(updated)
    }

    suspend fun listFavoriteMessages(limit: Int = 100): List<FavoriteMessageItem> {
        val refs = loadFavoriteMessageRefs()
        if (refs.isEmpty()) return emptyList()
        val messagesById = buildMap {
            refs.forEach { ref ->
                local.findMessageById(ref.messageId)?.let { put(ref.messageId, it) }
            }
        }
        val chatsById = buildMap {
            refs.forEach { ref ->
                if (ref.chatId !in this) {
                    local.findChat(ref.chatId)?.let { put(ref.chatId, it) }
                }
            }
        }
        val resolved = FavoriteMessagesLogic.resolve(
            refs = refs,
            findMessage = messagesById::get,
            findChat = chatsById::get,
            limit = limit,
        )
        if (resolved.pruned) {
            saveFavoriteMessageRefs(resolved.aliveRefs)
        }
        return resolved.items
    }

    private suspend fun loadFavoriteMessageRefs(): List<FavoriteMessageRef> {
        val raw = local.loadSetting(KEY_FAVORITE_MESSAGES, "")
        if (raw.isBlank()) return emptyList()
        return runCatching { favoritesJson.decodeFromString(favoriteListSerializer, raw) }
            .getOrDefault(emptyList())
    }

    private suspend fun saveFavoriteMessageRefs(items: List<FavoriteMessageRef>) {
        local.saveSetting(
            KEY_FAVORITE_MESSAGES,
            favoritesJson.encodeToString(favoriteListSerializer, items),
        )
    }

    private suspend fun loadPinnedMessageRefs(chatId: String): List<FavoriteMessageRef> {
        val raw = local.loadSetting(pinnedMessagesKey(chatId), "")
        if (raw.isBlank()) return emptyList()
        return runCatching { favoritesJson.decodeFromString(favoriteListSerializer, raw) }
            .getOrDefault(emptyList())
    }

    private suspend fun savePinnedMessageRefs(chatId: String, items: List<FavoriteMessageRef>) {
        local.saveSetting(
            pinnedMessagesKey(chatId),
            favoritesJson.encodeToString(favoriteListSerializer, items),
        )
    }

    private fun pinnedMessagesKey(chatId: String): String = "pinned_messages:$chatId"

    suspend fun retryFailedMessage(messageId: String) {
        val message = local.findMessageById(messageId) ?: return
        requeueUndeliveredMessage(message)
        processOutbox(maxJobs = 1)
    }

    /**
     * Re-queue every locally undelivered own message (failed + stuck sending).
     * Returns how many messages were re-queued. Safe to call on bootstrap / chat open.
     */
    suspend fun retryUndeliveredMessages(chatId: String? = null): Int =
        retryUndeliveredMessagesWithResult(chatId).requeued

    internal suspend fun retryUndeliveredMessagesWithResult(chatId: String? = null): OutboxRecoveryResult {
        val me = SessionStore.user.value?.id ?: return OutboxRecoveryResult()
        val candidates = UndeliveredMessagePolicy.selectForRetry(
            messages = local.listAllMessages(),
            currentUserId = me,
            chatId = chatId,
        )
        if (candidates.isEmpty()) {
            // Still drain any pending outbox jobs (message row may already be mid-flight).
            return OutboxRecoveryResult(outbox = processOutbox())
        }
        var requeued = 0
        candidates.forEach { message ->
            if (requeueUndeliveredMessage(message)) requeued++
        }
        val outboxResult = processOutbox(maxJobs = maxOf(candidates.size, 50))
        logSync("outbox recovery: requeued=$requeued chat=${chatId ?: "*"}")
        return OutboxRecoveryResult(requeued = requeued, outbox = outboxResult)
    }

    private suspend fun requeueUndeliveredMessage(message: Message): Boolean {
        val me = SessionStore.user.value?.id
        if (!UndeliveredMessagePolicy.isOwnUndelivered(message, me)) return false
        val pendingId = message.pending_id ?: message.id
        val chat = local.findChat(message.chat_id) ?: return false
        val body = message.body
            .removeSuffix(" (ошибка отправки)")
            .ifBlank { return false }
        local.updateMessageStatus(pendingId, MessageStatus.SENDING)
        // Prefer updating message row id as well when it differs from pending id.
        if (message.id != pendingId) {
            runCatching { local.updateMessageStatus(message.id, MessageStatus.SENDING) }
        }
        val existing = local.findOutboxJob(pendingId)
        if (existing == null) {
            reenqueueFromMessage(message.copy(body = body, status = MessageStatus.SENDING), chat)
        } else {
            local.updateOutboxJob(
                jobId = pendingId,
                status = OutboxJobStatus.PENDING,
                attempts = 0,
                nextAttemptAt = null,
                lastError = null,
            )
        }
        return true
    }

    suspend fun uploadEncryptedBackupToCloud(result: EncryptedBackupResult) {
        auth.withAuth { token ->
            val eventId = local.loadSetting(KEY_LAST_SYNC_CURSOR).toLongOrNull()
                ?: currentTimeMillis()
            api.saveSyncSnapshot(
                token = token,
                request = SaveSyncSnapshotRequest(
                    event_id = eventId,
                    snapshot_data = result.bytes.encodeBase64(),
                ),
            )
            local.saveSetting(KEY_LAST_BACKUP_UPLOADED_AT, result.createdAt)
        }
    }

    suspend fun fetchCloudBackupInfo(): CloudBackupInfo? =
        auth.withAuth { token ->
            val snapshot = api.getSyncSnapshot(token)
            snapshot.snapshot_data
                ?.takeIf { it.isNotBlank() }
                ?.let { CloudBackupInfo(snapshot.event_id, snapshot.created_at) }
        }

    suspend fun restoreEncryptedBackupFromCloud(passphrase: CharArray, recoveryKey: String) {
        val bytes = auth.withAuth { token ->
            api.getSyncSnapshot(token).snapshot_data
                ?.takeIf { it.isNotBlank() }
                ?.decodeBase64()
                ?: error("На сервере нет резервной копии")
        }
        prepareForBackupRestore()
        EncryptedBackupImporter.import(bytes, passphrase, recoveryKey.trim())
        AppLifecycle.restart()
    }

    private fun prepareForBackupRestore() {
        stopWebSocket()
        stopPrekeyWatchdog()
        local.close()
    }

    /**
     * Signal JobManager style: durable jobs run through the same auth path as interactive API
     * ([SessionTokenProvider.withAuth] inside perform*). No separate “is SessionStore.token set?”
     * pre-check — that diverged from disk credentials and left messages SENDING forever.
     *
     * Hard stop only when password re-login is required (Signal: not registered / forced re-reg).
     */
    suspend fun processOutbox(maxJobs: Int = 50): OutboxProcessingResult = outboxMutex.withLock {
        if (SessionStore.reauthRequired.value) {
            logSync("outbox deferred: reauth required")
            if (local.hasPendingOutboxJobs()) {
                scheduleBackgroundSyncWork()
            }
            return@withLock OutboxProcessingResult(
                authDeferred = true,
                lastError = OUTBOX_AUTH_PENDING_ERROR,
            )
        }
        local.resetRecoverableOutboxBackoff()
        val jobs = local.listDueOutboxJobs(nowIso(), maxJobs)
        var summary = OutboxProcessingResult()
        for (job in jobs) {
            summary = summary.copy(attempted = summary.attempted + 1)
            val payload = runCatching { decodeOutboxPayload(job.payloadJson) }.getOrNull()
            if (payload == null) {
                logSync("outbox payload decode failed job=${job.id}")
                local.deleteOutboxJob(job.id)
                local.updateMessageStatus(job.id, MessageStatus.FAILED)
                summary = summary.copy(
                    permanentFailures = summary.permanentFailures + 1,
                    lastError = "outbox payload decode failed",
                )
                continue
            }
            local.updateOutboxJob(
                jobId = job.id,
                status = OutboxJobStatus.PROCESSING,
                attempts = job.attempts,
                nextAttemptAt = job.nextAttemptAt,
                lastError = job.lastError,
            )
            val result = runCatching { performOutboxSend(payload) }
            if (result.isSuccess) {
                local.deleteOutboxJob(job.id)
                summary = summary.copy(sent = summary.sent + 1)
                continue
            }
            val throwable = result.exceptionOrNull()
            if (throwable is OutboxSendCancelledException) {
                local.deleteOutboxJob(job.id)
                continue
            }
            if (isAuthFailure(throwable)) {
                handleOutboxAuthFailure(job, throwable!!)
                return@withLock summary.copy(
                    authDeferred = true,
                    lastError = throwable.message ?: OUTBOX_AUTH_PENDING_ERROR,
                )
            }
            val attempts = job.attempts + 1
            val error = throwable?.message ?: "send failed"
            logSync("outbox send failed job=${job.id} type=${job.jobType} attempt=$attempts error=$error")
            if (OutboxRecoverableErrors.isWaitingForRecipient(error)) {
                deferOutboxJob(
                    job = job,
                    attempts = attempts,
                    lastError = null,
                    messageStatus = MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS,
                )
                summary = summary.copy(transientFailures = summary.transientFailures + 1)
                scheduleBackgroundSyncWork()
                continue
            }
            if (isPermanentOutboxError(error, throwable, job.jobType)) {
                local.deleteOutboxJob(job.id)
                local.updateMessageStatus(job.id, MessageStatus.FAILED)
                summary = summary.copy(
                    permanentFailures = summary.permanentFailures + 1,
                    lastError = error,
                )
                continue
            }
            if (attempts >= OUTBOX_MAX_ATTEMPTS) {
                local.deleteOutboxJob(job.id)
                local.updateMessageStatus(job.id, MessageStatus.FAILED)
                summary = summary.copy(
                    permanentFailures = summary.permanentFailures + 1,
                    lastError = error,
                )
            } else {
                deferOutboxJob(
                    job = job,
                    attempts = attempts,
                    lastError = error,
                    messageStatus = MessageStatus.SENDING,
                )
                summary = summary.copy(
                    transientFailures = summary.transientFailures + 1,
                    lastError = error,
                )
            }
            scheduleBackgroundSyncWork()
        }
        summary
    }

    private suspend fun deferOutboxJob(
        job: OutboxJobRecord,
        attempts: Int,
        lastError: String?,
        messageStatus: String,
    ) {
        val retryAt = formatIsoFromMillis(
            currentTimeMillis() + outboxRetryDelayMs(attempts),
        )
        local.updateOutboxJob(
            jobId = job.id,
            status = OutboxJobStatus.PENDING,
            attempts = attempts,
            nextAttemptAt = retryAt,
            lastError = lastError,
        )
        local.updateMessageStatus(job.id, messageStatus)
    }

    private suspend fun handleOutboxAuthFailure(job: OutboxJobRecord, throwable: Throwable) {
        handleAuthFailure(throwable)
        val attempts = job.attempts + 1
        val createdAtMs = parseIsoTimestampMillis(job.createdAt) ?: currentTimeMillis()
        val ageMs = currentTimeMillis() - createdAtMs
        if (attempts >= OUTBOX_AUTH_MAX_ATTEMPTS || ageMs >= OUTBOX_AUTH_FAILURE_TIMEOUT_MS) {
            logSync("outbox auth failed job=${job.id} attempts=$attempts — mark failed")
            local.deleteOutboxJob(job.id)
            local.updateMessageStatus(job.id, MessageStatus.FAILED)
            return
        }
        val retryAt = formatIsoFromMillis(currentTimeMillis() + outboxRetryDelayMs(attempts))
        local.updateOutboxJob(
            jobId = job.id,
            status = OutboxJobStatus.PENDING,
            attempts = attempts,
            nextAttemptAt = retryAt,
            lastError = OUTBOX_AUTH_PENDING_ERROR,
        )
        local.updateMessageStatus(job.id, MessageStatus.SENDING)
        scheduleBackgroundSyncWork()
    }

    /**
     * Background / bootstrap mailbox drain.
     * Same local-notification policy as push wake so inbound mail is visible on OEMs
     * (e.g. MIUI) where data-only FCM often fails to start a dead process.
     */
    suspend fun drainPendingSync() {
        PushWakeCoordinator.presentAfterMailboxDrain(drainPendingSyncForPush())
    }

    /**
     * Wake path after FCM/APNs/UnifiedPush: always poll mailbox (force), then report
     * how many inbound messages need a local system notification.
     * Callers that should show UI use [PushWakeCoordinator.presentAfterMailboxDrain].
     */
    suspend fun drainPendingSyncForPush(): PushWakeResult {
        if (SessionStore.reauthRequired.value) {
            logSync("background sync deferred: reauth required")
            return PushWakeResult.Empty
        }
        local.resetRecoverableOutboxBackoff()
        processOutbox()
        // Always drain the full queue (multi-chat), even if a chat is open.
        val applied = processMessageQueue(force = true)
        if (local.hasPendingOutboxJobs()) {
            scheduleBackgroundSyncWork()
        }
        val prefs = cachedNotificationPreferences()
        val shouldNotify = NotificationSettingsPolicy.shouldShowMessageNotification(
            messagesEnabled = prefs?.messages_enabled ?: true,
            appliedInbound = applied,
            appInForeground = AppLifecycle.isInForeground(),
            hasOpenChat = activeChatId != null,
        )
        val copy = NotificationSettingsPolicy.messageNotificationCopy(
            count = applied,
            showSenderName = prefs?.show_sender_name == true,
            showMessagePreview = prefs?.show_message_preview == true,
            senderName = null,
            previewBody = null,
        )
        return PushWakeResult(
            appliedInbound = applied,
            lastChatId = null,
            shouldShowLocalNotification = shouldNotify,
            previewTitle = copy.title,
            previewBody = copy.body,
        )
    }

    suspend fun syncLinkedDevices() {
        SessionStore.user.value ?: return
        auth.withAuth { token ->
            val since = local.loadSetting(KEY_LAST_SYNC_CURSOR).takeIf { it.isNotBlank() }
            // Legacy /api/sync is used here as a chat snapshot refresh. It does not expose a stable
            // next-page cursor, so paginating by server_time can loop or skip timestamp-tied events.
            val response = api.sync(token, since = since)
            if (response.chats.isNotEmpty()) {
                local.replaceChats(response.chats)
                cacheChatMembers(response.chats)
            }
            val nextCursor = response.server_time
                .takeIf { it.isNotBlank() }
                ?: response.events.maxOfOrNull { it.created_at }
            if (!nextCursor.isNullOrBlank()) {
                local.saveSetting(KEY_LAST_SYNC_CURSOR, nextCursor)
            }
        }
    }

    fun bindBackgroundSync() {
        BackgroundSyncBridge.processPending = {
            drainPendingSync()
        }
    }

    suspend fun sendAttachment(
        chatId: String,
        fileBytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
        kind: AttachmentKind? = null,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveform: List<Int> = emptyList(),
        pendingId: String = "pending-${Random.nextLong()}",
    ): Message {
        val chat = local.findChat(chatId) ?: error("Chat not found")
        check(chat.supportsE2eAttachments) { "Encrypted attachments are only supported in direct messages and groups" }
        val user = SessionStore.user.value ?: error("No user")
        if (chat.isDirectMessage) {
            val partnerId = resolveDmPartnerId(chat)
            if (isPartnerUntrusted(partnerId)) {
                error("Сначала подтвердите новый ключ безопасности собеседника")
            }
        }
        val validatedMedia = AttachmentMediaValidator.validateOutgoing(fileBytes, fileName, mimeType)
        val encrypted = withContext(Dispatchers.Default) {
            FileAttachmentCrypto.encrypt(fileBytes)
        }
        val label = validatedMedia.fileName?.takeIf { it.isNotBlank() } ?: "Вложение"
        val resolvedMimeType = validatedMedia.mimeType
        val resolvedKind = kind?.takeUnless { it == AttachmentKind.UNKNOWN }
            ?: resolveAttachmentKind(resolvedMimeType, validatedMedia.fileName)
        val resolvedThumbnail = thumbnailBytes ?: if (validatedMedia.canPreviewImage) {
            AttachmentThumbnailer.createThumbnail(fileBytes, resolvedMimeType)
        } else {
            null
        }
        val cacheId = attachmentCacheIdForOutgoing(pendingId)
        persistentAttachmentCache.putOutgoingEncrypted(
            cacheId = cacheId,
            encryptedBytes = encrypted.ciphertext,
            chatId = chat.id,
            ownerAccountId = user.id,
            messageId = pendingId,
            fileName = validatedMedia.fileName,
            mimeType = resolvedMimeType,
            plaintextSize = validatedMedia.plaintextSize,
            kind = resolvedKind,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = encodeWaveform(waveform),
        )
        persistentAttachmentCache.storeThumbnailPlaintext(
            cacheId = cacheId,
            thumbnailPlaintext = resolvedThumbnail,
        )
        attachmentPreviewStore.cache(
            messageId = pendingId,
            bytes = fileBytes,
            fileName = validatedMedia.fileName,
            mimeType = resolvedMimeType,
            kind = resolvedKind,
            thumbnailBytes = resolvedThumbnail,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
        )
        enqueueSendJob(
            chat = chat,
            body = "📎 $label",
            pendingId = pendingId,
            jobType = attachmentOutboxJobType(chat),
            localAttachmentCacheId = cacheId,
            attachmentFileKeyBase64 = encrypted.fileKey.encodeBase64(),
            attachmentFileName = validatedMedia.fileName,
            attachmentMimeType = resolvedMimeType,
            attachmentPlaintextSize = validatedMedia.plaintextSize,
            attachmentKind = resolvedKind,
            attachmentWidth = width,
            attachmentHeight = height,
            attachmentDurationMs = durationMs,
            attachmentThumbnailBase64 = resolvedThumbnail?.encodeBase64(),
            attachmentWaveform = waveform,
        )
        processOutbox(maxJobs = 1)
        return findEnqueuedMessage(pendingId)
    }

    suspend fun sendEncryptedAttachmentSpool(
        chatId: String,
        encryptedPath: String,
        fileKey: ByteArray,
        encryptedSize: Long,
        plaintextSize: Long,
        fileName: String? = null,
        mimeType: String? = null,
        kind: AttachmentKind? = null,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveform: List<Int> = emptyList(),
        pendingId: String = "pending-${Random.nextLong()}",
    ): Message {
        val chat = local.findChat(chatId) ?: error("Chat not found")
        check(chat.supportsE2eAttachments) { "Encrypted attachments are only supported in direct messages and groups" }
        val user = SessionStore.user.value ?: error("No user")
        if (chat.isDirectMessage) {
            val partnerId = resolveDmPartnerId(chat)
            if (isPartnerUntrusted(partnerId)) {
                error("Сначала подтвердите новый ключ безопасности собеседника")
            }
        }
        val validatedMedia = AttachmentMediaValidator.validateOutgoingMetadata(plaintextSize, fileName, mimeType)
        val label = validatedMedia.fileName?.takeIf { it.isNotBlank() } ?: "Вложение"
        val resolvedKind = kind?.takeUnless { it == AttachmentKind.UNKNOWN }
            ?: resolveAttachmentKind(validatedMedia.mimeType, validatedMedia.fileName)
        val cacheId = attachmentCacheIdForOutgoing(pendingId)
        persistentAttachmentCache.putOutgoingEncryptedFile(
            cacheId = cacheId,
            encryptedPath = encryptedPath,
            encryptedSize = encryptedSize,
            chatId = chat.id,
            ownerAccountId = user.id,
            messageId = pendingId,
            fileName = validatedMedia.fileName,
            mimeType = validatedMedia.mimeType,
            plaintextSize = validatedMedia.plaintextSize,
            kind = resolvedKind,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = encodeWaveform(waveform),
        )
        persistentAttachmentCache.storeThumbnailPlaintext(
            cacheId = cacheId,
            thumbnailPlaintext = thumbnailBytes,
        )
        attachmentPreviewStore.cache(
            messageId = pendingId,
            bytes = ByteArray(0),
            fileName = validatedMedia.fileName,
            mimeType = validatedMedia.mimeType,
            kind = resolvedKind,
            thumbnailBytes = thumbnailBytes,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
        )
        runCatching {
            cacheOutgoingMediaPreviewFromSpool(
                cacheId = cacheId,
                fileKey = fileKey,
                kind = resolvedKind,
                fileName = validatedMedia.fileName,
                mimeType = validatedMedia.mimeType,
                expectedPlaintextSize = validatedMedia.plaintextSize,
                messageId = pendingId,
            )
        }.onFailure {
            logSync("attachment preview warmup skipped pending=$pendingId: ${it.message}")
        }
        enqueueSendJob(
            chat = chat,
            body = "📎 $label",
            pendingId = pendingId,
            jobType = attachmentOutboxJobType(chat),
            localAttachmentCacheId = cacheId,
            attachmentFileKeyBase64 = fileKey.encodeBase64(),
            attachmentFileName = validatedMedia.fileName,
            attachmentMimeType = validatedMedia.mimeType,
            attachmentPlaintextSize = validatedMedia.plaintextSize,
            attachmentKind = resolvedKind,
            attachmentWidth = width,
            attachmentHeight = height,
            attachmentDurationMs = durationMs,
            attachmentThumbnailBase64 = thumbnailBytes?.encodeBase64(),
            attachmentWaveform = waveform,
        )
        processOutbox(maxJobs = 1)
        return findEnqueuedMessage(pendingId)
    }

    private suspend fun cacheOutgoingMediaPreviewFromSpool(
        cacheId: String,
        fileKey: ByteArray,
        kind: AttachmentKind,
        fileName: String?,
        mimeType: String?,
        expectedPlaintextSize: Long,
        messageId: String,
    ) {
        val canWarmAudio = kind == AttachmentKind.VOICE || kind == AttachmentKind.AUDIO
        val canWarmImage = isImageAttachment(mimeType, fileName) &&
            expectedPlaintextSize <= AttachmentMediaValidator.MAX_THUMBNAIL_SOURCE_BYTES
        val canWarmVideo = kind == AttachmentKind.VIDEO &&
            expectedPlaintextSize <= AttachmentMediaValidator.MAX_ATTACHMENT_BYTES
        if (!canWarmAudio && !canWarmImage && !canWarmVideo) return
        val encryptedBlob = persistentAttachmentCache.readEncrypted(cacheId) ?: return
        val plaintext = withContext(Dispatchers.Default) {
            FileAttachmentCrypto.decrypt(fileKey, encryptedBlob)
        } ?: return
        val validated = AttachmentMediaValidator.validatePlaintextForPreview(
            bytes = plaintext,
            fileName = fileName,
            mimeType = mimeType,
            expectedPlaintextSize = expectedPlaintextSize,
        ) ?: return
        if (canWarmImage && !validated.canPreviewImage && !canWarmAudio && !canWarmVideo) return
        val existing = attachmentPreviewStore.state.value[messageId]
        attachmentPreviewStore.cache(
            messageId = messageId,
            bytes = when {
                canWarmAudio || canWarmVideo -> plaintext
                validated.canPreviewImage -> plaintext
                else -> ByteArray(0)
            },
            fileName = validated.fileName,
            mimeType = if (canWarmImage && validated.canPreviewImage) validated.mimeType else mimeType,
            kind = kind,
            thumbnailBytes = existing?.thumbnailBytes,
            width = existing?.width,
            height = existing?.height,
            durationMs = existing?.durationMs,
            waveform = existing?.waveform ?: emptyList(),
        )
        if (validated.canPreviewImage) {
            persistentAttachmentCache.storeThumbnailPlaintext(
                cacheId = cacheId,
                thumbnailPlaintext = AttachmentThumbnailer.createThumbnail(plaintext, validated.mimeType),
            )
        }
    }

    private fun encodeWaveform(waveform: List<Int>): String? =
        waveform
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.coerceIn(0, 100).toString() }

    private fun decodeWaveform(raw: String?): List<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }
            .orEmpty()

    private suspend fun findEnqueuedMessage(pendingId: String): Message =
        local.findMessageById(pendingId)
            ?: local.findMessageByPendingId(pendingId)
            ?: error("Message not found after enqueue")

    private suspend fun performOutboxSend(payload: OutboxSendPayload): Message {
        val chat = local.findChat(payload.chat_id) ?: error("Chat not found")
        return when (payload.job_type) {
            OutboxJobType.SEND_DM -> performDmSend(chat, payload)
            OutboxJobType.SEND_GROUP -> performGroupSend(chat, payload)
            OutboxJobType.SEND_DM_ATTACHMENT -> performDmAttachmentSend(chat, payload)
            OutboxJobType.SEND_GROUP_ATTACHMENT -> performGroupAttachmentSend(chat, payload)
            else -> error("Unknown outbox job ${payload.job_type}")
        }
    }

    private fun attachmentOutboxJobType(chat: Chat): String =
        if (chat.isDirectMessage) OutboxJobType.SEND_DM_ATTACHMENT else OutboxJobType.SEND_GROUP_ATTACHMENT

    private suspend fun enqueueSendJob(
        chat: Chat,
        body: String,
        pendingId: String,
        jobType: String,
        relation: MessageRelationDraft? = null,
        expiresAtSec: Long? = null,
        attachment: SealedAttachmentRef? = null,
        encryptedAttachmentBase64: String? = null,
        localAttachmentCacheId: String? = null,
        attachmentFileKeyBase64: String? = null,
        attachmentFileName: String? = null,
        attachmentMimeType: String? = null,
        attachmentPlaintextSize: Long? = null,
        attachmentKind: AttachmentKind? = null,
        attachmentWidth: Int? = null,
        attachmentHeight: Int? = null,
        attachmentDurationMs: Long? = null,
        attachmentThumbnailBase64: String? = null,
        attachmentWaveform: List<Int> = emptyList(),
    ) {
        val user = SessionStore.user.value ?: error("No user")
        val visibility = relation?.visibility ?: MESSAGE_VISIBILITY_MAIN
        val createdAt = nowIso()
        val optimistic = Message(
            id = pendingId,
            chat_id = chat.id,
            sender_id = user.id,
            body = body,
            pending_id = pendingId,
            status = MessageStatus.SENDING,
            created_at = createdAt,
            reply_to_message_id = relation?.replyToMessageId,
            reply_preview_sender_id = relation?.replyPreviewSenderId,
            reply_preview_body = relation?.replyPreviewBody,
            thread_root_id = relation?.threadRootId,
            thread_parent_id = relation?.threadParentId,
            visibility = visibility,
            expires_at = messageExpiresAt(createdAt, expiresAtSec),
        )
        local.saveMessage(optimistic, status = MessageStatus.SENDING)
        refreshChatPreview(optimistic)
        val resolvedAttachment = attachment ?: if (attachmentFileKeyBase64 != null) {
            SealedAttachmentRef(
                attachment_id = "",
                file_key = attachmentFileKeyBase64,
                file_name = attachmentFileName,
                mime_type = attachmentMimeType,
                plaintext_size = attachmentPlaintextSize ?: 0,
                kind = attachmentKind?.wireName,
                width = attachmentWidth,
                height = attachmentHeight,
                duration_ms = attachmentDurationMs,
                thumbnail = attachmentThumbnailBase64,
                waveform = attachmentWaveform,
            )
        } else {
            null
        }
        val payload = OutboxSendPayload(
            chat_id = chat.id,
            body = body,
            pending_id = pendingId,
            job_type = jobType,
            relation = relation,
            expires_at_sec = expiresAtSec,
            attachment = resolvedAttachment,
            local_attachment_cache_id = localAttachmentCacheId,
            encrypted_attachment_base64 = encryptedAttachmentBase64,
        )
        local.enqueueOutboxJob(
            OutboxJobRecord(
                id = pendingId,
                jobType = jobType,
                chatId = chat.id,
                payloadJson = encodeOutboxPayload(payload),
                status = OutboxJobStatus.PENDING,
                attempts = 0,
                nextAttemptAt = null,
                createdAt = createdAt,
                lastError = null,
            ),
        )
        scheduleBackgroundSyncWork()
    }

    private suspend fun reenqueueFromMessage(message: Message, chat: Chat) {
        val pendingId = message.pending_id ?: message.id
        val jobType = if (chat.isDirectMessage) OutboxJobType.SEND_DM else OutboxJobType.SEND_GROUP
        val createdAt = message.created_at ?: nowIso()
        val relation = MessageRelationDraft(
            replyToMessageId = message.reply_to_message_id,
            replyPreviewSenderId = message.reply_preview_sender_id,
            replyPreviewBody = message.reply_preview_body,
            threadRootId = message.thread_root_id,
            threadParentId = message.thread_parent_id,
            visibility = message.visibility,
        ).takeIf { draft ->
            draft.replyToMessageId != null ||
                draft.replyPreviewSenderId != null ||
                draft.replyPreviewBody != null ||
                draft.threadRootId != null ||
                draft.threadParentId != null ||
                draft.visibility != null
        }
        val payload = OutboxSendPayload(
            chat_id = chat.id,
            body = message.body,
            pending_id = pendingId,
            job_type = jobType,
            relation = relation,
        )
        local.enqueueOutboxJob(
            OutboxJobRecord(
                id = pendingId,
                jobType = jobType,
                chatId = chat.id,
                payloadJson = encodeOutboxPayload(payload),
                status = OutboxJobStatus.PENDING,
                attempts = 0,
                nextAttemptAt = null,
                createdAt = createdAt,
                lastError = null,
            ),
        )
        scheduleBackgroundSyncWork()
    }

    private fun scheduleBackgroundSyncWork() {
        scheduleBackgroundSync()
    }

    private suspend fun performGroupSend(
        chat: Chat,
        payload: OutboxSendPayload,
    ): Message {
        val body = payload.body
        val pendingId = payload.pending_id
        val relation = payload.relation
        val expiresAtSec = payload.expires_at_sec
        val user = SessionStore.user.value ?: error("No user")
        ensureOutboxSendActive(pendingId)
        return auth.withAuth { token ->
            val identity = crypto.ensureDeviceIdentity(user.id)
            ensureGroupSenderKeyDistribution(chat)

            val visibility = relation?.visibility ?: MESSAGE_VISIBILITY_MAIN
            val optimistic = local.findMessageById(pendingId)
            val createdAt = optimistic?.created_at ?: nowIso()
            val sealedPlaintext = encodeSealedGroupPayload(
                SealedGroupPayload(
                    sender_account_id = user.id,
                    sender_device_id = identity.deviceId,
                    chat_id = chat.id,
                    body = body,
                    client_message_id = pendingId,
                    attachment = payload.attachment,
                    reply_to_message_id = relation?.replyToMessageId,
                    reply_preview_sender_id = relation?.replyPreviewSenderId,
                    reply_preview_body = relation?.replyPreviewBody,
                    thread_root_id = relation?.threadRootId,
                    thread_parent_id = relation?.threadParentId,
                    visibility = visibility,
                    expires_at_sec = expiresAtSec,
                ),
            )
            val encrypted = encryptGroupMessageWithPreparedSenderKey(
                accountId = user.id,
                deviceId = identity.deviceId,
                chatId = chat.id,
                chat = chat,
                plaintext = sealedPlaintext,
            )

            ensureOutboxSendActive(pendingId)
            val sent = api.sendMessage(
                token = token,
                chatId = chat.id,
                request = SendMessageRequest(
                    pending_id = pendingId,
                    reply_to_message_id = relation?.replyToMessageId,
                    thread_root_id = relation?.threadRootId,
                    thread_parent_id = relation?.threadParentId,
                    visibility = visibility,
                    envelope_type = encrypted.envelopeType,
                    ciphertext = encrypted.ciphertext.encodeBase64(),
                    sender_device_id = identity.deviceId,
                ),
            )
            val resolved = preserveOwnOutgoingPlaintext(
                message = decryptStoredMessage(sent),
                localFallback = optimistic,
                relation = relation,
                fallbackBody = body,
            )
            local.deleteMessageById(pendingId)
            local.saveMessage(resolved, status = MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS)
            if (payload.attachment != null) {
                attachmentPreviewStore.transfer(pendingId, resolved.id)
                persistentAttachmentCache.metadata.relinkMessage(
                    fromMessageId = pendingId,
                    toMessageId = resolved.id,
                    accessedAt = nowIso(),
                )
                payload.local_attachment_cache_id?.let { cacheId ->
                    persistentAttachmentCache.metadata.linkMessage(
                        cacheId = cacheId,
                        messageId = resolved.id,
                        accessedAt = nowIso(),
                    )
                }
            }
            refreshChatPreview(resolved)
            resolved
        }
    }

    private suspend fun performGroupAttachmentSend(
        chat: Chat,
        payload: OutboxSendPayload,
    ): Message {
        val pendingId = payload.pending_id
        ensureOutboxSendActive(pendingId)
        val cacheId = payload.local_attachment_cache_id
        val legacyEncryptedBlob = payload.encrypted_attachment_base64?.decodeBase64()
        val attachmentTemplate = payload.attachment ?: error("Attachment metadata missing")
        return auth.withAuth { token ->
            val attachmentId = attachmentTransferManager.uploadOutgoing(
                token = token,
                cacheId = cacheId,
                legacyEncryptedBytes = legacyEncryptedBlob,
                kind = AttachmentKind.fromWireName(attachmentTemplate.kind),
                mimeType = attachmentTemplate.mime_type,
            )
            ensureOutboxSendActive(pendingId)
            val sent = performGroupSend(
                chat = chat,
                payload = payload.copy(
                    job_type = OutboxJobType.SEND_GROUP,
                    attachment = attachmentTemplate.copy(attachment_id = attachmentId),
                    local_attachment_cache_id = cacheId,
                    encrypted_attachment_base64 = null,
                ),
            )
            runCatching { maintainMediaCache(force = true) }
                .onFailure { logSync("media cache maintenance after upload failed: ${it.message}") }
            sent
        }
    }

    private suspend fun performDmAttachmentSend(
        chat: Chat,
        payload: OutboxSendPayload,
    ): Message {
        val pendingId = payload.pending_id
        ensureOutboxSendActive(pendingId)
        val cacheId = payload.local_attachment_cache_id
        val legacyEncryptedBlob = payload.encrypted_attachment_base64?.decodeBase64()
        val attachmentTemplate = payload.attachment ?: error("Attachment metadata missing")
        return auth.withAuth { token ->
            val attachmentId = attachmentTransferManager.uploadOutgoing(
                token = token,
                cacheId = cacheId,
                legacyEncryptedBytes = legacyEncryptedBlob,
                kind = AttachmentKind.fromWireName(attachmentTemplate.kind),
                mimeType = attachmentTemplate.mime_type,
            )
            ensureOutboxSendActive(pendingId)
            val sent = performDmSend(
                chat = chat,
                payload = payload.copy(
                    job_type = OutboxJobType.SEND_DM,
                    attachment = attachmentTemplate.copy(attachment_id = attachmentId),
                    local_attachment_cache_id = cacheId,
                    encrypted_attachment_base64 = null,
                ),
            )
            runCatching { maintainMediaCache(force = true) }
                .onFailure { logSync("media cache maintenance after upload failed: ${it.message}") }
            sent
        }
    }

    private suspend fun performDmSend(
        chat: Chat,
        payload: OutboxSendPayload,
    ): Message {
        val body = payload.body
        val pendingId = payload.pending_id
        val attachment = payload.attachment
        val relation = payload.relation
        val expiresAtSec = payload.expires_at_sec
        val user = SessionStore.user.value ?: error("No user")
        val partnerId = resolveDmPartnerId(chat)
        if (isPartnerUntrusted(partnerId)) {
            error("Сначала подтвердите новый ключ безопасности собеседника")
        }
        ensureOutboxSendActive(pendingId)
        return auth.withAuth { token ->
            val identity = crypto.ensureDeviceIdentity(user.id)
            ensureDmSession(chat.id, partnerId)
            if (isPartnerUntrusted(partnerId)) {
                error("Сначала подтвердите новый ключ безопасности собеседника")
            }

            val visibility = relation?.visibility ?: MESSAGE_VISIBILITY_MAIN
            val createdAt = local.findMessageById(pendingId)?.created_at ?: nowIso()
            val pairwiseId = pairwiseIds.getOrCreate(user.id, partnerId)
            val sealedPlaintext = encodeSealedDmPayload(
                SealedDmPayload(
                    pairwise_id = pairwiseId,
                    sender_account_id = user.id,
                    sender_device_id = identity.deviceId,
                    chat_id = chat.id,
                    body = body,
                    client_message_id = pendingId,
                    created_at = createdAt,
                    attachment = attachment,
                    expires_at_sec = expiresAtSec,
                    reply_to_message_id = relation?.replyToMessageId,
                    reply_preview_sender_id = relation?.replyPreviewSenderId,
                    reply_preview_body = relation?.replyPreviewBody,
                    thread_root_id = relation?.threadRootId,
                    thread_parent_id = relation?.threadParentId,
                    visibility = visibility,
                ),
            )
            // Partner devices + own other devices (Signal multi-device: history follows the
            // account, not a single DeviceID). Without self-fanout, phone B never sees what
            // phone A sent and re-login on a new device looks like "wiped history".
            val envelopes = buildDmRelayEnvelopes(
                token = token,
                userId = user.id,
                partnerId = partnerId,
                pairwiseId = pairwiseId,
                sealedPlaintext = sealedPlaintext,
                includeOwnOtherDevices = true,
            )

            ensureOutboxSendActive(pendingId)
            val relay = api.relayMessage(
                token = token,
                deviceId = identity.deviceId,
                request = RelayMessageRequest(
                    pairwise_id = pairwiseId,
                    client_message_id = pendingId,
                    envelopes = envelopes,
                    expires_at_sec = expiresAtSec,
                ),
            )
            val optimistic = local.findMessageById(pendingId)
            val firstEnvelope = envelopes.firstOrNull()
            val sent = (optimistic ?: Message(
                id = pendingId,
                chat_id = chat.id,
                sender_id = user.id,
                body = body,
                pending_id = pendingId,
                created_at = createdAt,
            )).copy(
                id = pendingId,
                pending_id = pendingId,
                status = MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS,
                envelope_type = firstEnvelope?.envelope_type,
                ciphertext = firstEnvelope?.ciphertext,
                sender_device_id = identity.deviceId,
            )
            local.deleteMessageById(pendingId)
            local.saveMessage(sent, status = MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS)
            attachmentPreviewStore.transfer(pendingId, sent.id)
            persistentAttachmentCache.metadata.relinkMessage(
                fromMessageId = pendingId,
                toMessageId = sent.id,
                accessedAt = nowIso(),
            )
            payload.local_attachment_cache_id?.let { cacheId ->
                persistentAttachmentCache.metadata.linkMessage(
                    cacheId = cacheId,
                    messageId = sent.id,
                    accessedAt = nowIso(),
                )
            }
            refreshChatPreview(sent)
            runCatching { uploadSecureHistoryIfEnabled() }
            sent
        }
    }

    suspend fun processMessageQueue(chatId: String? = null, force: Boolean = false): Int = messageQueueMutex.withLock {
        val user = SessionStore.user.value ?: return@withLock 0
        val now = currentTimeMillis()
        val minInterval = if (chatId != null) QUEUE_POLL_OPEN_CHAT_MS else QUEUE_POLL_BACKGROUND_MS
        if (!force && now - lastQueuePollMs < minInterval) {
            return@withLock 0
        }
        lastQueuePollMs = now
        var appliedMessages = 0
        try {
            auth.withAuth { token ->
                val identity = crypto.ensureDeviceIdentity(user.id)
                val queue = api.fetchMessageQueue(token, identity.deviceId)
                if (queue.envelopes.isNotEmpty()) {
                    logSync("queue fetched count=${queue.envelopes.size} device=${identity.deviceId}")
                    runCatching { syncChatsWithToken(token) }
                        .onFailure { logSync("syncChats before queue decrypt failed: ${it.message}") }
                }
                val ackIds = mutableListOf<String>()

                for (envelope in queue.envelopes) {
                    val rawCiphertext = envelope.ciphertext.decodeBase64()
                    val extracted = extractPairwiseId(rawCiphertext)
                    if (extracted == null) {
                        // Do not ACK data this client could not understand: queue is the only
                        // recoverable copy until a fixed/newer client can process it.
                        logQueuedEnvelopeKept(envelope, "missing pairwise prefix")
                        continue
                    }
                    val (pairwiseId, signalCiphertext) = extracted
                    val decryptedEnvelope = decryptQueuedDmEnvelope(
                        token = token,
                        userId = user.id,
                        pairwiseId = pairwiseId,
                        envelope = envelope,
                        signalCiphertext = signalCiphertext,
                    )
                    val partnerId = decryptedEnvelope?.first
                    val plaintext = decryptedEnvelope?.second
                    if (plaintext == null) {
                        if (AppRuntimeInfo.devShortcutsEnabled) {
                            // Dev/local installs are routinely wiped while the local server keeps
                            // old per-device envelopes. Those stale PREKEY messages cannot be
                            // recovered by the new Signal store and otherwise starve the queue.
                            logQueuedEnvelopeDropped(
                                envelope,
                                "undecryptable in dev build",
                                "type=${envelope.envelope_type} pairwise=$pairwiseId",
                            )
                            ackIds.add(envelope.envelope_id)
                            continue
                        }
                        // Keep the envelope server-side. A later key restore/client fix can still
                        // recover it, while ACK would destroy the only queued ciphertext copy.
                        logQueuedEnvelopeKept(
                            envelope,
                            "undecryptable",
                            "type=${envelope.envelope_type} pairwise=$pairwiseId",
                        )
                        continue
                    }

                    val sealed = decodeSealedDmPayload(plaintext)
                    if (sealed == null) {
                        logQueuedEnvelopeKept(envelope, "sealed decode failed")
                        continue
                    }
                    if (sealed.kind == PAYLOAD_KIND_GROUP_SKDM) {
                        val groupChatId = sealed.group_chat_id
                        val skdm = sealed.group_skdm?.decodeBase64()
                        if (groupChatId == null || skdm == null) {
                            logSync("queue SKDM incomplete — acking id=${envelope.envelope_id}")
                            ackIds.add(envelope.envelope_id)
                            continue
                        }
                        crypto.processSenderKeyDistribution(
                            accountId = user.id,
                            senderAccountId = sealed.sender_account_id,
                            senderDeviceId = sealed.sender_device_id,
                            chatId = groupChatId,
                            distributionBytes = skdm,
                        )
                        ackIds.add(envelope.envelope_id)
                        continue
                    }
                    if (sealed.kind == PAYLOAD_KIND_DM_READ_RECEIPT) {
                        applyReadReceipt(sealed)
                        ackIds.add(envelope.envelope_id)
                        continue
                    }
                    if (sealed.kind == PAYLOAD_KIND_DM_DELETE) {
                        if (chatId != null && sealed.chat_id != chatId) continue
                        applyDmDeleteMarker(sealed)
                        ackIds.add(envelope.envelope_id)
                        continue
                    }
                    pairwiseIds.getOrCreate(user.id, sealed.sender_account_id)
                    // Filtered open-chat poll: leave unacked so a full drain still picks it up.
                    if (chatId != null && sealed.chat_id != chatId) continue
                    if (local.findChat(sealed.chat_id) == null) {
                        runCatching { syncChats() }
                            .onFailure { logSync("syncChats before incoming save failed: ${it.message}") }
                    }
                    if (local.findChat(sealed.chat_id) == null) {
                        // After sync still missing. Keep it queued instead of deleting a message
                        // whose chat metadata may arrive after server/client repair.
                        logQueuedEnvelopeKept(envelope, "chat missing after sync", "chat=${sealed.chat_id}")
                        continue
                    }

                    val messageId = incomingRelayMessageId(sealed.client_message_id, envelope.envelope_id)
                    val createdAt = sealed.created_at?.takeIf { it.isNotBlank() } ?: envelope.created_at ?: nowIso()
                    val body = resolveIncomingBody(token, sealed, messageId)
                    val message = Message(
                        id = messageId,
                        chat_id = sealed.chat_id,
                        sender_id = sealed.sender_account_id,
                        body = body,
                        pending_id = sealed.client_message_id,
                        created_at = createdAt,
                        reply_to_message_id = sealed.reply_to_message_id,
                        reply_preview_sender_id = sealed.reply_preview_sender_id,
                        reply_preview_body = sealed.reply_preview_body,
                        thread_root_id = sealed.thread_root_id,
                        thread_parent_id = sealed.thread_parent_id,
                        visibility = sealed.visibility ?: MESSAGE_VISIBILITY_MAIN,
                        expires_at = messageExpiresAt(createdAt, sealed.expires_at_sec),
                        envelope_type = envelope.envelope_type,
                        ciphertext = envelope.ciphertext,
                        sender_device_id = sealed.sender_device_id,
                    )
                    val isNewIncoming = applyIncomingMessage(message, alreadyDecrypted = true)
                    ackIds.add(envelope.envelope_id)
                    if (isNewIncoming && sealed.chat_id != activeChatId) {
                        appliedMessages++
                    }
                    logSync("queue envelope applied id=${envelope.envelope_id} chat=${sealed.chat_id} partner=$partnerId")
                    // Note: do not re-fanout received DMs to own other devices here — the sender
                    // already encrypts for every active recipient device. Re-fanout would loop and
                    // duplicate. Own *sent* messages self-sync in buildDmRelayEnvelopes(includeOwn…).
                    runCatching { uploadSecureHistoryIfEnabled() }
                }

                if (ackIds.isNotEmpty()) {
                    api.ackMessageQueue(token, identity.deviceId, AckMessageQueueRequest(ackIds))
                    logSync("queue ack count=${ackIds.size} device=${identity.deviceId}")
                }
            }
        } catch (e: ApiException) {
            if (e.status == HttpStatusCode.TooManyRequests) {
                lastQueuePollMs = now + QUEUE_RATE_LIMIT_BACKOFF_MS
                logSync("queue poll rate limited — backoff ${QUEUE_RATE_LIMIT_BACKOFF_MS}ms")
                return@withLock 0
            }
            if (isAuthFailure(e)) {
                handleAuthFailure(e)
                return@withLock 0
            }
            throw e
        } catch (e: SessionExpiredException) {
            handleAuthFailure(e)
            return@withLock 0
        }
        appliedMessages
    }

    private fun logQueuedEnvelopeKept(envelope: QueuedEnvelope, reason: String, details: String = "") {
        val suffix = details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        logSync("queue envelope $reason — keeping queued id=${envelope.envelope_id}$suffix")
    }

    private fun logQueuedEnvelopeDropped(envelope: QueuedEnvelope, reason: String, details: String = "") {
        val suffix = details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        logSync("queue envelope $reason — ack/drop id=${envelope.envelope_id}$suffix")
    }

    // --- Realtime ---

    fun startWebSocket(scope: CoroutineScope) {
        stopWebSocket()
        calls.startIncomingWatch()
        webSocketJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val token = runCatching { auth.activeToken() }.getOrNull()
                if (token == null) {
                    _webSocketConnectionState.value = RealtimeConnectionState.Hidden
                    attempt = 0
                    delay(1_000)
                    continue
                }

                _webSocketConnectionState.value = if (attempt == 0) {
                    RealtimeConnectionState.Connecting
                } else {
                    RealtimeConnectionState.Reconnecting
                }
                try {
                    // Prekeys must not block the first realtime connect.
                    if (attempt == 0) {
                        runCatching { runPrekeyWatchdog() }
                    }
                    api.wsHttp.webSocket(
                        urlString = api.webSocketUrl(token),
                        request = {
                            timeout {
                                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                connectTimeoutMillis = WebSocketTransport.CONNECT_TIMEOUT_MS
                            }
                        },
                    ) {
                        attempt = 0
                        _webSocketConnectionState.value = RealtimeConnectionState.Connected
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleWsFrame(frame.readText())
                            }
                        }
                    }
                    // Clean close (proxy idle, server restart).
                    attempt++
                    _webSocketConnectionState.value = RealtimeConnectionState.Reconnecting
                    delay(wsReconnectDelayMs(attempt))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    attempt++
                    _webSocketConnectionState.value = RealtimeConnectionState.Reconnecting
                    val msg = e.message.orEmpty()
                    logSync("ws reconnect attempt=$attempt error=$msg")
                    // 401: access token expired/rejected — one refresh then reconnect (MM pattern).
                    if (isWebSocketUnauthorized(msg, e)) {
                        runCatching { auth.forceRefreshAccessToken() }
                            .onFailure { err ->
                                logSync("ws auth refresh failed: ${err.message}")
                            }
                    }
                    delay(wsReconnectDelayMs(attempt))
                }
            }
        }
    }

    fun stopWebSocket() {
        webSocketJob?.cancel()
        webSocketJob = null
        calls.stopIncomingWatch()
        _webSocketConnectionState.value = RealtimeConnectionState.Hidden
    }

    // --- Internals ---

    /**
     * @param enrollDeviceBeforePublish Signal registration order: enroll device with the
     * login/register access token *before* [SessionStore.setSession], so Compose effects
     * (bootstrap, recovery, outbox) never observe a published token without a server device.
     */
    private suspend fun persistSession(
        token: String,
        user: User,
        refreshToken: String? = null,
        expiresInSeconds: Int? = null,
        enrollDeviceBeforePublish: Boolean = false,
    ) {
        clearPreviousAccountIfNeeded(user)
        local.switchAccount(user.id)
        val resolvedRefreshToken = refreshToken
            ?: SessionStore.refreshToken.value
            ?: secureSession.load()?.refreshToken
        val expiresAt = expiresInSeconds
            ?.takeIf { it > 0 }
            ?.let { currentTimeMillis() + it * 1_000L }
            ?: jwtExpiresAtEpochMs(token)
            ?: 0L
        val serverBinding = currentServerBinding()

        crypto.ensureDeviceIdentity(user.id)

        if (enrollDeviceBeforePublish) {
            // Do NOT setUserOnly here: UI recovery loop treats (user, no token) as
            // "recover session" and may mark reauth with *old* disk credentials while
            // enroll is still running — wiping the just-completed login (phone field bug).
            try {
                ensureDeviceRegistered(token, accountId = user.id)
            } catch (e: DeviceRegistrationException) {
                // Never published a full session — leave reauth/token clean for retry.
                throw e
            }
        }

        logSync(
            "session persisted expiresIn=${expiresInSeconds ?: 0} expiresAt=$expiresAt " +
                "refresh=${!resolvedRefreshToken.isNullOrBlank()} enrollFirst=$enrollDeviceBeforePublish",
        )
        SessionStore.clearReauthRequired()
        SessionStore.setSession(token, user, resolvedRefreshToken)
        secureSession.save(
            StoredCredentials(
                accessToken = token,
                refreshToken = resolvedRefreshToken,
                expiresAtEpochMs = expiresAt,
                userId = user.id,
                username = user.username,
                apiBaseUrl = serverBinding.apiBaseUrl,
                serverId = serverBinding.serverId,
                serverConfirmedAtEpochMs = serverBinding.confirmedAtEpochMs,
            ),
        )

        if (!enrollDeviceBeforePublish) {
            try {
                ensureDeviceRegistered(token, accountId = user.id)
            } catch (e: DeviceRegistrationException) {
                suspendAccessToken()
                throw e
            }
        }

        registerPushTokenIfNeeded(token)
        rotateMailboxIfNeeded(token)
        local.saveCachedUser(user)
    }

    private suspend fun clearPreviousAccountIfNeeded(nextUser: User) {
        val previousAccountIds = listOfNotNull(
            SessionStore.user.value?.id,
            local.loadCachedUser()?.id,
        )
            .distinct()
            .filter { it != nextUser.id }
        if (previousAccountIds.isEmpty()) return
        clearLocalAccountState(previousAccountIds, wipeCrypto = false)
    }

    private suspend fun clearLocalAccountState(
        accountIds: List<String>,
        wipeCrypto: Boolean = false,
    ) {
        stopWebSocket()
        stopPrekeyWatchdog()
        val ids = accountIds.distinct().filter { it.isNotBlank() }
        if (wipeCrypto) {
            ids.forEach { accountId ->
                runCatching { crypto.clearDeviceIdentity(accountId) }
                runCatching { secureSession.clearUser(accountId) }
            }
            // Intentionally do NOT clear chats/messages here.
            // History survives logout, reauth, multi-device login, and crypto rotate.
            // Only [eraseLocalHistory] with user confirmation may wipe local history.
        }
        secureSession.setActiveUser(null)
        clearTransientLocalState()
        local.switchAccount(null)
    }

    private suspend fun clearTransientLocalState() {
        SessionStore.clear()
        clearMemoryCaches()
    }

    private fun clearMemoryCaches() {
        hasMoreByChat.clear()
        knownUsernames.clear()
        knownUsers.clear()
        hydratedPublicProfileIds.clear()
        _knownUserProfiles.value = emptyMap()
        dmPartnerByChatId.clear()
        chatMemberIds.clear()
        _unreadCounts.value = emptyMap()
        activeChatId = null
    }

    private fun StoredCredentials.cachedUser(): User? {
        val id = userId?.takeIf { it.isNotBlank() } ?: return null
        val name = username?.takeIf { it.isNotBlank() } ?: id
        return User(id = id, username = name)
    }

    private fun StoredCredentials.isTrustedForCurrentServer(): Boolean =
        apiBaseUrl?.trim()?.trimEnd('/') == api.serverBaseUrl && !serverId.isNullOrBlank()

    private fun StoredCredentials.hasAccessToken(): Boolean =
        accessToken.isNotBlank()

    private suspend fun resolveStoredCredentials(): StoredCredentials? {
        secureSession.load()?.let { return it }
        val legacy = local.loadLegacySession() ?: return null
        val migrated = StoredCredentials(
            accessToken = legacy.token,
            refreshToken = null,
            expiresAtEpochMs = 0L,
            userId = legacy.user.id,
            username = legacy.user.username,
            apiBaseUrl = null,
            serverId = null,
            serverConfirmedAtEpochMs = 0L,
        )
        secureSession.save(migrated)
        return migrated
    }

    private data class ServerBinding(
        val apiBaseUrl: String,
        val serverId: String,
        val confirmedAtEpochMs: Long,
    )

    private suspend fun currentServerBinding(): ServerBinding {
        val hello = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(RESTORE_SESSION_REFRESH_TIMEOUT_MS) {
                api.hello()
            }
        }
        val serverId = hello.server_id.trim()
        if (serverId.isBlank()) {
            throw IllegalStateException("server identity missing")
        }
        return ServerBinding(
            apiBaseUrl = api.serverBaseUrl,
            serverId = serverId,
            confirmedAtEpochMs = currentTimeMillis(),
        )
    }

    private suspend fun verifyStoredServerBinding(credentials: StoredCredentials): Boolean {
        val expectedServerId = credentials.serverId?.trim().orEmpty()
        if (expectedServerId.isBlank()) return false
        val current = runCatching { currentServerBinding() }
            .onFailure { logSync("session: server identity check transient error: ${it.message}") }
            .getOrNull()
            ?: return true
        if (current.serverId != expectedServerId) {
            clearServerBoundSession(credentials)
            return false
        }
        if (credentials.serverConfirmedAtEpochMs != current.confirmedAtEpochMs) {
            secureSession.save(
                credentials.copy(
                    apiBaseUrl = current.apiBaseUrl,
                    serverId = current.serverId,
                    serverConfirmedAtEpochMs = current.confirmedAtEpochMs,
                ),
            )
        }
        return true
    }

    private suspend fun clearServerBoundSession(credentials: StoredCredentials) {
        credentials.userId?.let { secureSession.clearUser(it) } ?: secureSession.clear()
        secureSession.clearShell()
        SessionStore.clear()
        clearMemoryCaches()
    }

    private suspend fun refreshAccessToken(user: User, force: Boolean = false): String =
        sessionRefreshMutex.withLock {
            // Another waiter may have refreshed while we waited for the lock.
            val already = SessionStore.token.value
            val stored = secureSession.load()
            if (
                !force &&
                already != null &&
                stored?.requiresRefresh() != true &&
                stored?.isExpired() != true
            ) {
                return@withLock already
            }
            val refreshToken = SessionStore.refreshToken.value
                ?: stored?.refreshToken
                ?: throw SessionExpiredException()
            val identity = crypto.ensureDeviceIdentity(user.id)
            try {
                val response = api.refresh(
                    RefreshRequest(refresh_token = refreshToken, device_id = identity.deviceId),
                )
                // Tokens only — device already enrolled; do not re-enter enroll-before-publish.
                persistSession(
                    token = response.token,
                    user = response.user,
                    refreshToken = response.refresh_token,
                    expiresInSeconds = response.expires_in,
                    enrollDeviceBeforePublish = false,
                )
                return@withLock response.token
            } catch (e: ApiException) {
                if (e.status == HttpStatusCode.Unauthorized) {
                    // Signal-style: a concurrent login may have rotated refresh already.
                    // Never kill the newer session because a stale refresh lost the race.
                    val currentRefresh = SessionStore.refreshToken.value
                        ?: secureSession.load()?.refreshToken
                    if (currentRefresh != null && currentRefresh != refreshToken) {
                        logSync("session: ignoring stale refresh rejection (session superseded)")
                        return@withLock SessionStore.token.value
                            ?: throw SessionExpiredException()
                    }
                    throw SessionExpiredException()
                }
                throw e
            }
        }

    private fun jwtExpiresAtEpochMs(token: String): Long? =
        runCatching {
            val payload = token.split('.').getOrNull(1) ?: return null
            val normalized = payload
                .replace('-', '+')
                .replace('_', '/')
                .let { value ->
                    val padding = (4 - value.length % 4) % 4
                    value + "=".repeat(padding)
                }
            val json = normalized.decodeBase64().decodeToString()
            val seconds = Json.parseToJsonElement(json)
                .jsonObject["exp"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: return null
            seconds * 1_000L
        }.getOrNull()

    private suspend fun suspendAccessToken() {
        stopWebSocket()
        stopPrekeyWatchdog()
        _webSocketConnectionState.value = RealtimeConnectionState.Hidden
        SessionStore.clearAccessToken()
        val stored = secureSession.load() ?: return
        secureSession.save(
            stored.copy(
                accessToken = "",
                expiresAtEpochMs = 0L,
            ),
        )
    }

    private suspend fun handleAuthFailure(throwable: Throwable) {
        if (!isAuthFailure(throwable)) return
        if (!tryRecoverSession(forceRefresh = true)) {
            if (!SessionStore.reauthRequired.value && shouldRequireReauthAfterAuthFailure(throwable)) {
                markSessionReauthRequired()
            } else if (!SessionStore.reauthRequired.value && shouldSuspendAccessAfterAuthFailure(throwable)) {
                suspendAccessToken()
            }
        }
    }

    private fun shouldRequireReauthAfterAuthFailure(throwable: Throwable): Boolean =
        throwable is NotAuthenticatedException || throwable is SessionExpiredException

    private fun shouldSuspendAccessAfterAuthFailure(throwable: Throwable): Boolean =
        throwable is ApiException && throwable.status == HttpStatusCode.Unauthorized

    suspend fun recoverAuthFailure(throwable: Throwable): Boolean {
        if (!isAuthFailure(throwable)) return false
        handleAuthFailure(throwable)
        return true
    }

    private fun isAuthFailure(throwable: Throwable?): Boolean =
        when (throwable) {
            is SessionExpiredException -> true
            is NotAuthenticatedException -> true
            is ApiException -> throwable.status == HttpStatusCode.Unauthorized
            else -> throwable?.isAuthRefreshFailure() == true
        }

    suspend fun registerPushTokenIfNeeded(authToken: String? = null) {
        val user = SessionStore.user.value ?: return
        val identity = crypto.ensureDeviceIdentity(user.id)
        val pushToken = pushTokenProvider.currentToken()
        if (pushToken.isNullOrBlank()) {
            // Common when google-services.json is a stub / FCM project not configured.
            logSync("push: no FCM token yet (Firebase misconfigured or GMS unavailable) — skips register")
            return
        }
        val token = authToken ?: auth.activeToken()
        registerPushToken(token, identity.deviceId, pushToken)
    }

    suspend fun registerPushToken(
        authToken: String? = null,
        deviceId: String? = null,
        pushToken: String? = null,
    ) {
        val user = SessionStore.user.value ?: return
        val identity = crypto.ensureDeviceIdentity(user.id)
        val resolvedDeviceId = deviceId ?: identity.deviceId
        val resolvedPushToken = pushToken ?: pushTokenProvider.currentToken()
        if (resolvedPushToken.isNullOrBlank()) {
            logSync("push: register skipped — empty token platform=${pushTokenProvider.platform}")
            return
        }
        val token = authToken ?: auth.activeToken()
        runCatching {
            api.registerPushToken(
                token = token,
                deviceId = resolvedDeviceId,
                request = RegisterPushTokenRequest(
                    platform = pushTokenProvider.platform,
                    token = resolvedPushToken,
                    device_id = resolvedDeviceId,
                ),
            )
        }.onSuccess {
            logSync("push: registered platform=${pushTokenProvider.platform} device=$resolvedDeviceId")
        }.onFailure {
            logSync("push: register failed device=$resolvedDeviceId error=${it.message}")
        }
    }

    private suspend fun rotateMailboxIfNeeded(token: String) {
        val user = SessionStore.user.value ?: return
        val identity = crypto.ensureDeviceIdentity(user.id)
        val lastRotated = local.loadSetting(mailboxRotatedKey(user.id, identity.deviceId))
        if (lastRotated.isNotBlank()) return
        runCatching {
            api.rotateMailbox(token, identity.deviceId)
            local.saveSetting(mailboxRotatedKey(user.id, identity.deviceId), nowIso())
            logSync("mailbox rotated device=${identity.deviceId}")
        }.onFailure {
            logSync("mailbox rotate failed device=${identity.deviceId} error=${it.message}")
        }
    }

    private fun mailboxRotatedKey(accountId: String, deviceId: String) = "mailbox_rotated:$accountId:$deviceId"

    private suspend fun ensureDeviceRegistered(
        token: String,
        failIfUnavailable: Boolean = true,
        accountId: String? = null,
    ) {
        val resolvedAccountId = accountId
            ?: SessionStore.user.value?.id
            ?: return
        logDeviceRegistration(
            "registration: device enroll start account=$resolvedAccountId failIfUnavailable=$failIfUnavailable",
        )
        // If this install's device was revoked on the server (common after reinstall /
        // multi-device reconcile), mint a new local identity instead of hammering POST.
        rotateLocalDeviceIfRevokedOnServer(token, resolvedAccountId)

        var identity = crypto.ensureDeviceIdentity(resolvedAccountId)
        logDeviceRegistration("registration: device identity ready device=${identity.deviceId}")
        var registration = crypto.buildDeviceRegistration(resolvedAccountId)
        if (registration == null) {
            val message = "Ключи устройства не готовы"
            logDeviceRegistration("skipped — key bundle not ready for $resolvedAccountId")
            if (failIfUnavailable) {
                throw DeviceRegistrationException(message, deviceId = identity.deviceId)
            }
            return
        }
        logDeviceRegistration(
            "registration: device key bundle ready device=${identity.deviceId} " +
                "one_time_prekeys=${registration.one_time_prekeys.size} attestation=${registration.attestation != null}",
        )

        var lastError: Throwable? = null
        var rotatedAfterStuck = false
        repeat(DEVICE_REGISTER_MAX_ATTEMPTS) { attempt ->
            logDeviceRegistration(
                "registration: POST /api/devices attempt=${attempt + 1}/$DEVICE_REGISTER_MAX_ATTEMPTS " +
                    "device=${identity.deviceId}",
            )
            val result = runCatching {
                api.registerDevice(token, identity.deviceId, registration!!)
            }
            if (result.isSuccess) {
                logDeviceRegistration("ok device=${identity.deviceId} account=$resolvedAccountId")
                // Confirm / self-promote BEFORE revoking other actives, otherwise we can
                // leave the account with only pending devices (no confirmer left).
                ensureDeviceActiveAfterRegistration(
                    token, resolvedAccountId, identity.deviceId, registration!!,
                )
                val statusAfter = deviceStatusOnServer(token, resolvedAccountId, identity.deviceId)
                logDeviceRegistration("registration: device status after register device=${identity.deviceId} status=$statusAfter")
                when (statusAfter) {
                    "active", null -> {
                        // null = list failed but register succeeded; proceed best-effort.
                        reconcileOwnDevicesAfterRegistration(
                            token, resolvedAccountId, identity.deviceId,
                        )
                        replenishPrekeysIfNeeded(token, identity.deviceId, resolvedAccountId)
                        return
                    }
                    "pending" -> {
                        // Second promote pass (confirm race / list lag), then continue even if
                        // list failed. A confirmed pending state is not a usable receiving device:
                        // peers only fan out to active devices.
                        ensureDeviceActiveAfterRegistration(
                            token, resolvedAccountId, identity.deviceId, registration!!,
                        )
                        val statusAfterPromote = deviceStatusOnServer(token, resolvedAccountId, identity.deviceId)
                        logDeviceRegistration(
                            "registration: device status after promote device=${identity.deviceId} status=$statusAfterPromote",
                        )
                        when (statusAfterPromote) {
                            "active", null -> {
                                reconcileOwnDevicesAfterRegistration(
                                    token, resolvedAccountId, identity.deviceId,
                                )
                                replenishPrekeysIfNeeded(token, identity.deviceId, resolvedAccountId)
                                return
                            }
                            "pending" -> {
                                val pendingError = DeviceRegistrationException(
                                    "Устройство ожидает подтверждения на другом устройстве",
                                    deviceId = identity.deviceId,
                                )
                                lastError = pendingError
                                if (failIfUnavailable) throw pendingError
                                return
                            }
                            else -> {
                                reconcileOwnDevicesAfterRegistration(
                                    token, resolvedAccountId, identity.deviceId,
                                )
                                replenishPrekeysIfNeeded(token, identity.deviceId, resolvedAccountId)
                                return
                            }
                        }
                    }
                    "revoked" -> {
                        // Server accepted POST for a revoked row without un-revoking when another
                        // device is already active — re-enroll with a fresh local identity.
                        // Always revoke the old device_id first so it cannot stay a zombie fan-out target.
                        if (!rotatedAfterStuck && attempt < DEVICE_REGISTER_MAX_ATTEMPTS - 1) {
                            logDeviceRegistration(
                                "device still revoked after register — revoking + clearing identity for re-enroll",
                            )
                            rotateLocalDeviceIdentity(token, resolvedAccountId, identity.deviceId)
                            identity = crypto.ensureDeviceIdentity(resolvedAccountId)
                            registration = crypto.buildDeviceRegistration(resolvedAccountId)
                            rotatedAfterStuck = true
                            if (registration == null) return@repeat
                            delay(DEVICE_REGISTER_RETRY_MS * (attempt + 1))
                            return@repeat
                        }
                        lastError = DeviceRegistrationException(
                            "device remains revoked on server",
                            deviceId = identity.deviceId,
                        )
                    }
                    else -> {
                        reconcileOwnDevicesAfterRegistration(
                            token, resolvedAccountId, identity.deviceId,
                        )
                        replenishPrekeysIfNeeded(token, identity.deviceId, resolvedAccountId)
                        return
                    }
                }
            } else {
                lastError = result.exceptionOrNull()
            }
            val detail = (lastError as? ApiException)?.errorMessage
                ?: lastError?.message
            logDeviceRegistration(
                "POST /api/devices failed (attempt ${attempt + 1}/$DEVICE_REGISTER_MAX_ATTEMPTS, " +
                    "device=${identity.deviceId}): $detail",
            )
            // Forbidden / revoked-style failures: rotate once and retry with new device id.
            val apiErr = lastError as? ApiException
            if (apiErr != null &&
                (apiErr.status == HttpStatusCode.Forbidden ||
                    detail?.contains("revok", ignoreCase = true) == true) &&
                attempt == 0
            ) {
                logDeviceRegistration("rotating local identity after register rejection")
                rotateLocalDeviceIdentity(token, resolvedAccountId, identity.deviceId)
                identity = crypto.ensureDeviceIdentity(resolvedAccountId)
                registration = crypto.buildDeviceRegistration(resolvedAccountId)
                if (registration == null) return@repeat
            }
            if (attempt < DEVICE_REGISTER_MAX_ATTEMPTS - 1) {
                delay(DEVICE_REGISTER_RETRY_MS * (attempt + 1))
            }
        }

        val detail = (lastError as? ApiException)?.errorMessage ?: lastError?.message
        val message = detail?.takeIf { it.isNotBlank() }
            ?: "Не удалось зарегистрировать устройство на сервере"
        logDeviceRegistration(
            "gave up for ${identity.deviceId} — account may stay inactive on server until retry succeeds",
        )
        if (failIfUnavailable) {
            throw DeviceRegistrationException(message, deviceId = identity.deviceId, cause = lastError)
        }
    }

    /**
     * Phone reinstall / multi-device reconcile can leave a revoked server device id in local crypto.
     * Drop local keys (after best-effort server revoke) before re-registering.
     */
    private suspend fun rotateLocalDeviceIfRevokedOnServer(token: String, accountId: String) {
        val identity = runCatching { crypto.ensureDeviceIdentity(accountId) }.getOrNull() ?: return
        val devices = runCatching { api.listUserDevices(token, accountId) }.getOrNull() ?: return
        val own = devices.firstOrNull { it.device_id == identity.deviceId } ?: return
        if ((own.device_status ?: "active") != "revoked") return
        logDeviceRegistration(
            "server marks device=${identity.deviceId} revoked — revoking leftovers + clearing local identity",
        )
        rotateLocalDeviceIdentity(token, accountId, identity.deviceId)
    }

    /** Revoke current server device (best effort) then wipe local crypto identity. */
    private suspend fun rotateLocalDeviceIdentity(token: String, accountId: String, deviceId: String) {
        runCatching { api.revokeDevice(token, deviceId) }
            .onSuccess { logDeviceRegistration("revoked old device=$deviceId before local rotate") }
            .onFailure {
                logDeviceRegistration("revoke old device=$deviceId failed (continue rotate): ${it.message}")
            }
        crypto.clearDeviceIdentity(accountId)
    }

    private suspend fun deviceStatusOnServer(
        token: String,
        accountId: String,
        deviceId: String,
    ): String? {
        val devices = runCatching { api.listUserDevices(token, accountId) }.getOrNull() ?: return null
        return devices.firstOrNull { it.device_id == deviceId }?.device_status
    }

    private fun logDeviceRegistration(message: String) {
        AppLog.debug(message)
    }

    /**
     * Make this install active before touching other devices.
     * - pending + active confirmer → confirm
     * - pending + no active left → re-register (server promotes when activeCount == 0)
     */
    private suspend fun ensureDeviceActiveAfterRegistration(
        token: String,
        accountId: String,
        deviceId: String,
        registration: RegisterDeviceRequest,
    ) {
        val ownDevices = runCatching { api.listUserDevices(token, accountId) }
            .onFailure {
                logDeviceRegistration("device status check failed device=$deviceId error=${it.message}")
            }
            .getOrNull()
            ?: return
        val current = ownDevices.firstOrNull { it.device_id == deviceId }
        if (current == null) {
            logDeviceRegistration("device status missing device=$deviceId")
            return
        }
        val status = current.device_status ?: "active"
        if (status == "active") return
        if (status != "pending") {
            logDeviceRegistration("device status=$status device=$deviceId")
            return
        }

        val confirmingDevice = ownDevices.firstOrNull { device ->
            device.device_id != deviceId && (device.device_status ?: "active") == "active"
        }
        if (confirmingDevice != null) {
            runCatching {
                api.confirmDevice(token, deviceId, confirmingDevice.device_id)
            }.onSuccess {
                logDeviceRegistration("device confirmed device=$deviceId by=${confirmingDevice.device_id}")
                return
            }.onFailure {
                logDeviceRegistration("device confirm failed device=$deviceId error=${it.message}")
            }
        }

        // No active confirmer: re-register this device so the server can promote it
        // when activeCount is already 0 (or after other actives were lost).
        runCatching {
            api.registerDevice(token, deviceId, registration)
        }.onSuccess {
            val refreshed = runCatching { api.listUserDevices(token, accountId) }.getOrNull()
            val promoted = refreshed?.firstOrNull { it.device_id == deviceId }?.device_status ?: "unknown"
            logDeviceRegistration("device re-registered for promote device=$deviceId status=$promoted")
        }.onFailure {
            logDeviceRegistration("device promote re-register failed device=$deviceId error=${it.message}")
        }
    }

    /**
     * After this install is active:
     * - revoke leftover *pending* siblings (failed linking / reinstall half-states)
     * - keep a small number of other *active* devices (real multi-device phones)
     * - if the account accumulated reinstall ghosts beyond [MAX_LINKED_ACTIVE_DEVICES],
     *   revoke the excess actives (not this install). Server last_seen purge is the
     *   long-term safety net; this cap stops same-day reinstall piles from poisoning fan-out.
     */
    private suspend fun reconcileOwnDevicesAfterRegistration(
        token: String,
        accountId: String,
        deviceId: String,
    ) {
        val ownDevices = runCatching { api.listUserDevices(token, accountId) }
            .onFailure {
                logDeviceRegistration("reconcile devices failed account=$accountId error=${it.message}")
            }
            .getOrNull()
            ?: return

        val currentStatus = ownDevices.firstOrNull { it.device_id == deviceId }?.device_status ?: "active"
        if (currentStatus != "active") {
            logDeviceRegistration(
                "reconcile skip — this device is $currentStatus (need active; leave other devices alone)",
            )
            return
        }

        var revokedPending = 0
        ownDevices.forEach { device ->
            if (device.device_id == deviceId) return@forEach
            if ((device.device_status ?: "active") != "pending") return@forEach
            runCatching { api.revokeDevice(token, device.device_id) }
                .onSuccess { revokedPending++ }
                .onFailure {
                    logDeviceRegistration(
                        "reconcile revoke pending failed device=${device.device_id}: ${it.message}",
                    )
                }
        }
        if (revokedPending > 0) {
            logDeviceRegistration("reconcile revoked $revokedPending pending sibling device(s)")
        }

        val otherActives = ownDevices.filter { device ->
            device.device_id != deviceId && (device.device_status ?: "active") == "active"
        }
        // Cap linked actives. Real multi-device (phone + tablet) fits under the cap;
        // reinstall zombies grow past it and must not stay fan-out targets.
        val excess = (otherActives.size + 1) - MAX_LINKED_ACTIVE_DEVICES
        if (excess > 0) {
            // Prefer revoking siblings that are not this install. Order is server list order
            // (created_at ASC) so oldest ghosts go first; keep the newest extras + this device.
            val toRevoke = otherActives.take(excess)
            var revokedActive = 0
            toRevoke.forEach { device ->
                runCatching { api.revokeDevice(token, device.device_id) }
                    .onSuccess { revokedActive++ }
                    .onFailure {
                        logDeviceRegistration(
                            "reconcile revoke excess active failed device=${device.device_id}: ${it.message}",
                        )
                    }
            }
            logDeviceRegistration(
                "anti-zombie: revoked $revokedActive excess active sibling(s) " +
                    "(cap=$MAX_LINKED_ACTIVE_DEVICES, had=${otherActives.size + 1}) keep=$deviceId",
            )
        } else if (otherActives.isNotEmpty()) {
            logDeviceRegistration(
                "multi-device: keep ${otherActives.size} other active device(s) linked keep=$deviceId",
            )
        }
    }

    private suspend fun replenishPrekeysIfNeeded(token: String, deviceId: String, accountId: String) {
        val remaining = runCatching { api.countRemainingPrekeys(token, deviceId).remaining_prekeys }.getOrDefault(0)
        if (remaining >= PREKEY_REPLENISH_THRESHOLD) return
        val prekeys = crypto.buildPrekeyReplenishment(accountId) ?: return
        runCatching {
            api.replenishPrekeys(token, deviceId, ReplenishPrekeysRequest(prekeys))
        }
    }

    private suspend fun checkPartnerIdentityKey(partnerUserId: String, deviceId: String, identityPublicKey: String) {
        val hash = sha256Hex(identityPublicKey.decodeBase64())
        val trusted = local.loadSetting(identityTrustKey(partnerUserId, deviceId))
        if (trusted.isBlank()) {
            local.saveSetting(identityTrustKey(partnerUserId, deviceId), hash)
            if (isPartnerUntrusted(partnerUserId)) {
                removeUntrustedPartner(partnerUserId)
            }
            return
        }
        if (trusted == hash) {
            if (isPartnerUntrusted(partnerUserId)) {
                removeUntrustedPartner(partnerUserId)
            }
            return
        }
        local.saveSetting(identityPendingKey(partnerUserId, deviceId), hash)
        addUntrustedPartner(partnerUserId)
        _identityKeyChanges.emit(partnerUserId)
    }

    private suspend fun loadUntrustedPartners() {
        val raw = local.loadSetting(KEY_UNTRUSTED_PARTNERS)
        _untrustedPartnerIds.value = decodePartnerIdSet(raw)
    }

    private suspend fun addUntrustedPartner(partnerUserId: String) {
        val next = _untrustedPartnerIds.value + partnerUserId
        _untrustedPartnerIds.value = next
        local.saveSetting(KEY_UNTRUSTED_PARTNERS, encodePartnerIdSet(next))
    }

    private suspend fun removeUntrustedPartner(partnerUserId: String) {
        val next = _untrustedPartnerIds.value - partnerUserId
        _untrustedPartnerIds.value = next
        local.saveSetting(KEY_UNTRUSTED_PARTNERS, encodePartnerIdSet(next))
    }

    private fun identityTrustKey(partnerUserId: String) = "identity_trust:$partnerUserId"

    private fun identityPendingKey(partnerUserId: String) = "identity_pending:$partnerUserId"

    private fun identityTrustKey(partnerUserId: String, deviceId: String) = "identity_trust:$partnerUserId:$deviceId"

    private fun identityPendingKey(partnerUserId: String, deviceId: String) = "identity_pending:$partnerUserId:$deviceId"

    private fun messageExpiresAt(createdAt: String?, ttlSec: Long?): String? {
        val ttl = ttlSec ?: return null
        if (ttl <= 0L) return null
        val baseMillis = createdAt?.let { parseIsoTimestampMillis(it) } ?: return null
        return formatIsoFromMillis(baseMillis + ttl * 1_000L)
    }

    private suspend fun persistCurrentUser(user: User, token: String): User {
        SessionStore.updateUser(user)
        rememberUsers(listOf(user))
        local.saveCachedUser(user)
        return user
    }

    private suspend fun handleWsFrame(payload: String) {
        val event = runCatching { api.json.decodeFromString(WsEvent.serializer(), payload) }.getOrNull() ?: return
        if (event.data == null) {
            return
        }

        when (event.event) {
            WsEventType.MESSAGE_NEW -> {
                val data = runCatching {
                    api.json.decodeFromJsonElement(MessageNewData.serializer(), event.data)
                }.getOrNull() ?: return
                applyIncomingMessage(data.message)
            }
            WsEventType.CHAT_UPDATED -> {
                val data = runCatching {
                    api.json.decodeFromJsonElement(ChatUpdatedData.serializer(), event.data)
                }.getOrNull() ?: return
                cacheChatMembers(listOf(data.chat))
                local.saveChat(data.chat)
                rememberDmPartner(data.chat)
            }
            WsEventType.MESSAGE_ENVELOPE -> {
                runCatching {
                    api.json.decodeFromJsonElement(EnvelopeNewData.serializer(), event.data)
                }.getOrNull() ?: return
                processMessageQueue(activeChatId)
            }
            WsEventType.PRESENCE_ONLINE -> {
                val data = runCatching {
                    api.json.decodeFromJsonElement(PresenceUserData.serializer(), event.data!!)
                }.getOrNull() ?: return
                presence.applyPartnerOnline(data.user_id)
            }
            WsEventType.PRESENCE_OFFLINE -> {
                val data = runCatching {
                    api.json.decodeFromJsonElement(PresenceUserData.serializer(), event.data!!)
                }.getOrNull() ?: return
                presence.applyPartnerOffline(data.user_id)
            }
            WsEventType.CALL_STARTED -> {
                val data = runCatching {
                    api.json.decodeFromJsonElement(CallPresenceData.serializer(), event.data!!)
                }.getOrNull() ?: return
                presence.applyPartnerInCall(data.user_id, data.call_id)
            }
            WsEventType.CALL_ENDED -> {
                // Presence payload has user_id; control-plane payload has call_id + status.
                val presenceData = runCatching {
                    api.json.decodeFromJsonElement(CallPresenceData.serializer(), event.data!!)
                }.getOrNull()
                if (presenceData != null && presenceData.user_id.isNotBlank()) {
                    presence.applyPartnerCallEnded(presenceData.user_id)
                }
                decodeCallControlEvent(event, logInvalid = false)?.let { control ->
                    calls.onControlEvent(WsEventType.CALL_ENDED, control)
                }
            }
            WsEventType.CALL_CREATED,
            WsEventType.CALL_RINGING,
            WsEventType.CALL_ACCEPTED,
            WsEventType.CALL_CONNECTED,
            WsEventType.CALL_REJECTED,
            WsEventType.CALL_PARTICIPANT_JOINED,
            WsEventType.CALL_PARTICIPANT_LEFT,
            WsEventType.CALL_LOW_BANDWIDTH,
            WsEventType.CALL_ROUTE_DEGRADED,
            -> {
                decodeCallControlEvent(event)?.let { control ->
                    calls.onControlEvent(event.event, control)
                }
            }
        }
    }

    private fun decodeCallControlEvent(
        event: WsEvent,
        logInvalid: Boolean = true,
    ): CallControlEventData? {
        val data = event.data ?: return null
        val control = runCatching {
            api.json.decodeFromJsonElement(CallControlEventData.serializer(), data)
        }.onFailure {
            if (logInvalid) {
                AppLog.warning("call: ws ${event.event} decode failed error=${it.message}")
            }
        }.getOrNull() ?: return null
        if (control.call_id.isBlank()) {
            if (logInvalid) {
                AppLog.warning("call: ws ${event.event} missing call_id")
            }
            return null
        }
        AppLog.debug("call: ws frame ${event.event} id=${control.call_id} status=${control.status}")
        return control
    }

    internal suspend fun applyIncomingMessage(message: Message, alreadyDecrypted: Boolean = false): Boolean {
        val resolvedFromEnvelope = if (alreadyDecrypted) message else decryptStoredMessage(message)
        val selfId = SessionStore.user.value?.id
        val existing = local.findMessageById(resolvedFromEnvelope.id)
            ?: resolvedFromEnvelope.pending_id?.let { local.findMessageByPendingId(it) }
        val isNewIncoming = existing == null && resolvedFromEnvelope.sender_id != selfId
        val resolved = preserveOwnOutgoingPlaintext(
            message = resolvedFromEnvelope,
            localFallback = existing,
            selfId = selfId,
        )
        val preservedOwnStatus = existing
            ?.deliveryStatus()
            ?.takeIf { resolved.sender_id == selfId && it != MessageStatus.SENDING && it != MessageStatus.FAILED }
        val status = preservedOwnStatus ?: resolved.status ?: MessageDeliverySemantics.AFTER_RELAY_ACCEPT_STATUS
        val stored = resolved.copy(status = status)
        if (existing != null && existing.id != stored.id) {
            persistentAttachmentCache.metadata.relinkMessage(
                fromMessageId = existing.id,
                toMessageId = stored.id,
                accessedAt = nowIso(),
            )
            local.deleteMessageById(existing.id)
        }
        stored.pending_id?.takeIf { it != stored.id }?.let { pendingId ->
            persistentAttachmentCache.metadata.relinkMessage(
                fromMessageId = pendingId,
                toMessageId = stored.id,
                accessedAt = nowIso(),
            )
        }
        stored.pending_id?.takeIf { it != stored.id }?.let { local.deleteMessageById(it) }
        local.saveMessage(stored, status = status)
        refreshChatPreview(stored)
        if (isNewIncoming && stored.chat_id != activeChatId) {
            incrementUnread(stored.chat_id)
        }
        _incomingMessages.emit(stored)
        return isNewIncoming
    }

    private suspend fun sendDmReadReceipt(chat: Chat, messageIds: List<String>) {
        runCatching {
            sendDmServicePayload(
                chat = chat,
                messageIds = messageIds,
                kind = PAYLOAD_KIND_DM_READ_RECEIPT,
                clientMessagePrefix = "read",
                includeOwnOtherDevices = false,
                resolvePartnerIfMissing = false,
                failWhenUntrusted = false,
            ) { base, ids ->
                base.copy(read_message_ids = ids)
            }
        }.onFailure { logSync("read receipt failed chat=${chat.id} error=${it.message}") }
    }

    private suspend fun applyReadReceipt(sealed: SealedDmPayload) {
        val selfId = SessionStore.user.value?.id ?: return
        for (messageId in sealed.read_message_ids) {
            val message = local.findMessageByPendingId(messageId)
                ?: local.findMessageById(messageId)
                ?: continue
            if (message.sender_id == selfId && message.chat_id == sealed.chat_id) {
                local.updateMessageStatus(message.id, MessageDeliverySemantics.AFTER_PEER_READ_RECEIPT_STATUS)
                message.pending_id
                    ?.takeIf { it.isNotBlank() }
                    ?.let { pendingId ->
                        local.updateMessageStatusByPendingId(
                            chatId = message.chat_id,
                            senderId = message.sender_id,
                            pendingId = pendingId,
                            status = MessageDeliverySemantics.AFTER_PEER_READ_RECEIPT_STATUS,
                        )
                    }
            }
        }
    }

    private suspend fun sendDmDeleteMarker(chat: Chat, messageIds: List<String>) {
        sendDmServicePayload(
            chat = chat,
            messageIds = messageIds,
            kind = PAYLOAD_KIND_DM_DELETE,
            clientMessagePrefix = "delete",
            includeOwnOtherDevices = true,
            resolvePartnerIfMissing = true,
            failWhenUntrusted = true,
        ) { base, ids ->
            base.copy(deleted_message_ids = ids)
        }
    }

    private suspend fun sendDmServicePayload(
        chat: Chat,
        messageIds: List<String>,
        kind: String,
        clientMessagePrefix: String,
        includeOwnOtherDevices: Boolean,
        resolvePartnerIfMissing: Boolean,
        failWhenUntrusted: Boolean,
        buildPayload: (SealedDmPayload, List<String>) -> SealedDmPayload,
    ) {
        val user = SessionStore.user.value ?: return
        val ids = normalizedMessageIds(messageIds)
        if (ids.isEmpty()) return
        val partnerId = dmPartnerByChatId[chat.id]
            ?: if (resolvePartnerIfMissing) resolveDmPartnerId(chat) else return
        if (isPartnerUntrusted(partnerId)) {
            if (failWhenUntrusted) {
                error("Сначала подтвердите новый ключ безопасности собеседника")
            }
            return
        }
        auth.withAuth { token ->
            val identity = crypto.ensureDeviceIdentity(user.id)
            ensureDmSession(chat.id, partnerId)
            val pairwiseId = pairwiseIds.getOrCreate(user.id, partnerId)
            val sealedPlaintext = encodeSealedDmPayload(
                buildPayload(
                    SealedDmPayload(
                        pairwise_id = pairwiseId,
                        sender_account_id = user.id,
                        sender_device_id = identity.deviceId,
                        chat_id = chat.id,
                        kind = kind,
                    ),
                    ids,
                ),
            )
            val envelopes = buildDmRelayEnvelopes(
                token = token,
                userId = user.id,
                partnerId = partnerId,
                pairwiseId = pairwiseId,
                sealedPlaintext = sealedPlaintext,
                includeOwnOtherDevices = includeOwnOtherDevices,
            )
            api.relayMessage(
                token = token,
                deviceId = identity.deviceId,
                request = RelayMessageRequest(
                    pairwise_id = pairwiseId,
                    client_message_id = dmServiceClientMessageId(clientMessagePrefix, chat.id, ids),
                    envelopes = envelopes,
                ),
            )
        }
    }

    private suspend fun applyDmDeleteMarker(sealed: SealedDmPayload) {
        val ids = normalizedMessageIds(sealed.deleted_message_ids)
        if (ids.isEmpty()) return
        for (messageId in ids) {
            val message = local.findMessageByPendingId(messageId) ?: local.findMessageById(messageId)
            if (message?.chat_id == sealed.chat_id) {
                local.deleteMessageById(message.id)
            }
        }
        refreshChatPreviewFromLocal(sealed.chat_id)
        runCatching { uploadSecureHistoryIfEnabled() }
        logSync("dm delete marker applied chat=${sealed.chat_id} count=${ids.size}")
    }

    private fun normalizedMessageIds(messageIds: List<String>): List<String> =
        messageIds.mapNotNull { it.takeIf(String::isNotBlank) }.distinct()

    private fun dmServiceClientMessageId(prefix: String, chatId: String, messageIds: List<String>): String =
        "$prefix-$chatId-${messageIds.sorted().joinToString("-")}"

    private suspend fun encryptGroupMessageWithPreparedSenderKey(
        accountId: String,
        deviceId: String,
        chatId: String,
        chat: Chat,
        plaintext: ByteArray,
    ): EncryptedPayload {
        crypto.encryptGroupMessage(
            accountId = accountId,
            deviceId = deviceId,
            chatId = chatId,
            plaintext = plaintext,
        )?.let { return it }

        logSync("group encryption missing sender key chat=$chatId; recreating distribution")
        ensureGroupSenderKeyDistribution(chat)

        return crypto.encryptGroupMessage(
            accountId = accountId,
            deviceId = deviceId,
            chatId = chatId,
            plaintext = plaintext,
        ) ?: error("Group encryption failed")
    }

    private suspend fun ensureGroupSenderKeyDistribution(chat: Chat): Boolean {
        val user = SessionStore.user.value ?: return false
        return auth.withAuth { token ->
            val identity = crypto.ensureDeviceIdentity(user.id)
            val skdm = crypto.createSenderKeyDistribution(user.id, identity.deviceId, chat.id)
                ?: return@withAuth false
            crypto.processSenderKeyDistribution(
                accountId = user.id,
                senderAccountId = user.id,
                senderDeviceId = identity.deviceId,
                chatId = chat.id,
                distributionBytes = skdm,
            )
            val skdmB64 = skdm.encodeBase64()

            val members = chat.member_ids
                .ifEmpty { chatMemberIds[chat.id].orEmpty() }
                .filter { it != user.id }
            if (members.isEmpty()) return@withAuth true

            for (memberId in members) {
                ensureMemberSessions(memberId)
                val devices = api.listUserDevices(token, memberId)
                val pairwiseId = pairwiseIds.getOrCreate(user.id, memberId)
                val sealedPlaintext = encodeSealedDmPayload(
                    SealedDmPayload(
                        pairwise_id = pairwiseId,
                        sender_account_id = user.id,
                        sender_device_id = identity.deviceId,
                        chat_id = chat.id,
                        kind = PAYLOAD_KIND_GROUP_SKDM,
                        group_skdm = skdmB64,
                        group_chat_id = chat.id,
                    ),
                )
                val envelopes = devices.mapNotNull { device ->
                    val payload = crypto.encryptMessage(user.id, memberId, device.device_id, sealedPlaintext)
                        ?: return@mapNotNull null
                    RelayEnvelopeRequest(
                        mailbox_token = device.mailbox_token,
                        delivery_token = device.delivery_token,
                        envelope_type = payload.envelopeType,
                        ciphertext = prependPairwiseId(pairwiseId, payload.ciphertext).encodeBase64(),
                    )
                }
                if (envelopes.isEmpty()) continue
                runCatching {
                    api.relayMessage(
                        token = token,
                        deviceId = identity.deviceId,
                        request = RelayMessageRequest(
                            pairwise_id = pairwiseId,
                            client_message_id = "skdm-${chat.id}-${memberId}",
                            envelopes = envelopes,
                        ),
                    )
                }.onFailure {
                    logSync("group sender key relay failed chat=${chat.id} member=$memberId error=${it.message}")
                }
            }
            true
        }
    }

    private suspend fun ensureMemberSessions(memberId: String) {
        val user = SessionStore.user.value ?: return
        auth.withAuth { token ->
            val devices = api.listUserDevices(token, memberId)
            for (device in devices) {
                checkPartnerIdentityKey(memberId, device.device_id, device.identity_public_key)
                val bundle = api.getDeviceBundle(token, device.device_id)
                crypto.ensureSession(user.id, memberId, device.device_id, bundle)
            }
        }
    }

    private suspend fun decryptStoredMessage(message: Message): Message {
        val ciphertextB64 = message.ciphertext?.takeIf { it.isNotBlank() } ?: return message
        val senderDeviceId = message.sender_device_id?.takeIf { it.isNotBlank() } ?: return message.copy(
            body = encryptedPlaceholder(),
        )
        val user = SessionStore.user.value ?: return message.copy(body = encryptedPlaceholder())
        val plaintext = crypto.decryptGroupMessage(
            accountId = user.id,
            senderAccountId = message.sender_id,
            senderDeviceId = senderDeviceId,
            chatId = message.chat_id,
            ciphertext = ciphertextB64.decodeBase64(),
        ) ?: return message.copy(body = encryptedPlaceholder())

        val sealed = decodeSealedGroupPayload(plaintext) ?: return message.copy(body = encryptedPlaceholder())
        val body = if (sealed.attachment != null) {
            auth.withAuth { token -> resolveIncomingGroupBody(token, sealed, message.id) }
        } else {
            sealed.body
        }
        return message.copy(
            body = body,
            reply_to_message_id = sealed.reply_to_message_id ?: message.reply_to_message_id,
            reply_preview_sender_id = sealed.reply_preview_sender_id ?: message.reply_preview_sender_id,
            reply_preview_body = sealed.reply_preview_body ?: message.reply_preview_body,
            thread_root_id = sealed.thread_root_id ?: message.thread_root_id,
            thread_parent_id = sealed.thread_parent_id ?: message.thread_parent_id,
            visibility = sealed.visibility ?: message.visibility,
            expires_at = message.expires_at ?: messageExpiresAt(message.created_at, sealed.expires_at_sec),
        )
    }

    private suspend fun decryptStoredMessagePreservingOwnPlaintext(message: Message): Message {
        val resolved = decryptStoredMessage(message)
        val selfId = SessionStore.user.value?.id
        if (selfId == null || resolved.sender_id != selfId || !resolved.body.isEncryptedPlaceholderOrBlank()) {
            return resolved
        }
        val existing = local.findMessageById(resolved.id)
            ?: resolved.pending_id?.let { local.findMessageByPendingId(it) }
        return preserveOwnOutgoingPlaintext(
            message = resolved,
            localFallback = existing,
            selfId = selfId,
        )
    }

    private fun preserveOwnOutgoingPlaintext(
        message: Message,
        localFallback: Message?,
        relation: MessageRelationDraft? = null,
        fallbackBody: String? = null,
        selfId: String? = SessionStore.user.value?.id,
    ): Message {
        if (selfId == null || message.sender_id != selfId) return message

        val localBody = localFallback?.body?.takeUnless { it.isEncryptedPlaceholderOrBlank() }
        val payloadBody = fallbackBody?.takeUnless { it.isEncryptedPlaceholderOrBlank() }
        val body = if (message.body.isEncryptedPlaceholderOrBlank()) {
            localBody ?: payloadBody ?: message.body
        } else {
            message.body
        }

        return message.copy(
            body = body,
            reply_to_message_id = message.reply_to_message_id
                ?: relation?.replyToMessageId
                ?: localFallback?.reply_to_message_id,
            reply_preview_sender_id = message.reply_preview_sender_id
                ?: relation?.replyPreviewSenderId
                ?: localFallback?.reply_preview_sender_id,
            reply_preview_body = message.reply_preview_body
                ?: relation?.replyPreviewBody
                ?: localFallback?.reply_preview_body,
            thread_root_id = message.thread_root_id
                ?: relation?.threadRootId
                ?: localFallback?.thread_root_id,
            thread_parent_id = message.thread_parent_id
                ?: relation?.threadParentId
                ?: localFallback?.thread_parent_id,
            visibility = message.visibility
                ?: relation?.visibility
                ?: localFallback?.visibility,
            expires_at = message.expires_at ?: localFallback?.expires_at,
        )
    }

    private fun String.isEncryptedPlaceholderOrBlank(): Boolean =
        isBlank() || this == encryptedPlaceholder()

    private suspend fun resolveIncomingGroupBody(
        token: String,
        sealed: SealedGroupPayload,
        messageId: String,
    ): String {
        val attachment = sealed.attachment ?: return sealed.body
        return runCatching {
            val encryptedBlob = loadOrDownloadAttachmentBlob(
                token = token,
                attachment = attachment,
                chatId = sealed.chat_id,
                messageId = messageId,
                ownerAccountId = SessionStore.user.value?.id ?: sealed.sender_account_id,
            )
            val fileKey = attachment.file_key.decodeBase64()
            val plaintext = withContext(Dispatchers.Default) {
                FileAttachmentCrypto.decrypt(fileKey, encryptedBlob)
            }
            val label = attachment.file_name?.takeIf { it.isNotBlank() } ?: "Вложение"
            if (plaintext != null) {
                cacheIncomingAttachmentPreview(messageId, attachment, plaintext)
            }
            if (sealed.body.isNotBlank()) sealed.body else "📎 $label (${plaintext?.size ?: 0} байт)"
        }.getOrElse { sealed.body }
    }

    private suspend fun decryptQueuedDmEnvelope(
        token: String,
        userId: String,
        pairwiseId: String,
        envelope: QueuedEnvelope,
        signalCiphertext: ByteArray,
    ): Pair<String, ByteArray>? {
        val firstPartner = pairwiseIds.partnerFor(pairwiseId)
        val partners = (
            listOfNotNull(firstPartner) +
                dmPartnerByChatId.values +
                chatMemberIds.values.flatten()
            )
            .distinct()
        for (partnerId in partners) {
            val deviceIds = candidateDmDeviceIds(token, partnerId)
            for (deviceId in deviceIds) {
                val plaintext = crypto.decryptMessage(
                    accountId = userId,
                    remoteAccountId = partnerId,
                    remoteDeviceUuid = deviceId,
                    envelopeType = envelope.envelope_type,
                    ciphertext = signalCiphertext,
                ) ?: continue
                return partnerId to plaintext
            }
        }
        return null
    }

    /**
     * Build relay envelopes for a DM payload.
     *
     * Always encrypts for every **active** partner device. When [includeOwnOtherDevices]
     * is true (send path), also encrypts for the sender's other active devices so multi-
     * device installs stay in sync without depending on a single DeviceID / local wipe.
     *
     * Account-level durable history after a full install wipe still needs encrypted
     * backup restore — the server stores only per-mailbox ciphertext, not plaintext.
     */
    private suspend fun buildDmRelayEnvelopes(
        token: String,
        userId: String,
        partnerId: String,
        pairwiseId: String,
        sealedPlaintext: ByteArray,
        includeOwnOtherDevices: Boolean,
    ): List<RelayEnvelopeRequest> {
        if (isPartnerUntrusted(partnerId)) {
            error("Сначала подтвердите новый ключ безопасности собеседника")
        }
        val partnerDevices = api.listUserDevices(token, partnerId)
            .filter { (it.device_status ?: "active") == "active" }
        logSync("buildDmRelayEnvelopes partner=$partnerId devices=${partnerDevices.size}")
        if (partnerDevices.isEmpty()) {
            error(OutboxRecoverableErrors.NO_DEVICES)
        }
        val envelopes = partnerDevices.mapNotNull { device ->
            encryptRelayEnvelopeForDevice(
                token = token,
                userId = userId,
                partnerId = partnerId,
                device = device,
                pairwiseId = pairwiseId,
                plaintext = sealedPlaintext,
            )
        }.toMutableList()

        if (includeOwnOtherDevices) {
            // Best-effort: never fail the partner send because self-sync cannot encrypt.
            // Uses a dedicated path that must not touch partner trust / untrusted state.
            runCatching {
                val selfId = crypto.ensureDeviceIdentity(userId).deviceId
                val ownOther = api.listUserDevices(token, userId)
                    .filter { (it.device_status ?: "active") == "active" }
                    .filter { it.device_id != selfId }
                if (ownOther.isNotEmpty()) {
                    logSync("buildDmRelayEnvelopes self-sync devices=${ownOther.size} keep=$selfId")
                }
                for (device in ownOther) {
                    encryptSelfSyncEnvelopeForOwnDevice(
                        token = token,
                        userId = userId,
                        device = device,
                        pairwiseId = pairwiseId,
                        plaintext = sealedPlaintext,
                    )?.let { envelopes.add(it) }
                }
            }.onFailure {
                logSync("buildDmRelayEnvelopes self-sync skipped: ${it.message}")
            }
        }

        if (envelopes.isEmpty()) {
            error(OutboxRecoverableErrors.NO_ENVELOPES)
        }
        return envelopes
    }

    /** Partner-only fanout (e.g. group/member paths that must not self-sync). */
    private suspend fun buildPartnerRelayEnvelopes(
        token: String,
        userId: String,
        partnerId: String,
        pairwiseId: String,
        sealedPlaintext: ByteArray,
    ): List<RelayEnvelopeRequest> = buildDmRelayEnvelopes(
        token = token,
        userId = userId,
        partnerId = partnerId,
        pairwiseId = pairwiseId,
        sealedPlaintext = sealedPlaintext,
        includeOwnOtherDevices = false,
    )

    private suspend fun encryptRelayEnvelopeForDevice(
        token: String,
        userId: String,
        partnerId: String,
        device: UserDevice,
        pairwiseId: String,
        plaintext: ByteArray,
    ): RelayEnvelopeRequest? {
        checkPartnerIdentityKey(partnerId, device.device_id, device.identity_public_key)
        if (isPartnerUntrusted(partnerId)) return null
        crypto.trustRemoteIdentity(userId, partnerId, device.device_id)

        suspend fun encryptWithFreshBundle(resetFirst: Boolean): EncryptedPayload? {
            if (resetFirst) {
                crypto.resetSession(userId, partnerId, device.device_id)
            }
            val bundle = runCatching { api.getDeviceBundle(token, device.device_id) }.getOrElse {
                logSync("getDeviceBundle failed device=${device.device_id} error=${it.message}")
                return null
            }
            val sessionReady = crypto.ensureSession(userId, partnerId, device.device_id, bundle)
            if (!sessionReady) {
                logSync("ensureSession failed device=${device.device_id}")
            }
            return crypto.encryptMessage(userId, partnerId, device.device_id, plaintext)
        }

        val preferFreshSession = AppRuntimeInfo.devShortcutsEnabled
        val payload = if (preferFreshSession) {
            encryptWithFreshBundle(resetFirst = true)
                ?: encryptWithFreshBundle(resetFirst = false)
        } else {
            encryptWithFreshBundle(resetFirst = false)
                ?: encryptWithFreshBundle(resetFirst = true)
        }
            ?: run {
                logSync(
                    "encryptRelayEnvelope failed device=${device.device_id} " +
                        "status=${device.device_status ?: "unknown"}",
                )
                return null
            }
        return RelayEnvelopeRequest(
            mailbox_token = device.mailbox_token,
            delivery_token = device.delivery_token,
            envelope_type = payload.envelopeType,
            ciphertext = prependPairwiseId(pairwiseId, payload.ciphertext).encodeBase64(),
        )
    }

    /**
     * Encrypt a sealed DM for another install of the *same* account (multi-device self-sync).
     * Must not invoke partner safety-number checks — that would mark the account untrusted
     * against itself and break the real partner send.
     */
    private suspend fun encryptSelfSyncEnvelopeForOwnDevice(
        token: String,
        userId: String,
        device: UserDevice,
        pairwiseId: String,
        plaintext: ByteArray,
    ): RelayEnvelopeRequest? {
        val remoteAccountId = userId
        suspend fun encryptWithFreshBundle(resetFirst: Boolean): EncryptedPayload? {
            if (resetFirst) {
                crypto.resetSession(userId, remoteAccountId, device.device_id)
            }
            val bundle = runCatching { api.getDeviceBundle(token, device.device_id) }.getOrElse {
                logSync("self-sync getDeviceBundle failed device=${device.device_id}: ${it.message}")
                return null
            }
            crypto.ensureSession(userId, remoteAccountId, device.device_id, bundle)
            return runCatching {
                crypto.encryptMessage(userId, remoteAccountId, device.device_id, plaintext)
            }.getOrNull()
        }
        val payload = encryptWithFreshBundle(resetFirst = false)
            ?: encryptWithFreshBundle(resetFirst = true)
            ?: run {
                logSync("self-sync encrypt failed device=${device.device_id}")
                return null
            }
        return RelayEnvelopeRequest(
            mailbox_token = device.mailbox_token,
            delivery_token = device.delivery_token,
            envelope_type = payload.envelopeType,
            ciphertext = prependPairwiseId(pairwiseId, payload.ciphertext).encodeBase64(),
        )
    }

    private suspend fun candidateDmDeviceIds(token: String, partnerId: String): List<String> {
        // Only *active* devices: revoked/pending reinstall ghosts make decrypt try dozens of
        // remote addresses per envelope and can freeze the UI.
        val remoteDevices = runCatching { api.listUserDevices(token, partnerId) }
            .getOrElse {
                logSync("listUserDevices failed partner=$partnerId error=${it.message}")
                emptyList()
            }
            .filter { (it.device_status ?: "active") == "active" }
            .map { it.device_id }
        return remoteDevices.distinct()
    }

    private fun isPermanentOutboxError(message: String?, throwable: Throwable?, jobType: String): Boolean {
        val apiErr = throwable as? ApiException
        if (apiErr != null) {
            if (apiErr.errorMessage?.contains(RELAY_ENVELOPE_LIMIT_ERROR, ignoreCase = true) == true) {
                return true
            }
            val status = apiErr.status
            if ((jobType == OutboxJobType.SEND_GROUP || jobType == OutboxJobType.SEND_GROUP_ATTACHMENT) &&
                status == HttpStatusCode.Forbidden
            ) {
                return true
            }
            val isPermanentClientStatus =
                status.value in 400..499 &&
                    status != HttpStatusCode.Forbidden &&
                    status != HttpStatusCode.PayloadTooLarge &&
                    status != HttpStatusCode.TooManyRequests &&
                    status != HttpStatusCode.RequestTimeout &&
                    status != HttpStatusCode.Unauthorized
            if (isPermanentClientStatus) return true
        }
        return when {
            message.isNullOrBlank() -> false
            message.contains("ключ безопасности") -> true
            message.contains("Group encryption failed") -> true
            else -> false
        }
    }

    private fun encryptedPlaceholder(): String = "🔒 Зашифрованное сообщение"

    private fun logSync(message: String) {
        AppLog.debug(message)
    }

    private class OutboxSendCancelledException(pendingId: String) : Exception("Outbox send cancelled: $pendingId")

    suspend fun rememberChatMembers(chatId: String, memberIds: List<String>) {
        val ids = memberIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty() || chatMemberIds[chatId] == ids) return
        chatMemberIds[chatId] = ids
        persistChatMembers()
        chatMembersRevision.update { it + 1 }
    }

    private suspend fun cacheChatMembers(chats: List<Chat>) {
        var changed = false
        chats.forEach { chat ->
            if (chat.member_ids.isNotEmpty() && chatMemberIds[chat.id] != chat.member_ids) {
                chatMemberIds[chat.id] = chat.member_ids
                changed = true
            }
        }
        persistChatMembers()
        if (changed) chatMembersRevision.update { it + 1 }
    }

    private suspend fun loadCachedChatMembers() {
        val raw = local.loadSetting(KEY_CHAT_MEMBER_IDS)
        if (raw.isBlank()) return
        decodeChatMemberIds(raw).forEach { (chatId, members) ->
            if (members.isNotEmpty()) {
                chatMemberIds[chatId] = members
                hydrateDmPartnerFromMembers(chatId, members)
            }
        }
    }

    private suspend fun loadCachedKnownUsers() {
        val raw = local.loadSetting(KEY_KNOWN_USER_PROFILES)
        if (raw.isBlank()) return
        val users = runCatching {
            profileJson.decodeFromString(profileListSerializer, raw)
        }.getOrDefault(emptyList())
        if (users.isNotEmpty()) {
            rememberUsers(users)
        }
    }

    private suspend fun persistKnownUsers() {
        if (knownUsers.isEmpty()) return
        local.saveSetting(
            KEY_KNOWN_USER_PROFILES,
            profileJson.encodeToString(profileListSerializer, knownUsers.values.toList()),
        )
    }

    private suspend fun persistChatMembers() {
        local.saveSetting(KEY_CHAT_MEMBER_IDS, encodeChatMemberIds(chatMemberIds))
    }

    private suspend fun hydrateDmPartnerFromMembers(chatId: String, memberIds: List<String>) {
        val selfId = SessionStore.user.value?.id ?: return
        val partnerId = memberIds.firstOrNull { it != selfId } ?: return
        dmPartnerByChatId[chatId] = partnerId
        pairwiseIds.getOrCreate(selfId, partnerId)
    }

    private suspend fun resolveDmPartnerId(chat: Chat): String {
        dmPartnerByChatId[chat.id]?.let { return it }
        val selfId = SessionStore.user.value?.id ?: error("No user")
        val members = chatMemberIds[chat.id].orEmpty().ifEmpty { chat.member_ids }
        members.firstOrNull { it != selfId }?.let { partnerId ->
            rememberDmPartner(chat.copy(member_ids = members))
            return partnerId
        }
        runCatching { syncChats() }
        dmPartnerByChatId[chat.id]?.let { return it }
        error("DM partner unknown")
    }

    private suspend fun loadUnreadCounts() {
        val raw = local.loadSetting(KEY_UNREAD_COUNTS)
        _unreadCounts.value = decodeUnreadCounts(raw)
    }

    private suspend fun persistUnreadCounts() {
        local.saveSetting(KEY_UNREAD_COUNTS, encodeUnreadCounts(_unreadCounts.value))
    }

    private suspend fun incrementUnread(chatId: String) {
        val next = (_unreadCounts.value[chatId] ?: 0) + 1
        _unreadCounts.value = _unreadCounts.value + (chatId to next)
        persistUnreadCounts()
    }

    private suspend fun cacheDmPartnerNames(chats: List<Chat>) {
        chats.filter { it.isDirectMessage }.forEach { rememberDmPartner(it) }
    }

    private suspend fun rememberDmPartner(chat: Chat) {
        val selfId = SessionStore.user.value?.id ?: return
        val members = chat.member_ids.ifEmpty { chatMemberIds[chat.id].orEmpty() }
        if (members.isNotEmpty()) {
            chatMemberIds[chat.id] = members
        }
        val partnerId = members.firstOrNull { it != selfId } ?: return
        dmPartnerByChatId[chat.id] = partnerId
        knownUsernames[partnerId] = chat.title
        pairwiseIds.getOrCreate(selfId, partnerId)
    }

    private suspend fun logStaleRelayQueuesIfAny(token: String, accountId: String) {
        val identity = crypto.ensureDeviceIdentity(accountId)
        val ownDevices = api.listUserDevices(token, accountId)
        var otherQueue = 0
        for (device in ownDevices) {
            if (device.device_id == identity.deviceId) continue
            val queue = api.fetchMessageQueue(token, device.device_id, limit = 20)
            otherQueue += queue.envelopes.size
        }
        if (otherQueue > 0) {
            logSync(
                "relay warn: $otherQueue envelopes on other devices; " +
                    "current=${identity.deviceId} cannot decrypt them — ask sender to resend",
            )
        }
    }

    private suspend fun refreshChatPreview(message: Message) {
        if (message.visibility == MESSAGE_VISIBILITY_THREAD_ONLY) return
        local.updateChatPreview(message.chat_id, message.body, message.created_at)
    }

    private suspend fun refreshChatPreviewFromLocal(chatId: String) {
        val latest = local.listAllMessages()
            .filter { message ->
                message.chat_id == chatId && message.visibility != MESSAGE_VISIBILITY_THREAD_ONLY
            }
            .sortedForChat()
            .lastOrNull()
        local.updateChatPreview(chatId, latest?.body, latest?.created_at)
    }

    private suspend fun ensureOutboxSendActive(pendingId: String) {
        val hasJob = local.findOutboxJob(pendingId) != null
        val message = local.findMessageById(pendingId) ?: local.findMessageByPendingId(pendingId)
        if (!hasJob || message?.isSending() != true) {
            throw OutboxSendCancelledException(pendingId)
        }
    }

    private suspend fun resolveIncomingBody(
        token: String,
        sealed: SealedDmPayload,
        messageId: String,
    ): String {
        val attachment = sealed.attachment ?: return sealed.body
        return runCatching {
            val encryptedBlob = loadOrDownloadAttachmentBlob(
                token = token,
                attachment = attachment,
                chatId = sealed.chat_id,
                messageId = messageId,
                ownerAccountId = SessionStore.user.value?.id ?: sealed.sender_account_id,
            )
            val fileKey = attachment.file_key.decodeBase64()
            val plaintext = withContext(Dispatchers.Default) {
                FileAttachmentCrypto.decrypt(fileKey, encryptedBlob)
            }
                ?: return sealed.body
            val label = attachment.file_name?.takeIf { it.isNotBlank() } ?: "Вложение"
            cacheIncomingAttachmentPreview(messageId, attachment, plaintext)
            "📎 $label (${plaintext.size} байт)"
        }.getOrDefault(sealed.body)
    }

    private suspend fun cacheIncomingAttachmentPreview(
        messageId: String,
        attachment: SealedAttachmentRef,
        plaintext: ByteArray,
    ) {
        val normalizedMime = normalizeAttachmentMimeType(attachment.mime_type, attachment.file_name)
        val kind = AttachmentKind.fromWireName(attachment.kind)
            .takeUnless { it == AttachmentKind.UNKNOWN }
            ?: resolveAttachmentKind(normalizedMime, attachment.file_name)
        val thumbnailBytes = attachment.thumbnail
            ?.let { runCatching { it.decodeBase64() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val validatedMedia = AttachmentMediaValidator.validatePlaintextForPreview(
            bytes = plaintext,
            fileName = attachment.file_name,
            mimeType = normalizedMime,
            expectedPlaintextSize = attachment.plaintext_size,
        )
        val previewBytes = when {
            kind == AttachmentKind.IMAGE && validatedMedia?.canPreviewImage == true -> plaintext
            kind == AttachmentKind.VIDEO && plaintext.isNotEmpty() -> plaintext
            (kind == AttachmentKind.VOICE || kind == AttachmentKind.AUDIO) && validatedMedia != null -> plaintext
            else -> ByteArray(0)
        }
        val previewMime = if (kind == AttachmentKind.IMAGE && validatedMedia?.canPreviewImage == true) {
            validatedMedia.mimeType
        } else {
            normalizedMime
        }
        val generatedThumbnail = if (kind == AttachmentKind.IMAGE && validatedMedia?.canPreviewImage == true) {
            AttachmentThumbnailer.createThumbnail(plaintext, previewMime)
        } else {
            null
        }
        val resolvedThumbnail = thumbnailBytes ?: generatedThumbnail
        attachmentPreviewStore.cache(
            messageId = messageId,
            bytes = previewBytes,
            fileName = validatedMedia?.fileName ?: attachment.file_name,
            mimeType = previewMime,
            kind = kind,
            thumbnailBytes = resolvedThumbnail,
            width = attachment.width,
            height = attachment.height,
            durationMs = attachment.duration_ms,
            waveform = attachment.waveform,
        )
        persistentAttachmentCache.storeThumbnailPlaintext(
            cacheId = attachmentCacheIdForRemote(attachment.attachment_id),
            thumbnailPlaintext = resolvedThumbnail,
        )
    }

    private suspend fun loadOrDownloadAttachmentBlob(
        token: String,
        attachment: SealedAttachmentRef,
        chatId: String,
        messageId: String,
        ownerAccountId: String,
    ): ByteArray {
        val cacheId = attachmentCacheIdForRemote(attachment.attachment_id)
        val kind = AttachmentKind.fromWireName(attachment.kind)
            .takeUnless { it == AttachmentKind.UNKNOWN }
            ?: resolveAttachmentKind(attachment.mime_type, attachment.file_name)
        val alreadyCached = persistentAttachmentCache.hasEncrypted(cacheId) ||
            persistentAttachmentCache.metadata.findByAttachmentId(attachment.attachment_id)
                ?.let { persistentAttachmentCache.hasEncrypted(it.cacheId) } == true
        val encryptedBlob = attachmentTransferManager.loadOrDownload(
            token = token,
            attachmentId = attachment.attachment_id,
            cacheId = cacheId,
            chatId = chatId,
            ownerAccountId = ownerAccountId,
            messageId = messageId,
            fileName = attachment.file_name,
            mimeType = normalizeAttachmentMimeType(attachment.mime_type, attachment.file_name),
            plaintextSize = attachment.plaintext_size,
            kind = kind,
            width = attachment.width,
            height = attachment.height,
            durationMs = attachment.duration_ms,
            waveform = encodeWaveform(attachment.waveform),
        )
        if (!alreadyCached) {
            runCatching { maintainMediaCache(force = true) }
                .onFailure { logSync("media cache maintenance after download failed: ${it.message}") }
        }
        return encryptedBlob
    }

    // --- Secure History (Signal Secure Backups style; recovery key only) ---

    private val _secureHistoryRestoreAvailable = MutableStateFlow(false)
    /** True when cloud has GLSBR1 backup and local history is empty — UI must ask recovery key. */
    val secureHistoryRestoreAvailable: StateFlow<Boolean> = _secureHistoryRestoreAvailable.asStateFlow()

    suspend fun secureHistoryEnabled(): Boolean =
        local.loadSetting(KEY_SECURE_HISTORY_RECOVERY_KEY).isNotBlank()

    /**
     * Enable Secure Backup (Signal-style). Generates a 64-hex recovery key, stores it on
     * **this device only** (SQLCipher settings), uploads encrypted history. Server never
     * receives the recovery key — zero-knowledge.
     *
     * @return recovery key — show once; user must write it down.
     */
    suspend fun enableSecureHistoryBackup(): String {
        val user = SessionStore.user.value ?: error("Нужен вход в аккаунт")
        val recoveryKey = SecureHistoryCrypto.generateRecoveryKey()
        local.saveSetting(KEY_SECURE_HISTORY_RECOVERY_KEY, recoveryKey)
        local.saveSetting(KEY_SECURE_HISTORY_ENABLED_AT, nowIso())
        uploadSecureHistory(recoveryKey, user.id)
        _secureHistoryRestoreAvailable.value = false
        logSync("secure history enabled messages=${local.countMessages()}")
        return recoveryKey
    }

    /**
     * Restore history on a **new phone** after login. Requires the recovery key from the
     * previous install. Merges into local DB without wiping. Then enables auto-upload
     * on this device by caching the recovery key locally.
     */
    suspend fun restoreSecureHistory(recoveryKey: String): AccountHistoryArchive {
        val user = SessionStore.user.value ?: error("Нужен вход в аккаунт")
        val sealedB64 = auth.withAuth { token ->
            runCatching { api.getSyncSnapshot(token).snapshot_data }
                .getOrElse { err ->
                    if (err is ApiException && err.status == HttpStatusCode.NotFound) {
                        error("Сохранённой копии переписки нет. Сначала включите защиту на старом телефоне.")
                    }
                    throw err
                }
        }?.takeIf { it.isNotBlank() } ?: error(
            "Сохранённой копии переписки нет. Сначала включите защиту на старом телефоне.",
        )

        val sealed = sealedB64.decodeBase64()
        if (!SecureHistoryCrypto.isSecureBackupBlob(sealed)) {
            error("Не удалось прочитать сохранённую копию. Попробуйте включить защиту заново на старом телефоне.")
        }
        val plain = withContext(Dispatchers.Default) {
            SecureHistoryCrypto.open(recoveryKey, sealed)
        }
            ?: error("Неверный код восстановления. Проверьте, что ввели его целиком.")
        val archive = decodeAccountHistoryArchive(plain)
        if (archive.user_id.isNotBlank() && archive.user_id != user.id) {
            error("Этот код от другого аккаунта")
        }
        local.mergeAccountHistory(archive.chats, archive.messages)
        runCatching { cacheDmPartnerNames(archive.chats) }
        // Cache key on this install for auto-upload (never sent to server).
        local.saveSetting(KEY_SECURE_HISTORY_RECOVERY_KEY, recoveryKey.trim().lowercase())
        local.saveSetting(KEY_SECURE_HISTORY_ENABLED_AT, nowIso())
        _secureHistoryRestoreAvailable.value = false
        runCatching { uploadSecureHistory(recoveryKey, user.id) }
        logSync(
            "secure history restored chats=${archive.chats.size} messages=${archive.messageCount}",
        )
        return archive
    }

    suspend fun uploadSecureHistoryIfEnabled() {
        val recoveryKey = local.loadSetting(KEY_SECURE_HISTORY_RECOVERY_KEY).takeIf { it.isNotBlank() }
            ?: return
        val user = SessionStore.user.value ?: return
        runCatching { uploadSecureHistory(recoveryKey, user.id) }
            .onFailure { logSync("secure history auto-upload: ${it.message}") }
    }

    private suspend fun uploadSecureHistory(recoveryKey: String, userId: String) {
        val archive = AccountHistoryArchive(
            user_id = userId,
            exported_at = nowIso(),
            chats = local.listAllChats(),
            messages = local.listAllMessages(),
        )
        val sealed = withContext(Dispatchers.Default) {
            SecureHistoryCrypto.seal(recoveryKey, archive.encodeToBytes())
        }
        auth.withAuth { token ->
            api.saveSyncSnapshot(
                token = token,
                request = SaveSyncSnapshotRequest(
                    event_id = currentTimeMillis(),
                    snapshot_data = sealed.encodeBase64(),
                ),
            )
        }
        local.saveSetting(KEY_SECURE_HISTORY_UPLOADED_AT, nowIso())
        local.saveSetting(KEY_SECURE_HISTORY_MESSAGE_COUNT, archive.messageCount.toString())
        logSync("secure history uploaded messages=${archive.messageCount}")
    }

    /**
     * After login on a new install: if cloud has GLSBR1 and local has no messages and no
     * local recovery key, UI should prompt for recovery key (not login password).
     */
    private suspend fun refreshSecureHistoryRestoreHint() {
        val hasLocalKey = local.loadSetting(KEY_SECURE_HISTORY_RECOVERY_KEY).isNotBlank()
        if (hasLocalKey) {
            _secureHistoryRestoreAvailable.value = false
            return
        }
        val localCount = local.countMessages()
        if (localCount > 0) {
            _secureHistoryRestoreAvailable.value = false
            return
        }
        val sealedB64 = runCatching {
            auth.withAuth { token -> api.getSyncSnapshot(token).snapshot_data }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (sealedB64 == null) {
            _secureHistoryRestoreAvailable.value = false
            return
        }
        val sealed = runCatching { sealedB64.decodeBase64() }.getOrNull()
        val available = sealed != null && SecureHistoryCrypto.isSecureBackupBlob(sealed)
        _secureHistoryRestoreAvailable.value = available
        if (available) {
            logSync("secure history: cloud backup present — recovery key required to restore")
        }
    }

    private fun nowIso(): String = currentIsoTimestamp()

    companion object {
        private val reactionJson = Json { ignoreUnknownKeys = true }
        private val reactionMapSerializer =
            MapSerializer(String.serializer(), ListSerializer(MessageReaction.serializer()))
        private val favoritesJson = Json { ignoreUnknownKeys = true }
        private val favoriteListSerializer = ListSerializer(FavoriteMessageRef.serializer())
        private val editedIdsSerializer = ListSerializer(String.serializer())
        private val profileJson = Json { ignoreUnknownKeys = true }
        private val profileListSerializer = ListSerializer(User.serializer())
        private const val KEY_NOTIFICATION_PREFS = "notification_preferences_v1"

        private const val KEY_UNREAD_COUNTS = "chat_unread_counts"
        private const val KEY_UNTRUSTED_PARTNERS = "identity_untrusted_ids"
        private const val KEY_FAVORITE_MESSAGES = "favorite_messages"
        private const val KEY_EDITED_MESSAGE_IDS = "edited_message_ids"
        private const val KEY_KNOWN_USER_PROFILES = "known_user_profiles"
        private const val KEY_PASSKEYS = "webauthn_passkeys_v1"
        private const val KEY_ACCOUNT_RECOVERY_KEY_CREATED = "account_recovery_key_created_v1"
        private const val KEY_SECURE_HISTORY_RECOVERY_KEY = "secure_history_recovery_key_v1"
        private const val KEY_SECURE_HISTORY_ENABLED_AT = "secure_history_enabled_at"
        private const val KEY_SECURE_HISTORY_UPLOADED_AT = "secure_history_uploaded_at"
        private const val KEY_SECURE_HISTORY_MESSAGE_COUNT = "secure_history_message_count"
        private const val QUEUE_POLL_OPEN_CHAT_MS = 2_000L
        private const val QUEUE_POLL_BACKGROUND_MS = 5_000L
        private const val QUEUE_RATE_LIMIT_BACKOFF_MS = 15_000L
        private const val MEDIA_CACHE_MAINTENANCE_INTERVAL_MS = 5 * 60 * 1_000L
        private const val OUTBOX_AUTH_MAX_ATTEMPTS = 3
        private const val OUTBOX_AUTH_FAILURE_TIMEOUT_MS = 30_000L
        private const val OUTBOX_AUTH_PENDING_ERROR = "auth pending"
        private const val RELAY_ENVELOPE_LIMIT_ERROR = "relay envelope limit exceeded"
        private const val DEVICE_REGISTER_MAX_ATTEMPTS = 5
        private const val DEVICE_REGISTER_RETRY_MS = 1_500L
        /**
         * Max active devices kept linked per account after local re-register.
         * Phone + tablet is fine; same-day reinstall ghosts pile past this and
         * break PREKEY fan-out (InvalidKeyIdException / silent non-delivery).
         */
        private const val MAX_LINKED_ACTIVE_DEVICES = 3
        private const val PREKEY_REPLENISH_THRESHOLD = 20
        private const val PREKEY_WATCHDOG_INTERVAL_MS = 6 * 60 * 60 * 1_000L
        private const val RESTORE_SESSION_REFRESH_TIMEOUT_MS = 6_000L
        private const val WS_RECONNECT_BASE_MS = 1_500L
        private const val WS_RECONNECT_MAX_MS = 30_000L
        private const val SIGNED_PREKEY_ROTATION_INTERVAL_MS = 7 * 24 * 60 * 60 * 1_000L
        private const val KEY_LAST_SIGNED_PREKEY_ROTATION = "last_signed_prekey_rotation_ms"

        private fun wsReconnectDelayMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            val delay = WS_RECONNECT_BASE_MS * (1L shl shift)
            return delay.coerceAtMost(WS_RECONNECT_MAX_MS)
        }

        private fun isWebSocketUnauthorized(message: String, error: Throwable): Boolean {
            if (error is ApiException && error.status == io.ktor.http.HttpStatusCode.Unauthorized) {
                return true
            }
            val lower = message.lowercase()
            return "401" in lower ||
                "unauthorized" in lower ||
                "invalid token" in lower ||
                "token expired" in lower
        }
        private const val KEY_LAST_SYNC_CURSOR = "last_sync_cursor"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_SIZE = "last_backup_size_bytes"
        private const val KEY_LAST_BACKUP_UPLOADED_AT = "last_backup_uploaded_at"
        private const val KEY_CHAT_MEMBER_IDS = "chat_member_ids"

        private fun encodeChatMemberIds(map: Map<String, List<String>>): String =
            map.entries.joinToString("\n") { (chatId, members) ->
                "$chatId|${members.joinToString(",")}"
            }

        private fun decodeChatMemberIds(raw: String): Map<String, List<String>> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    parts[0] to parts[1].split(",").filter { it.isNotBlank() }
                }
                .toMap()

        private fun encodePartnerIdSet(ids: Set<String>): String = ids.joinToString(",")

        private fun decodePartnerIdSet(raw: String): Set<String> =
            if (raw.isBlank()) emptySet() else raw.split(",").filter { it.isNotBlank() }.toSet()

        private fun encodeUnreadCounts(counts: Map<String, Int>): String =
            counts.entries.joinToString(",") { "${it.key}:${it.value}" }

        private fun decodeUnreadCounts(raw: String): Map<String, Int> {
            if (raw.isBlank()) return emptyMap()
            return raw.split(",")
                .mapNotNull { part ->
                    val pieces = part.split(":", limit = 2)
                    if (pieces.size != 2) return@mapNotNull null
                    val count = pieces[1].toIntOrNull() ?: return@mapNotNull null
                    pieces[0] to count
                }
                .toMap()
        }
    }
}
