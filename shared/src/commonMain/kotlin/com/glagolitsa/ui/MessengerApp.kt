// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.glagolitsa.audio.AudioProcessingCoordinator
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.log.AppLog
import com.glagolitsa.model.MessageReplyPolicy
import com.glagolitsa.model.ShareIntakePolicy
import com.glagolitsa.model.statusLabel
import com.glagolitsa.push.PushWakeCoordinator
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.session.BiometricUnlockPolicy
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.session.SessionNavigationPolicy
import com.glagolitsa.session.SessionStore
import com.glagolitsa.share.IncomingShareBus
import com.glagolitsa.ui.chat.AppInboxSyncEffect
import com.glagolitsa.ui.chat.AppWebSocketEffect
import com.glagolitsa.ui.chat.ChatInfoScreen
import com.glagolitsa.ui.chat.ChatSettingsScreen
import com.glagolitsa.ui.presence.PresenceHeartbeatEffect
import com.glagolitsa.ui.navigation.AppRoute
import com.glagolitsa.ui.loadAudioProcessingSettings
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.AppThemeController
import com.glagolitsa.ui.theme.AppThemeMode
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaTheme
import com.glagolitsa.ui.theme.themePreferenceReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
fun MessengerApp(
    driverFactory: DatabaseDriverFactory,
    cryptoEngineFactory: CryptoEngineFactory,
    secureSessionStore: SecureSessionStore,
    localAuthenticator: LocalAuthenticator,
) {
    val repository = remember(driverFactory, cryptoEngineFactory, secureSessionStore) {
        MessengerRepository(driverFactory, cryptoEngineFactory, secureSessionStore)
    }
    AppThemeController.restorePersisted()
    LaunchedEffect(repository) {
        migrateThemeFromAccountSettings(repository)
    }
    val dark = AppThemeController.isDark
    GlagolitsaTheme(dark = dark) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(GlagolitsaColors.Background950),
            color = GlagolitsaColors.Background950,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MessengerRoot(
                    repository = repository,
                    localAuthenticator = localAuthenticator,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MessengerRoot(
    repository: MessengerRepository,
    localAuthenticator: LocalAuthenticator,
    modifier: Modifier = Modifier,
) {
    // Signal / Mattermost: first frame is a real shell (auth or app), never an endless null splash.
    // Login is the safe default; local restore may promote to Main without waiting on network.
    var route by remember { mutableStateOf<AppRoute>(AppRoute.Login()) }
    val token by SessionStore.token.collectAsState()
    val user by SessionStore.user.collectAsState()
    val reauthRequired by SessionStore.reauthRequired.collectAsState()
    val activeCall by repository.calls.activeCall.collectAsState()
    val pendingShare by IncomingShareBus.pending.collectAsState()
    val scope = rememberCoroutineScope()
    var shareSendingChatId by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val updatePrompt = rememberClientUpdatePrompt(repository)

    LaunchedEffect(repository) {
        // 1) Local-only routing first (Signal: device/session shell before network).
        val biometricCandidate = runCatching { repository.biometricUnlockCandidate() }
            .onFailure { AppLog.warning("biometricUnlockCandidate failed: ${it.message}") }
            .getOrNull()
        if (BiometricUnlockPolicy.shouldGateColdStart(
                hasStoredSession = biometricCandidate != null,
                // Candidate already implies unlock-enabled for that user (Signal device gate).
                biometricUnlockEnabled = biometricCandidate != null,
                reauthRequired = SessionStore.reauthRequired.value,
            )
        ) {
            route = AppRoute.Login(prefilledUsername = biometricCandidate?.username)
        } else {
            val fastShell = runCatching { repository.restoreFastSessionShell() }
                .onFailure { AppLog.warning("restoreFastSessionShell failed: ${it.message}") }
                .getOrDefault(false)
            if (fastShell) {
                // Mattermost/Element: cached identity → main shell immediately.
                route = AppRoute.Main()
                launch {
                    yield()
                    val localShell = runCatching { repository.restoreLocalSessionShell() }
                        .onFailure { AppLog.warning("restoreLocalSessionShell failed: ${it.message}") }
                        .getOrDefault(false)
                    if (!localShell) {
                        repository.clearFastSessionShell()
                        SessionStore.clear()
                        route = AppRoute.Login()
                    } else {
                        migrateThemeFromAccountSettings(repository)
                        // Network after UI is visible (Signal: never block first paint on REST).
                        runCatching { repository.restoreSessionOnline() }
                            .onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                AppLog.warning("restoreSessionOnline failed: ${it.message}")
                            }
                    }
                }
            } else {
                // Disk-only credentials; still no network on the critical path.
                val localShell = runCatching { repository.restoreLocalSessionShell() }
                    .onFailure { AppLog.warning("restoreLocalSessionShell failed: ${it.message}") }
                    .getOrDefault(false)
                if (localShell) {
                    migrateThemeFromAccountSettings(repository)
                }
                route = if (SessionNavigationPolicy.initialRouteIsMain(localShell)) {
                    AppRoute.Main()
                } else {
                    AppRoute.Login()
                }
                if (localShell) {
                    launch {
                        yield()
                        runCatching { repository.restoreSessionOnline() }
                            .onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                AppLog.warning("restoreSessionOnline failed: ${it.message}")
                            }
                    }
                }
            }
        }

        // 2) Platform wiring after shell decision (Signal: defer non-UI work past first frame).
        yield()
        runCatching {
            val loaded = loadAudioProcessingSettings(repository)
            AudioProcessingCoordinator.hydrate(loaded)
            val assigned = AudioProcessingCoordinator.currentSettings().experimentArm
            if (loaded.experimentArm == null && assigned != null) {
                repository.saveProfileSetting(
                    com.glagolitsa.audio.AudioProcessingSettingsKeys.EXPERIMENT_ARM,
                    assigned.storageKey,
                )
            }
        }
        repository.bindBackgroundSync()
        PushWakeCoordinator.bind(
            onSyncQueue = { repository.drainPendingSyncForPush() },
            onPrekeysLow = { repository.runPrekeyWatchdog() },
            onTokenRefresh = { repository.registerPushToken(pushToken = it) },
            onIncomingCall = { data ->
                repository.calls.presentIncomingFromWake(data["call_id"].orEmpty())
            },
        )
    }

    // Key on user identity + reauth gate, NOT on [token]: refresh inside bootstrap updates
    // the access token and would cancel this effect mid-enroll ("scope left the composition"),
    // then concurrent paths mark reauth and leave outbox stuck on SENDING.
    val bootstrapUserId = user?.id
    val bootstrapAllowed = token != null && !reauthRequired
    LaunchedEffect(bootstrapUserId, bootstrapAllowed) {
        if (bootstrapUserId != null && bootstrapAllowed) {
            // Must never crash activity on refresh timeout after install -r (keeps old session).
            runCatching { repository.bootstrapRestoredSession() }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    AppLog.warning("bootstrapRestoredSession: ${it.message}")
                }
        }
    }

    LaunchedEffect(reauthRequired, user) {
        val cachedUser = user
        // Soft reauth only — temporary token null must not leave chat (see SessionNavigationPolicy).
        if (SessionNavigationPolicy.shouldRouteToReauthLogin(
                reauthRequired = reauthRequired,
                hasUser = cachedUser != null,
            )
        ) {
            route = AppRoute.Login(prefilledUsername = cachedUser?.username)
        }
    }

    LaunchedEffect(user, token, reauthRequired, repository) {
        // Telegram-style: keep shell + retry refresh while identity is known.
        while (
            isActive &&
            SessionNavigationPolicy.shouldAttemptSessionRecovery(
                hasUser = user != null,
                hasAccessToken = token != null,
                reauthRequired = reauthRequired,
            )
        ) {
            val ok = runCatching { repository.tryRecoverSession() }.getOrDefault(false)
            if (ok) break
            delay(SESSION_RECOVERY_RETRY_MS)
        }
    }

    AppWebSocketEffect(token = token, repository = repository)
    AppInboxSyncEffect(token = token, repository = repository)
    PresenceHeartbeatEffect(token = token, repository = repository)

    LaunchedEffect(token, repository) {
        if (token != null) {
            repository.startPrekeyWatchdog(this)
        } else {
            repository.stopPrekeyWatchdog()
        }
    }

    LaunchedEffect(token, user) {
        val current = route
        val alreadyOnAuth = current is AppRoute.Login || current is AppRoute.Register
        // Full logout only; mid-refresh (token null, user set) stays on ChatDetail/Main.
        if (SessionNavigationPolicy.shouldRouteToLoggedOutLogin(
                hasAccessToken = token != null,
                hasUser = user != null,
                alreadyOnAuthScreen = alreadyOnAuth,
            )
        ) {
            route = AppRoute.Login()
        }
    }

    AppBackHandler(enabled = route.canNavigateBack()) {
        when (val current = route) {
            AppRoute.Register -> route = AppRoute.Login()
            AppRoute.CreateChannel -> route = AppRoute.Main()
            is AppRoute.ChatDetail -> {
                repository.clearActiveChat()
                route = AppRoute.Main()
            }
            is AppRoute.ChatThread -> route = AppRoute.ChatDetail(current.chat)
            is AppRoute.ChatInfo -> route = AppRoute.ChatDetail(current.chat)
            is AppRoute.ChatSettings -> route = AppRoute.ChatDetail(current.chat)
            is AppRoute.SafetyNumber -> route = AppRoute.ChatDetail(current.chat)
            else -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = route) {
            is AppRoute.Login -> LoginScreen(
                repository = repository,
                localAuthenticator = localAuthenticator,
                initialUsername = current.prefilledUsername,
                onLoggedIn = { route = AppRoute.Main() },
                onOpenRegister = { route = AppRoute.Register },
            )

            AppRoute.Register -> RegisterScreen(
                repository = repository,
                onRegistered = { route = AppRoute.Main() },
                onOpenLogin = { username -> route = AppRoute.Login(prefilledUsername = username) },
            )

            is AppRoute.Main -> MainScreen(
                repository = repository,
                localAuthenticator = localAuthenticator,
                initialTab = current.tab,
                updatePrompt = updatePrompt,
                onChatSelected = { chat -> route = AppRoute.ChatDetail(chat) },
                onCreateChannel = { route = AppRoute.CreateChannel },
                onLoggedOut = { route = AppRoute.Login() },
            )

            AppRoute.CreateChannel -> CreateChannelScreen(
                repository = repository,
                onCancel = { route = AppRoute.Main() },
                onCreated = { chat -> route = AppRoute.ChatDetail(chat) },
            )

            is AppRoute.ChatDetail -> if (current.chat.isChannel) {
                com.glagolitsa.ui.channel.ChannelScreen(
                    chat = current.chat,
                    repository = repository,
                    onBack = {
                        repository.clearActiveChat()
                        route = AppRoute.Main()
                    },
                    onOpenSettings = { route = AppRoute.ChatSettings(current.chat) },
                    onOpenThread = { message ->
                        route = AppRoute.ChatThread(current.chat, message)
                    },
                )
            } else ChatScreen(
                chat = current.chat,
                repository = repository,
                initialFocusMessageId = current.focusMessageId,
                onBack = {
                    repository.clearActiveChat()
                    route = AppRoute.Main()
                },
                onChatInfo = { route = AppRoute.ChatInfo(current.chat) },
                onChatSettings = { route = AppRoute.ChatSettings(current.chat) },
                onOpenThread = { message ->
                    if (MessageReplyPolicy.canReplyInThread(current.chat)) {
                        route = AppRoute.ChatThread(current.chat, message)
                    }
                },
                onSafetyNumber = { partnerId ->
                    route = AppRoute.SafetyNumber(current.chat, partnerId)
                },
                onForwardChatOpened = { targetChat ->
                    route = AppRoute.ChatDetail(targetChat)
                },
                onOpenChat = { targetChat ->
                    route = AppRoute.ChatDetail(targetChat)
                },
            )

            is AppRoute.ChatThread -> {
                if (!MessageReplyPolicy.canReplyInThread(current.chat)) {
                    LaunchedEffect(current.chat.id) {
                        route = AppRoute.ChatDetail(current.chat)
                    }
                } else {
                    ThreadScreen(
                        chat = current.chat,
                        parentMessage = current.parentMessage,
                        repository = repository,
                        onBack = { route = AppRoute.ChatDetail(current.chat) },
                    )
                }
            }

            is AppRoute.ChatInfo -> ChatInfoScreen(
                chat = current.chat,
                repository = repository,
                presenceStatus = chatInfoPresenceStatus(current.chat, repository),
                onBack = { route = AppRoute.ChatDetail(current.chat) },
                onConversationRemoved = { route = AppRoute.Main() },
            )

            is AppRoute.ChatSettings -> {
                if (current.chat.isChannel) {
                    com.glagolitsa.ui.channel.ChannelSettingsScreen(
                        chat = current.chat,
                        repository = repository,
                        onBack = { route = AppRoute.ChatDetail(current.chat) },
                        onConversationRemoved = { route = AppRoute.Main() },
                    )
                } else {
                    val partnerId = repository.dmPartnerFor(current.chat.id)
                    ChatSettingsScreen(
                        chat = current.chat,
                        repository = repository,
                        onBack = { route = AppRoute.ChatDetail(current.chat) },
                        onConversationRemoved = { route = AppRoute.Main() },
                        onSafetyNumber = partnerId?.let { id ->
                            { route = AppRoute.SafetyNumber(current.chat, id) }
                        },
                    )
                }
            }

            is AppRoute.SafetyNumber -> SafetyNumberScreen(
                chat = current.chat,
                partnerUserId = current.partnerUserId,
                repository = repository,
                onBack = { route = AppRoute.ChatDetail(current.chat) },
            )
        }

        activeCall?.let { callState ->
            CallScreen(
                state = callState,
                repository = repository,
                onDismiss = { repository.calls.dismissCall() },
            )
        }

        // External share (YouTube / Telegram / browser) → pick channel/chat and send.
        val share = pendingShare
        if (share != null && user != null && token != null && !reauthRequired) {
            ShareToChatOverlay(
                repository = repository,
                share = share,
                sendingChatId = shareSendingChatId,
                error = shareError,
                onDismiss = {
                    if (shareSendingChatId == null) {
                        shareError = null
                        IncomingShareBus.clear()
                    }
                },
                onSendToChat = { chat ->
                    if (shareSendingChatId == null) {
                        shareSendingChatId = chat.id
                        shareError = null
                        scope.launch {
                            runCatching {
                                repository.sendMessage(chatId = chat.id, body = share.text)
                            }.onSuccess {
                                IncomingShareBus.clear()
                                shareError = null
                                route = AppRoute.ChatDetail(chat)
                            }.onFailure {
                                shareError = it.message ?: "Не удалось отправить"
                            }
                            shareSendingChatId = null
                        }
                    }
                },
            )
        }

        // Hard install gate only. Soft card lives in-flow on Main so it never
        // covers chat chrome (back / call / menu).
        ClientUpdateGate(prompt = updatePrompt)
    }
}

private const val SESSION_RECOVERY_RETRY_MS = 30_000L

@Composable
private fun ShareToChatOverlay(
    repository: MessengerRepository,
    share: com.glagolitsa.share.IncomingShare,
    sendingChatId: String?,
    error: String?,
    onDismiss: () -> Unit,
    onSendToChat: (com.glagolitsa.model.Chat) -> Unit,
) {
    val chats by repository.observeChats().collectAsState(initial = emptyList())
    val shareTargets = remember(chats) { ShareIntakePolicy.orderShareTargets(chats) }
    ShareToChatDialog(
        share = share,
        targets = shareTargets,
        sendingChatId = sendingChatId,
        error = error,
        onDismiss = onDismiss,
        onSendToChat = onSendToChat,
    )
}

/**
 * One-time copy from account SQLDelight when device prefs are still empty
 * (users who toggled light before device-scoped persistence existed).
 */
private suspend fun migrateThemeFromAccountSettings(repository: MessengerRepository) {
    if (themePreferenceReader() != null) return
    val stored = runCatching {
        repository.loadProfileSetting(AppThemeMode.SETTINGS_KEY, "")
    }.getOrDefault("")
    if (stored.isBlank()) return
    AppThemeController.applyMode(AppThemeMode.fromStorage(stored))
    AppThemeController.persistCurrent()
}

private fun AppRoute.canNavigateBack(): Boolean =
    when (this) {
        is AppRoute.Login,
        is AppRoute.Main -> false
        AppRoute.Register,
        AppRoute.CreateChannel,
        is AppRoute.ChatDetail,
        is AppRoute.ChatThread,
        is AppRoute.ChatInfo,
        is AppRoute.ChatSettings,
        is AppRoute.SafetyNumber -> true
    }

@Composable
private fun chatInfoPresenceStatus(
    chat: com.glagolitsa.model.Chat,
    repository: MessengerRepository,
): String? {
    val presenceMap by repository.presence.partnerPresence.collectAsState()
    if (!chat.isDirectMessage) return null
    val partnerId = repository.dmPartnerFor(chat.id) ?: return null
    return presenceMap[partnerId]?.statusLabel()
}
