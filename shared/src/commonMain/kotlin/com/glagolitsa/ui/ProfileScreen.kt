// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ClientPlatform
import com.glagolitsa.model.ClientUpdateEvaluator
import com.glagolitsa.model.FavoriteMessageItem
import com.glagolitsa.model.FavoriteMessagesLogic
import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.PresenceVisibility
import com.glagolitsa.model.ProfileAboutPolicy
import com.glagolitsa.model.ProfileUpdateInput
import com.glagolitsa.model.StatusChannels
import com.glagolitsa.model.UpdateDecision
import com.glagolitsa.model.UpdatePresencePrivacyRequest
import com.glagolitsa.model.UserDevice
import com.glagolitsa.model.UserPresence
import com.glagolitsa.model.avatarLabel
import com.glagolitsa.model.displayLabel
import com.glagolitsa.model.presenceState
import com.glagolitsa.media.AttachmentCacheStats
import com.glagolitsa.media.AttachmentRetentionPolicy
import com.glagolitsa.platform.AppRuntimeInfo
import com.glagolitsa.platform.toClientAppIdentity
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.LocalAuthAvailability
import com.glagolitsa.security.LocalAuthResult
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.session.SessionStore
import com.glagolitsa.backup.CloudBackupInfo
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonSize
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.GlagolitsaSwitch
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.profile.ProfileHeroCard
import com.glagolitsa.ui.profile.ProfileHeroOrnament
import com.glagolitsa.ui.profile.ProfileHeroOrnamentPreview
import com.glagolitsa.ui.profile.ProfileMenuBellIcon
import com.glagolitsa.ui.profile.ProfileMenuCard
import com.glagolitsa.ui.profile.ProfileMenuChannelIcon
import com.glagolitsa.ui.profile.ProfileMenuDevicesIcon
import com.glagolitsa.ui.profile.ProfileMenuEntry
import com.glagolitsa.ui.profile.ProfileMenuGlobeIcon
import com.glagolitsa.ui.profile.ProfileMenuHelpIcon
import com.glagolitsa.ui.profile.ProfileMenuInfoIcon
import com.glagolitsa.ui.profile.ProfileMenuLayout
import com.glagolitsa.ui.profile.ProfileMenuLockIcon
import com.glagolitsa.ui.profile.ProfileMenuNoiseIcon
import com.glagolitsa.ui.profile.ProfileMenuPhoneIcon
import com.glagolitsa.ui.profile.ProfileMenuSplitSecretIcon
import com.glagolitsa.ui.profile.ProfileMenuStarIcon
import com.glagolitsa.ui.profile.ProfileMenuStorageIcon
import com.glagolitsa.ui.profile.RecoveryVaultDialog
import com.glagolitsa.ui.profile.buildRecoveryVaultPlan
import com.glagolitsa.ui.profile.SecureHistoryDialog
import com.glagolitsa.ui.profile.ProfileMenuAppearanceIcon
import com.glagolitsa.ui.profile.ProfileMenuThemeIcon
import com.glagolitsa.ui.profile.ProfileMenuUpdateIcon
import com.glagolitsa.ui.profile.ProfileThemeToggle
import com.glagolitsa.ui.profile.color
import com.glagolitsa.ui.profile.rememberAvatarPicker
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.ui.profile.isPresencePrivacyTransient
import com.glagolitsa.ui.profile.isSameNetworkPrivacy
import com.glagolitsa.ui.profile.presencePrivacyUserMessage
import com.glagolitsa.ui.profile.savePresencePrivacyWithRetry
import com.glagolitsa.ui.theme.AppThemeController
import com.glagolitsa.ui.theme.AppThemeMode
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import com.glagolitsa.ui.i18n.AppLanguage
import com.glagolitsa.ui.i18n.AppLanguageController
import com.glagolitsa.ui.i18n.tr

/**
 * Экран «Профиль» / «Настройки» — визуализация по референсу
 * `e454fddf-2496-45a1-87c7-99cf210dd4d6.png`.
 */
@Composable
fun ProfileScreen(
    repository: MessengerRepository,
    localAuthenticator: LocalAuthenticator,
    onLoggedOut: () -> Unit,
    bottomContentPadding: Dp,
    onChatSelected: (Chat) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val user by SessionStore.user.collectAsState()
    val chats by repository.observeChats().collectAsState(initial = emptyList())
    // Never block the shell on network: open immediately when session has a user.
    // Spinner only while identity is still unknown (cold restore).
    var loading by remember(user?.id) { mutableStateOf(user == null) }
    var showAboutApp by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var showAppUpdate by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showCalls by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showAudioProcessing by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var updateDecision by remember { mutableStateOf<UpdateDecision?>(null) }
    var activeDeviceCount by remember { mutableStateOf<Int?>(null) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRecoveryVaultDialog by remember { mutableStateOf(false) }
    var recoveryKeySet by remember { mutableStateOf(false) }
    var passkeyReady by remember { mutableStateOf(false) }
    var recoveryLocalCreated by remember { mutableStateOf(false) }
    var recoveryCreatedKey by remember { mutableStateOf<String?>(null) }
    var recoveryVaultLoading by remember { mutableStateOf(false) }
    var recoveryVaultError by remember { mutableStateOf<String?>(null) }
    var pendingRecoveryChallenges by remember {
        mutableStateOf(emptyList<com.glagolitsa.model.TrustedRecoveryPendingItem>())
    }
    var backupLoading by remember { mutableStateOf(false) }
    var backupRecoveryKey by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var backupCloudInfo by remember { mutableStateOf<CloudBackupInfo?>(null) }
    var backupCloudInfoLoading by remember { mutableStateOf(false) }
    var secureHistoryEnabled by remember { mutableStateOf(false) }
    var secureCloudPresent by remember { mutableStateOf(false) }
    val secureRestoreHint by repository.secureHistoryRestoreAvailable.collectAsState()
    var logoutLoading by remember { mutableStateOf(false) }
    var statusEditorExpanded by remember { mutableStateOf(false) }
    var statusDraft by remember { mutableStateOf("") }
    var statusSaving by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var aboutEditorExpanded by remember { mutableStateOf(false) }
    var aboutDraft by remember { mutableStateOf("") }
    var aboutSaving by remember { mutableStateOf(false) }
    var aboutError by remember { mutableStateOf<String?>(null) }
    var avatarSaving by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var heroOrnament by remember { mutableStateOf(ProfileHeroOrnament.Default) }
    val themeToggleDark = AppThemeController.isDark
    /** Optimistic preview while upload runs (data:image from picker). */
    var localAvatarPreview by remember { mutableStateOf<String?>(null) }
    var presencePrivacy by remember { mutableStateOf(PresencePrivacySettings()) }
    var presencePrivacyLoaded by remember { mutableStateOf(false) }
    var presencePrivacySaving by remember { mutableStateOf(false) }
    var presencePrivacyError by remember { mutableStateOf<String?>(null) }
    var presencePrivacySaveJob by remember { mutableStateOf<Job?>(null) }
    var presencePrivacySaveGen by remember { mutableStateOf(0) }
    /** After user toggles, ignore late initial getPrivacy so ON/OFF is not clobbered. */
    var presencePrivacyUserEdited by remember { mutableStateOf(false) }
    var biometricUnlockEnabled by remember { mutableStateOf(false) }
    var biometricAvailability by remember { mutableStateOf(LocalAuthAvailability.Unknown) }
    var biometricSaving by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(user?.id) {
        localAvatarPreview = null
        avatarError = null
        val currentUserId = user?.id
        statusEditorExpanded = false
        statusError = null
        statusSaving = false
        aboutEditorExpanded = false
        aboutError = null
        aboutSaving = false
        statusDraft = SessionStore.user.value?.status.orEmpty()
        aboutDraft = SessionStore.user.value?.bio.orEmpty()
        presencePrivacyError = null
        biometricError = null
        biometricSaving = false

        // 1) Local shell first — profile must open even when API/refresh is stuck.
        if (currentUserId != null) {
            biometricUnlockEnabled = runCatching { repository.isBiometricUnlockEnabledForCurrentUser() }
                .getOrDefault(false)
            biometricAvailability = runCatching { localAuthenticator.availability() }
                .getOrDefault(LocalAuthAvailability.Unknown)
            heroOrnament = ProfileHeroOrnament.fromStorageValue(
                repository.loadProfileSetting(
                    key = ProfileHeroOrnament.storageKey(currentUserId),
                    defaultValue = ProfileHeroOrnament.Default.storageValue,
                ),
            )
            statusDraft = SessionStore.user.value?.status.orEmpty()
            aboutDraft = SessionStore.user.value?.bio.orEmpty()
            loading = false
        } else {
            heroOrnament = ProfileHeroOrnament.Default
            statusDraft = ""
            aboutDraft = ""
            biometricUnlockEnabled = false
            biometricAvailability = LocalAuthAvailability.Unknown
            // Keep spinner only until session publishes a user.
            loading = true
            return@LaunchedEffect
        }

        // 2) Network refresh is best-effort; switch stays interactive with defaults.
        presencePrivacyLoaded = true
        presencePrivacyUserEdited = false
        runCatching { repository.syncProfile() }
        runCatching { repository.presence.getPrivacy() }
            .onSuccess {
                // Never overwrite after the user has toggled (ON was getting reset).
                if (!presencePrivacyUserEdited && presencePrivacySaveJob?.isActive != true) {
                    presencePrivacy = it
                }
                presencePrivacyLoaded = true
            }
            .onFailure {
                presencePrivacyError = it.message ?: "Не удалось загрузить видимость статуса"
            }
        runCatching { repository.fetchClientUpdatePolicy() }
            .onSuccess { policy ->
                ClientFeatureFlags.update(policy.features)
                updateDecision = ClientUpdateEvaluator.evaluate(
                    local = AppRuntimeInfo.toClientAppIdentity(),
                    policy = policy,
                )
            }
        runCatching { repository.listOwnDevices() }
            .onSuccess { ownDevices ->
                activeDeviceCount = ownDevices.devices.count {
                    (it.device_status ?: "active").lowercase() == "active"
                }
            }
            .onFailure {
                activeDeviceCount = null
            }
    }

    LaunchedEffect(repository) {
        runCatching { repository.calls.refreshHistory() }
    }

    val accountLabel = user?.username?.let { "@$it" } ?: "—"
    val profileDisplayName = user?.display_name?.trim().orEmpty()
    val profileTextLines = StatusChannels.profileTextLines(
        status = user?.status,
        bio = user?.bio,
    )
    val profileStatus = profileTextLines.status.orEmpty()
    val profileAbout = profileTextLines.about.orEmpty()
    val avatarUrl = localAvatarPreview ?: user?.avatar_url
    val ownPresence = user?.presenceState() ?: UserPresence.Online
    // Network privacy only (presence service) — never derived from profile about.
    val statusVisible = StatusChannels.isNetworkStatusVisible(presencePrivacy)
    val profilePresenceColor = if (statusVisible) {
        ownPresence.color()
    } else {
        UserPresence.Offline.color()
    }

    fun updatePresenceVisibility(visible: Boolean) {
        val baseline = presencePrivacy
        val currentlyVisible = StatusChannels.isNetworkStatusVisible(baseline)
        // Duplicate tap with same target and no save in flight — no-op.
        if (currentlyVisible == visible && presencePrivacySaveJob?.isActive != true) return

        presencePrivacyUserEdited = true
        // Optimistic network privacy only; profile about is untouched.
        presencePrivacy = StatusChannels.privacyForNetworkVisible(visible)
        presencePrivacyError = null
        presencePrivacyLoaded = true

        // Latest tap wins — cancel must not revert via CancellationException.
        presencePrivacySaveJob?.cancel()
        val gen = presencePrivacySaveGen + 1
        presencePrivacySaveGen = gen
        presencePrivacySaving = true
        presencePrivacySaveJob = scope.launch {
            val request = StatusChannels.updatePrivacyRequest(visible)
            val expected = StatusChannels.privacyForNetworkVisible(visible)
            try {
                val outcome = savePresencePrivacyWithRetry(request) { req ->
                    repository.presence.updatePrivacy(req)
                }
                if (isSameNetworkPrivacy(presencePrivacy, expected)) {
                    if (outcome.isSuccess) {
                        presencePrivacy = outcome.settings ?: expected
                        presencePrivacyLoaded = true
                        presencePrivacyError = null
                    } else if (isPresencePrivacyTransient(outcome.error)) {
                        presencePrivacyError =
                            "Сервер не ответил вовремя. Настройка оставлена как есть, повторите позже."
                    } else {
                        presencePrivacy = baseline
                        presencePrivacyError = presencePrivacyUserMessage(outcome.error)
                    }
                }
            } finally {
                if (presencePrivacySaveGen == gen) {
                    presencePrivacySaving = false
                }
            }
        }
    }

    fun updateBiometricUnlock(enabled: Boolean) {
        if (biometricSaving || enabled == biometricUnlockEnabled) return
        biometricSaving = true
        biometricError = null
        scope.launch {
            if (!enabled) {
                runCatching { repository.setBiometricUnlockEnabledForCurrentUser(false) }
                    .onSuccess { saved ->
                        if (saved) biometricUnlockEnabled = false
                    }
                    .onFailure { biometricError = it.message ?: "Не удалось выключить вход по отпечатку" }
                biometricSaving = false
                return@launch
            }

            biometricAvailability = runCatching { localAuthenticator.availability() }
                .getOrDefault(LocalAuthAvailability.Unknown)
            if (biometricAvailability != LocalAuthAvailability.Available) {
                biometricError = biometricUnavailableMessage(biometricAvailability)
                biometricSaving = false
                return@launch
            }
            when (val result = localAuthenticator.authenticate("Подтвердите включение входа по отпечатку")) {
                LocalAuthResult.Success -> {
                    runCatching { repository.setBiometricUnlockEnabledForCurrentUser(true) }
                        .onSuccess { saved ->
                            if (saved) biometricUnlockEnabled = true
                        }
                        .onFailure { biometricError = it.message ?: "Не удалось включить вход по отпечатку" }
                }
                LocalAuthResult.Cancelled -> Unit
                is LocalAuthResult.Failed -> {
                    biometricError = result.message ?: "Не удалось подтвердить отпечаток"
                }
            }
            biometricSaving = false
        }
    }

    fun saveStatusNow() {
        if (statusSaving) return
        val current = SessionStore.user.value ?: return
        val normalizedStatus = ProfileAboutPolicy.normalize(statusDraft)
        statusDraft = normalizedStatus
        statusSaving = true
        statusError = null
        scope.launch {
            runCatching {
                repository.updateProfile(
                    ProfileUpdateInput(
                        displayName = current.display_name.orEmpty(),
                        position = current.position.orEmpty(),
                        status = normalizedStatus,
                        bio = current.bio.orEmpty(),
                        avatarUrl = current.avatar_url,
                        presence = current.presenceState().apiValue,
                    ),
                )
            }.onSuccess {
                statusEditorExpanded = false
                statusError = null
            }.onFailure {
                statusError = it.message ?: "Не удалось сохранить"
            }
            statusSaving = false
        }
    }

    fun saveAboutNow() {
        if (aboutSaving) return
        val current = SessionStore.user.value ?: return
        val normalizedAbout = ProfileAboutPolicy.normalize(aboutDraft)
        aboutDraft = normalizedAbout
        aboutSaving = true
        aboutError = null
        scope.launch {
            runCatching {
                repository.updateProfile(
                    ProfileUpdateInput(
                        displayName = current.display_name.orEmpty(),
                        position = current.position.orEmpty(),
                        status = current.status.orEmpty(),
                        bio = normalizedAbout,
                        avatarUrl = current.avatar_url,
                        presence = current.presenceState().apiValue,
                    ),
                )
            }.onSuccess {
                aboutEditorExpanded = false
                aboutError = null
            }.onFailure {
                aboutError = it.message ?: "Не удалось сохранить"
            }
            aboutSaving = false
        }
    }

    val hasStatusChanges = ProfileAboutPolicy.hasChanges(statusDraft, profileStatus)
    val hasAboutChanges = ProfileAboutPolicy.hasChanges(aboutDraft, profileAbout)

    val openAvatarPicker = rememberAvatarPicker { dataUrl ->
        if (dataUrl == null) {
            // User cancelled gallery — no error.
            return@rememberAvatarPicker
        }
        if (!dataUrl.startsWith("data:image")) {
            avatarError = "Не удалось обработать фото"
            return@rememberAvatarPicker
        }
        val current = SessionStore.user.value
        if (current == null) {
            avatarError = "Нужно войти в аккаунт"
            return@rememberAvatarPicker
        }
        localAvatarPreview = dataUrl
        avatarError = null
        avatarSaving = true
        scope.launch {
            runCatching {
                repository.updateProfile(
                    ProfileUpdateInput(
                        displayName = current.display_name.orEmpty(),
                        position = current.position.orEmpty(),
                        status = current.status.orEmpty(),
                        bio = current.bio.orEmpty(),
                        avatarUrl = dataUrl,
                        presence = current.presenceState().apiValue,
                    ),
                )
            }.onSuccess {
                localAvatarPreview = null // SessionStore now has server avatar
            }.onFailure {
                localAvatarPreview = null
                avatarError = it.message ?: "Не удалось сохранить аватар"
            }
            avatarSaving = false
        }
    }

    val updateBadge = remember(updateDecision) {
        ClientUpdateEvaluator.settingsBadge(updateDecision)
    }

    fun updateHeroOrnament(next: ProfileHeroOrnament) {
        val currentUserId = SessionStore.user.value?.id ?: return
        if (heroOrnament == next) return
        heroOrnament = next
        scope.launch {
            repository.saveProfileSetting(
                key = ProfileHeroOrnament.storageKey(currentUserId),
                value = next.storageValue,
            )
        }
    }

    val selectedLanguage = AppLanguageController.language
    val accountItems = listOf(
            ProfileMenuEntry(
                title = tr("Устройства", "Devices"),
                icon = { ProfileMenuDevicesIcon(tint = it) },
                onClick = { showDevices = true },
            ),
            ProfileMenuEntry(
                title = tr("Защищённая история", "Protected history"),
                badge = if (secureRestoreHint) "!" else null,
                badgeHighlight = secureRestoreHint,
                icon = { ProfileMenuLockIcon(tint = it) },
                onClick = {
                    backupError = null
                    backupRecoveryKey = null
                    showBackupDialog = true
                },
            ),
            ProfileMenuEntry(
                title = tr("Восстановление", "Recovery"),
                subtitle = tr("Доступ к аккаунту", "Account access"),
                badge = when {
                    pendingRecoveryChallenges.isNotEmpty() -> "!"
                    recoveryKeySet || recoveryLocalCreated -> null
                    else -> "NEW"
                },
                badgeHighlight = pendingRecoveryChallenges.isNotEmpty(),
                icon = { ProfileMenuSplitSecretIcon(tint = it) },
                onClick = {
                    showRecoveryVaultDialog = true
                },
            ),
        )

    val historyItems = listOf(
            ProfileMenuEntry(
                title = tr("Избранное", "Favorites"),
                icon = { ProfileMenuStarIcon(tint = it) },
                onClick = { showFavorites = true },
            ),
            ProfileMenuEntry(
                title = tr("Недавние звонки", "Recent calls"),
                icon = { ProfileMenuPhoneIcon(tint = it) },
                onClick = { showCalls = true },
            ),
        )

    /**
     * All membership channels (public + private).
     * Section is always shown (empty hint if none) so create → profile is discoverable.
     * Per-channel «show on profile» toggle can plug in later without moving the block.
     */
    val myChannels = remember(chats) {
        chats
            .filter { it.isChannel }
            .sortedBy { it.title.lowercase() }
    }
    val myChannelItems = myChannels.map { chat ->
            val kindLabel = when {
                chat.isPublicChannel && !chat.slug.isNullOrBlank() -> "@${chat.slug}"
                chat.isPublicChannel -> tr("Публичный", "Public")
                else -> tr("Личный", "Private")
            }
            // One subtitle line only — keep the row short (title + @slug / kind).
            ProfileMenuEntry(
                title = chat.title,
                subtitle = kindLabel,
                icon = { ProfileMenuChannelIcon(tint = it) },
                onClick = { onChatSelected(chat) },
            )
    }

    // Pull latest membership (incl. just-created channels) when opening profile.
    LaunchedEffect(user?.id) {
        if (user?.id != null) {
            runCatching { repository.syncChats() }
            recoveryLocalCreated = runCatching { repository.localAccountRecoveryCreated() }.getOrDefault(false)
            runCatching { repository.accountRecoveryStatus() }
                .onSuccess { status ->
                    recoveryKeySet = status.recovery_key_set
                    passkeyReady = status.passkey_ready
                    if (status.trusted_device_count > 0) {
                        activeDeviceCount = status.trusted_device_count
                    }
                }
            pendingRecoveryChallenges = runCatching { repository.pendingTrustedAccountRecoveries() }
                .getOrDefault(emptyList())
            if (pendingRecoveryChallenges.isNotEmpty()) {
                showRecoveryVaultDialog = true
            }
        }
    }

    val appItems = listOf(
            ProfileMenuEntry(
                title = tr("Тема", "Theme"),
                icon = { ProfileMenuThemeIcon(tint = it) },
                trailingContent = {
                    ProfileThemeToggle(
                        dark = themeToggleDark,
                        interactive = false,
                    )
                },
                onClick = {
                    val nextDark = !AppThemeController.isDark
                    AppThemeController.setDark(nextDark)
                    scope.launch {
                        repository.saveProfileSetting(
                            AppThemeMode.SETTINGS_KEY,
                            if (nextDark) AppThemeMode.Dark.storageKey else AppThemeMode.Light.storageKey,
                        )
                    }
                },
            ),
            ProfileMenuEntry(
                title = tr("Уведомления", "Notifications"),
                subtitle = tr("Сообщения, звонки, превью", "Messages, calls, and previews"),
                icon = { ProfileMenuBellIcon(tint = it) },
                onClick = { showNotifications = true },
            ),
            ProfileMenuEntry(
                title = tr("Шумоподавление", "Noise reduction"),
                subtitle = tr("Звонки и голосовые", "Calls and voice messages"),
                icon = { ProfileMenuNoiseIcon(tint = it) },
                onClick = { showAudioProcessing = true },
            ),
            ProfileMenuEntry(
                title = tr("Оформление", "Appearance"),
                trailing = heroOrnament.title,
                icon = { ProfileMenuAppearanceIcon(tint = it) },
                onClick = { showAppearance = true },
            ),
            ProfileMenuEntry(
                title = tr("Хранилище", "Storage"),
                subtitle = tr("Кеш медиа и вложений", "Media and attachment cache"),
                icon = { ProfileMenuStorageIcon(tint = it) },
                onClick = { showStorage = true },
            ),
            ProfileMenuEntry(
                title = tr("Язык приложения", "App language"),
                trailing = selectedLanguage.displayName,
                icon = { ProfileMenuGlobeIcon(tint = it) },
                onClick = { showLanguagePicker = true },
            ),
            ProfileMenuEntry(
                title = tr("Обновления", "Updates"),
                badge = updateBadge.label,
                badgeHighlight = updateBadge.highlight,
                trailing = AppRuntimeInfo.versionName,
                icon = { ProfileMenuUpdateIcon(tint = it) },
                onClick = { showAppUpdate = true },
            ),
        )

    val supportItems = listOf(
            ProfileMenuEntry(
                title = tr("Помощь и поддержка", "Help and support"),
                icon = { ProfileMenuHelpIcon(tint = it) },
                onClick = { showSupport = true },
            ),
            ProfileMenuEntry(
                title = tr("О приложении", "About"),
                icon = { ProfileMenuInfoIcon(tint = it) },
                trailingContent = {
                    AboutGlagolitsaMark()
                },
                onClick = { showAboutApp = true },
            ),
        )

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(tr("Язык приложения", "App language")) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        TextButton(
                            onClick = {
                                AppLanguageController.applyLanguage(language)
                                showLanguagePicker = false
                                scope.launch {
                                    repository.saveProfileSetting(AppLanguage.SETTINGS_KEY, language.storageKey)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (language == selectedLanguage) "✓ ${language.displayName}" else language.displayName,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(tr("Отмена", "Cancel"))
                }
            },
        )
    }

    fun performLogout() {
        if (logoutLoading) return
        logoutLoading = true
        scope.launch {
            runCatching { repository.logout() }
            onLoggedOut()
            logoutLoading = false
        }
    }

    if (showAboutApp) {
        AppBackHandler {
            showAboutApp = false
        }
        AboutAppScreen(
            bottomContentPadding = bottomContentPadding,
            onBack = { showAboutApp = false },
        )
        return
    }
    if (showSupport) {
        AppBackHandler {
            showSupport = false
        }
        HelpSupportScreen(
            bottomContentPadding = bottomContentPadding,
            onBack = { showSupport = false },
        )
        return
    }
    if (showAppUpdate) {
        fun closeAppUpdate() {
            showAppUpdate = false
            // Refresh badge after user checked / dismissed.
            scope.launch {
                runCatching { repository.fetchClientUpdatePolicy() }
                    .onSuccess { policy ->
                        ClientFeatureFlags.update(policy.features)
                        updateDecision = ClientUpdateEvaluator.evaluate(
                            local = AppRuntimeInfo.toClientAppIdentity(),
                            policy = policy,
                        )
                    }
            }
        }
        AppBackHandler {
            closeAppUpdate()
        }
        AppUpdateSettingsScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onDecisionChanged = { updateDecision = it },
            onBack = ::closeAppUpdate,
        )
        return
    }
    if (showFavorites) {
        AppBackHandler {
            showFavorites = false
        }
        FavoritesScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = { showFavorites = false },
        )
        return
    }
    if (showCalls) {
        AppBackHandler {
            showCalls = false
        }
        CallsScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = { showCalls = false },
        )
        return
    }
    if (showDevices) {
        fun closeDevices() {
            showDevices = false
            scope.launch {
                runCatching { repository.listOwnDevices() }
                    .onSuccess { ownDevices ->
                        activeDeviceCount = ownDevices.devices.count {
                            (it.device_status ?: "active").lowercase() == "active"
                        }
                    }
            }
        }
        AppBackHandler {
            closeDevices()
        }
        DevicesScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = ::closeDevices,
        )
        return
    }
    if (showStorage) {
        AppBackHandler {
            showStorage = false
        }
        MediaStorageScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = { showStorage = false },
        )
        return
    }
    if (showNotifications) {
        AppBackHandler {
            showNotifications = false
        }
        NotificationSettingsScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = { showNotifications = false },
        )
        return
    }
    if (showAudioProcessing) {
        AppBackHandler {
            showAudioProcessing = false
        }
        AudioProcessingSettingsScreen(
            repository = repository,
            bottomContentPadding = bottomContentPadding,
            onBack = { showAudioProcessing = false },
        )
        return
    }
    if (showAppearance) {
        AppBackHandler {
            showAppearance = false
        }
        ProfileAppearanceScreen(
            selected = heroOrnament,
            bottomContentPadding = bottomContentPadding,
            onBack = { showAppearance = false },
            onOrnamentSelected = ::updateHeroOrnament,
        )
        return
    }

    LaunchedEffect(showBackupDialog) {
        if (!showBackupDialog) return@LaunchedEffect
        backupCloudInfoLoading = true
        backupError = null
        secureHistoryEnabled = runCatching { repository.secureHistoryEnabled() }.getOrDefault(false)
        backupCloudInfo = runCatching { repository.fetchCloudBackupInfo() }.getOrNull()
        // Cloud "present" for Secure Backup only if GLSBR1 — fetchCloudBackupInfo only checks any snapshot.
        secureCloudPresent = backupCloudInfo != null || secureRestoreHint
        backupCloudInfoLoading = false
    }

    if (showBackupDialog) {
        SecureHistoryDialog(
            loading = backupLoading,
            enabled = secureHistoryEnabled,
            cloudPresent = secureCloudPresent || secureRestoreHint,
            cloudInfoLoading = backupCloudInfoLoading,
            shownRecoveryKey = backupRecoveryKey,
            error = backupError,
            preferRestore = secureRestoreHint,
            onDismiss = { showBackupDialog = false },
            onEnable = {
                scope.launch {
                    backupLoading = true
                    backupError = null
                    runCatching { repository.enableSecureHistoryBackup() }
                        .onSuccess { key ->
                            backupRecoveryKey = key
                            secureHistoryEnabled = true
                            secureCloudPresent = true
                            SecureClipboard.copyWithAutoClear(
                                label = "secure-history-recovery-key",
                                text = key,
                            )
                        }
                        .onFailure { backupError = it.message }
                    backupLoading = false
                }
            },
            onRestore = { recoveryKey ->
                scope.launch {
                    backupLoading = true
                    backupError = null
                    runCatching { repository.restoreSecureHistory(recoveryKey) }
                        .onSuccess {
                            secureHistoryEnabled = true
                            showBackupDialog = false
                        }
                        .onFailure { backupError = it.message }
                    backupLoading = false
                }
            },
        )
    }

    if (showRecoveryVaultDialog) {
        AppBackHandler {
            showRecoveryVaultDialog = false
        }
        RecoveryVaultDialog(
            plan = buildRecoveryVaultPlan(
                localShardReady = recoveryLocalCreated || recoveryCreatedKey != null,
                cloudShardReady = recoveryKeySet,
                trustedDeviceCount = activeDeviceCount ?: 0,
                passkeyFallbackReady = passkeyReady,
            ),
            createdKey = recoveryCreatedKey,
            pendingChallenges = pendingRecoveryChallenges,
            loading = recoveryVaultLoading,
            error = recoveryVaultError,
            onCreatePasskey = {
                recoveryVaultLoading = true
                recoveryVaultError = null
                scope.launch {
                    runCatching { repository.registerPasskey(localAuthenticator) }
                        .onSuccess { passkeyReady = true }
                        .onFailure { recoveryVaultError = it.message }
                    recoveryVaultLoading = false
                }
            },
            onCreateKey = {
                recoveryVaultLoading = true
                recoveryVaultError = null
                scope.launch {
                    runCatching { repository.setupAccountRecoveryKey() }
                        .onSuccess { key ->
                            recoveryCreatedKey = key
                            recoveryLocalCreated = true
                            recoveryKeySet = true
                        }
                        .onFailure { recoveryVaultError = it.message }
                    recoveryVaultLoading = false
                }
            },
            onApprove = { challengeId ->
                recoveryVaultLoading = true
                recoveryVaultError = null
                scope.launch {
                    runCatching { repository.approveTrustedAccountRecovery(challengeId) }
                        .onSuccess {
                            pendingRecoveryChallenges = pendingRecoveryChallenges.filterNot {
                                it.challenge_id == challengeId
                            }
                        }
                        .onFailure { recoveryVaultError = it.message }
                    recoveryVaultLoading = false
                }
            },
            onDismiss = { showRecoveryVaultDialog = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
    ) {
        ProfileTopBar(
            logoutLoading = logoutLoading,
            onLogout = ::performLogout,
        )

        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
        ) {
            ProfileHeroCard(
                name = if (profileDisplayName.isNotBlank()) profileDisplayName else accountLabel,
                subtitle = accountLabel.takeIf { profileDisplayName.isNotBlank() },
                presenceColor = profilePresenceColor,
                ornament = heroOrnament,
                onAvatarClick = {
                    if (!avatarSaving) openAvatarPicker()
                },
                avatarHint = if (avatarSaving) tr("Сохраняем фото…", "Saving photo…") else null,
                avatar = {
                    Box(contentAlignment = Alignment.Center) {
                        ProfileAvatar(
                            avatarUrl = avatarUrl,
                            fallbackLabel = user?.avatarLabel() ?: "?",
                            size = 128.dp,
                            presenceColor = profilePresenceColor,
                        )
                        if (avatarSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = GlagolitsaColors.AccentRed,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                },
                belowName = {
                    HeroProfileStatusField(
                        status = profileStatus,
                        statusDraft = statusDraft,
                        expanded = statusEditorExpanded,
                        hasChanges = hasStatusChanges,
                        saving = statusSaving,
                        error = statusError,
                        onToggleExpanded = {
                            if (!statusEditorExpanded) {
                                statusDraft = profileStatus
                                statusError = null
                            }
                            statusEditorExpanded = !statusEditorExpanded
                        },
                        onValueChange = {
                            statusDraft = it.take(ProfileTextMaxLength)
                            statusError = null
                        },
                        onSave = ::saveStatusNow,
                    )
                },
            )

            avatarError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }

            PresenceVisibilityCard(
                visibilityCaption = if (statusVisible) {
                    tr("Сетевой статус виден контактам", "Your online status is visible to contacts")
                } else {
                    tr("Сетевой статус скрыт от других", "Your online status is hidden")
                },
                visible = statusVisible,
                loading = !presencePrivacyLoaded,
                error = presencePrivacyError,
                onVisibleChange = ::updatePresenceVisibility,
            )

            BiometricUnlockCard(
                enabled = biometricUnlockEnabled,
                loading = biometricSaving,
                availability = biometricAvailability,
                error = biometricError,
                onEnabledChange = ::updateBiometricUnlock,
            )

            // Slight air before menu sections.
            Spacer(modifier = Modifier.height(GlagolitsaSpacing.xs))

            ProfileAboutCard(
                about = profileAbout,
                aboutDraft = aboutDraft,
                expanded = aboutEditorExpanded,
                hasChanges = hasAboutChanges,
                saving = aboutSaving,
                error = aboutError,
                onToggleExpanded = {
                    if (!aboutEditorExpanded) {
                        aboutDraft = profileAbout
                        aboutError = null
                    }
                    aboutEditorExpanded = !aboutEditorExpanded
                },
                onValueChange = {
                    aboutDraft = it.take(ProfileTextMaxLength)
                    aboutError = null
                },
                onSave = ::saveAboutNow,
            )

            // After identity, before settings — all your channels.
            if (myChannelItems.isNotEmpty()) {
                ProfileMenuCard(
                    title = tr("Мои каналы", "My channels"),
                    items = myChannelItems,
                    compact = true,
                )
            }

            ProfileMenuCard(
                title = tr("Аккаунт и безопасность", "Account and security"),
                items = accountItems,
                layout = ProfileMenuLayout.IconGrid,
            )
            ProfileMenuCard(title = tr("История", "History"), items = historyItems)
            ProfileMenuCard(title = tr("Приложение", "Application"), items = appItems)
            ProfileMenuCard(title = tr("Поддержка", "Support"), items = supportItems)
        }

    }
}

private const val ProfileTextMaxLength = ProfileAboutPolicy.MAX_LENGTH

/**
 * Compact profile status near the avatar. Free-text status only;
 * network presence and profile "about" live in separate controls.
 */
@Composable
private fun HeroProfileStatusField(
    status: String,
    statusDraft: String,
    expanded: Boolean,
    hasChanges: Boolean,
    saving: Boolean,
    error: String?,
    onToggleExpanded: () -> Unit,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val displayStatus = status.trim()
    val isEmpty = displayStatus.isEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GlagolitsaInput(
                    value = statusDraft,
                    onValueChange = onValueChange,
                    placeholder = tr("Короткая фраза", "Short status"),
                    size = GlagolitsaInputSize.Small,
                    variant = GlagolitsaInputVariant.Multiline,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileFieldSyncFooter(
                    syncLabel = when {
                        saving -> tr("Сохраняем…", "Saving…")
                        !error.isNullOrBlank() -> error
                        else -> ""
                    },
                    syncColor = if (!error.isNullOrBlank()) {
                        GlagolitsaColors.AccentRed
                    } else {
                        GlagolitsaColors.TextTertiary
                    },
                    showSaving = saving,
                    showSave = hasChanges && !saving,
                    counter = "${statusDraft.length}/$ProfileTextMaxLength",
                    onSave = onSave,
                    onCollapse = onToggleExpanded,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlagolitsaColors.Background950.copy(alpha = 0.42f))
                    .clickable(onClick = onToggleExpanded)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isEmpty) {
                            tr("Добавить короткую фразу", "Add a short status")
                        } else {
                            tr("Редактировать короткую фразу", "Edit short status")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (isEmpty) tr("Короткая фраза", "Short status") else displayStatus,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = if (isEmpty) FontStyle.Italic else FontStyle.Normal,
                        lineHeight = 18.sp,
                    ),
                    color = if (isEmpty) {
                        GlagolitsaColors.OrnamentGold.copy(alpha = 0.78f)
                    } else {
                        GlagolitsaColors.OrnamentGold.copy(alpha = 0.94f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProfileAboutCard(
    about: String,
    aboutDraft: String,
    expanded: Boolean,
    hasChanges: Boolean,
    saving: Boolean,
    error: String?,
    onToggleExpanded: () -> Unit,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val isEmpty = about.isBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProfileSectionOrnamentHeader(title = tr("О себе", "About"))
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GlagolitsaInput(
                    value = aboutDraft,
                    onValueChange = onValueChange,
                    placeholder = tr("Расскажите о себе", "Tell people about yourself"),
                    size = GlagolitsaInputSize.Small,
                    variant = GlagolitsaInputVariant.Multiline,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileFieldSyncFooter(
                    syncLabel = when {
                        saving -> tr("Сохраняем…", "Saving…")
                        !error.isNullOrBlank() -> error
                        else -> ""
                    },
                    syncColor = if (!error.isNullOrBlank()) {
                        GlagolitsaColors.AccentRed
                    } else {
                        GlagolitsaColors.TextTertiary
                    },
                    showSaving = saving,
                    showSave = hasChanges && !saving,
                    counter = "${aboutDraft.length}/$ProfileTextMaxLength",
                    onSave = onSave,
                    onCollapse = onToggleExpanded,
                )
            }
        } else {
            Text(
                text = if (isEmpty) tr("Не указано", "Not specified") else about,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = if (isEmpty) FontStyle.Italic else FontStyle.Normal,
                ),
                color = if (isEmpty) {
                    GlagolitsaColors.TextTertiary
                } else {
                    GlagolitsaColors.TextPrimary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlagolitsaColors.Surface800.copy(alpha = 0.58f))
                    .clickable(onClick = onToggleExpanded)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isEmpty) {
                            tr("Добавить информацию о себе", "Add information about yourself")
                        } else {
                            tr("Редактировать информацию о себе", "Edit information about yourself")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ◇ + gold title + soft hairline — same language as profile menu sections. */
@Composable
private fun ProfileSectionOrnamentHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "◇",
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.AccentRed.copy(alpha = 0.9f),
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.88f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            GlagolitsaColors.AccentRed.copy(alpha = 0.72f),
                            GlagolitsaColors.AccentRed.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/** Thin sync / save row under a field — never competes with body text. */
@Composable
private fun ProfileFieldSyncFooter(
    syncLabel: String,
    syncColor: Color,
    showSaving: Boolean,
    showSave: Boolean,
    counter: String?,
    onSave: () -> Unit,
    onCollapse: (() -> Unit)?,
) {
    val hasLeft = syncLabel.isNotBlank() || showSaving
    val hasRight = showSave || counter != null || onCollapse != null
    if (!hasLeft && !hasRight) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (showSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = GlagolitsaColors.AccentRed,
                    strokeWidth = 1.4.dp,
                )
            }
            if (syncLabel.isNotBlank()) {
                Text(
                    text = syncLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = syncColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (counter != null && !showSave) {
                Text(
                    text = counter,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.TextTertiary,
                )
            }
            if (onCollapse != null) {
                Text(
                    text = tr("Свернуть", "Collapse"),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.TextTertiary,
                    modifier = Modifier.clickable(onClick = onCollapse),
                )
            }
            if (showSave) {
                Text(
                    text = tr("Сохранить", "Save"),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.OrnamentGold,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onSave),
                )
            }
        }
    }
}

/**
 * Compact network-privacy toggle.
 * Presence ring lives only on the hero avatar — no second face here.
 */
@Composable
private fun PresenceVisibilityCard(
    /** Network-privacy caption only — never profile about text. */
    visibilityCaption: String,
    visible: Boolean,
    loading: Boolean,
    error: String?,
    onVisibleChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.md, vertical = GlagolitsaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("Сетевой статус", "Online status"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = visibilityCaption,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextSecondary,
                    )
                }
                // Ring on hero avatar reflects visibility; this is the control only.
                GlagolitsaSwitch(
                    checked = visible,
                    enabled = !loading,
                    onCheckedChange = onVisibleChange,
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }
        }
    }
}

@Composable
private fun BiometricUnlockCard(
    enabled: Boolean,
    loading: Boolean,
    availability: LocalAuthAvailability,
    error: String?,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.md, vertical = GlagolitsaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("Вход по отпечатку", "Fingerprint sign-in"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = biometricSettingCaption(enabled, availability),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextSecondary,
                    )
                }
                GlagolitsaSwitch(
                    checked = enabled,
                    enabled = !loading,
                    onCheckedChange = onEnabledChange,
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }
        }
    }
}

private fun biometricSettingCaption(enabled: Boolean, availability: LocalAuthAvailability): String =
    when {
        enabled -> tr("Сохраненная сессия откроется после отпечатка", "Your saved session will open after fingerprint verification")
        availability == LocalAuthAvailability.Available -> tr("Включите для этого аккаунта", "Enable for this account")
        else -> biometricUnavailableMessage(availability)
    }

private fun biometricUnavailableMessage(availability: LocalAuthAvailability): String =
    when (availability) {
        LocalAuthAvailability.NoBiometricEnrolled -> tr("Добавьте отпечаток в настройках телефона", "Add a fingerprint in your device settings")
        LocalAuthAvailability.Unsupported -> tr("На этом устройстве отпечаток недоступен", "Fingerprint authentication is unavailable on this device")
        LocalAuthAvailability.HardwareUnavailable -> tr("Датчик отпечатка временно недоступен", "The fingerprint sensor is temporarily unavailable")
        LocalAuthAvailability.SecurityUpdateRequired -> tr("Требуется обновление безопасности", "A security update is required")
        LocalAuthAvailability.Unknown -> tr("Статус датчика не определен", "Fingerprint status is unknown")
        LocalAuthAvailability.Available -> tr("Доступно", "Available")
    }

@Composable
private fun FavoritesScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<FavoriteMessageItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { repository.listFavoriteMessages() }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "Не удалось загрузить избранное" }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = "Избранное",
            actions = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
        )

        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            if (error != null) {
                FavoritesStateCard(
                    title = "Не удалось загрузить избранное",
                    message = error ?: "Попробуйте снова.",
                )
            } else if (items.isEmpty()) {
                FavoritesStateCard(
                    title = "Пока пусто",
                    message = "Добавляйте сообщения и файлы в избранное из меню в чате.",
                )
            } else {
                FavoritesListCard(
                    items = items,
                    onRemove = { messageId ->
                        items = items.filterNot { it.message.id == messageId }
                        scope.launch {
                            runCatching { repository.removeMessageFromFavorites(messageId) }
                                .onFailure {
                                    error = it.message ?: "Не удалось удалить из избранного"
                                    runCatching { repository.listFavoriteMessages() }
                                        .onSuccess { items = it }
                                }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FavoritesStateCard(
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun FavoritesListCard(
    items: List<FavoriteMessageItem>,
    onRemove: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                FavoriteMessageRow(
                    item = item,
                    onRemove = onRemove,
                )
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = GlagolitsaColors.DividerSubtle,
                        modifier = Modifier.padding(start = 56.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteMessageRow(
    item: FavoriteMessageItem,
    onRemove: (String) -> Unit,
) {
    val presentation = remember(item) { FavoriteMessagesLogic.presentation(item) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value != SwipeToDismissBoxValue.StartToEnd },
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove(item.message.id)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlagolitsaColors.StatusError.copy(alpha = 0.18f))
                    .padding(horizontal = GlagolitsaSpacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Удалить",
                    style = MaterialTheme.typography.labelLarge,
                    color = GlagolitsaColors.StatusError,
                )
            }
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = GlagolitsaColors.SurfacePanel,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
            ) {
                Text(
                    text = presentation.icon,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlagolitsaColors.TextPrimary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = presentation.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlagolitsaColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = presentation.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf<List<UserDevice>>(emptyList()) }
    var currentDeviceId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        loading = true
        error = null
        runCatching { repository.listOwnDevices() }
            .onSuccess { ownDevices ->
                currentDeviceId = ownDevices.currentDeviceId
                devices = ownDevices.devices
            }
            .onFailure {
                error = it.message ?: tr("Не удалось загрузить устройства", "Failed to load devices")
            }
        loading = false
    }

    val activeCount = devices.count {
        (it.device_status ?: "active").lowercase() == "active"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = tr("Устройства", "Devices"),
            subtitle = if (!loading && error == null) {
                tr("Активных: $activeCount из ${devices.size}", "Active: $activeCount of ${devices.size}")
            } else null,
            actions = {
                AppTopBarTextAction(text = tr("Обновить", "Refresh"), onClick = { refreshTick += 1 })
                AppTopBarTextAction(text = tr("Назад", "Back"), onClick = onBack)
            },
        )

        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
        ) {
            if (error != null) {
                DevicesStateCard(
                    title = tr("Не удалось загрузить список", "Failed to load devices"),
                    message = error ?: tr(
                        "Проверьте подключение и попробуйте снова.",
                        "Check your connection and try again.",
                    ),
                    actionText = tr("Повторить", "Retry"),
                    onAction = { refreshTick += 1 },
                )
            } else if (devices.isEmpty()) {
                DevicesStateCard(
                    title = tr("Устройств пока нет", "No devices yet"),
                    message = tr(
                        "После регистрации текущего телефона он появится в этом списке.",
                        "Your current phone will appear here after it is registered.",
                    ),
                    actionText = tr("Обновить", "Refresh"),
                    onAction = { refreshTick += 1 },
                )
            } else {
                DevicesSummaryCard(
                    activeCount = activeCount,
                    totalCount = devices.size,
                    currentDeviceId = currentDeviceId,
                )
                DevicesListCard(
                    devices = devices,
                    currentDeviceId = currentDeviceId,
                )
            }
        }
    }
}

@Composable
private fun DevicesStateCard(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextTertiary,
            )
            GlagolitsaButton(
                onClick = onAction,
                style = GlagolitsaButtonStyle.Secondary,
                size = GlagolitsaButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun DevicesSummaryCard(
    activeCount: Int,
    totalCount: Int,
    currentDeviceId: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr("Список устройств", "Device list"),
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            AboutInfoRow(label = tr("Активные", "Active"), value = activeCount.toString())
            AboutInfoRow(label = tr("Всего", "Total"), value = totalCount.toString())
            AboutInfoRow(
                label = tr("Это устройство", "This device"),
                value = currentDeviceId?.shortDeviceValue() ?: "-",
            )
        }
    }
}

@Composable
private fun DevicesListCard(
    devices: List<UserDevice>,
    currentDeviceId: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            devices.forEachIndexed { index, device ->
                DeviceRow(
                    device = device,
                    isCurrent = device.device_id == currentDeviceId,
                )
                if (index < devices.lastIndex) {
                    HorizontalDivider(
                        color = GlagolitsaColors.DividerSubtle,
                        modifier = Modifier.padding(start = 64.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: UserDevice,
    isCurrent: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProfileMenuDevicesIcon(tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCurrent) {
                        tr("Это устройство", "This device")
                    } else {
                        tr("Устройство", "Device") + " ${device.device_id.shortDeviceValue()}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.device_id,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DeviceStatusChip(status = device.device_status)
        }

        DeviceDetail(label = "Registration ID", value = device.registration_id.toString())
        DeviceDetail(label = "Mailbox", value = device.mailbox_token.shortSecretValue())
        DeviceDetail(label = "Identity key", value = device.identity_public_key.shortSecretValue())
    }
}

@Composable
private fun DeviceStatusChip(status: String?) {
    val normalized = status?.lowercase() ?: "active"
    val color = when (normalized) {
        "active" -> GlagolitsaColors.StatusSuccess
        "pending" -> GlagolitsaColors.StatusWarning
        "revoked" -> GlagolitsaColors.AccentRedText
        else -> GlagolitsaColors.TextTertiary
    }
    val label = when (normalized) {
        "active" -> tr("Активно", "Active")
        "pending" -> tr("Ожидает", "Pending")
        "revoked" -> tr("Отозвано", "Revoked")
        else -> normalized
    }
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.16f), shape = GlagolitsaShapes.xs)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun DeviceDetail(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String.shortDeviceValue(): String =
    if (length <= 12) this else "${take(6)}...${takeLast(6)}"

private fun String.shortSecretValue(): String =
    if (length <= 16) this else "${take(8)}...${takeLast(8)}"

@Composable
private fun MediaStorageScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<AttachmentCacheStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionInFlight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun refreshStats() {
        loading = true
        error = null
        scope.launch {
            runCatching { repository.mediaCacheStats() }
                .onSuccess { stats = it }
                .onFailure { error = it.message ?: "Не удалось загрузить хранилище" }
            loading = false
        }
    }

    LaunchedEffect(repository) {
        refreshStats()
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить кеш медиа?") },
            text = {
                Text(
                    "Будут удалены локальные encrypted blobs и thumbnails. Сообщения останутся в истории.",
                    color = GlagolitsaColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        actionInFlight = true
                        scope.launch {
                            runCatching { repository.clearMediaCache() }
                                .onSuccess { stats = repository.mediaCacheStats() }
                                .onFailure { error = it.message ?: "Не удалось очистить кеш" }
                            actionInFlight = false
                        }
                    },
                ) {
                    Text("Очистить", color = GlagolitsaColors.AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Отмена", color = GlagolitsaColors.TextPrimary)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = "Хранилище",
            actions = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = GlagolitsaShapes.md,
            colors = CardDefaults.cardColors(containerColor = GlagolitsaColors.SurfacePanel),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Кеш медиа",
                    style = MaterialTheme.typography.titleMedium,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = GlagolitsaColors.AccentRed,
                        strokeWidth = 2.dp,
                    )
                } else {
                    val current = stats
                    AboutInfoRow(label = "Всего", value = formatMediaCacheBytes(current?.totalBytes ?: 0))
                    AboutInfoRow(label = "Файлы", value = (current?.fileCount ?: 0).toString())
                    AboutInfoRow(label = "Вложения", value = formatMediaCacheBytes(current?.encryptedBytes ?: 0))
                    AboutInfoRow(label = "Миниатюры", value = formatMediaCacheBytes(current?.thumbnailBytes ?: 0))
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.AccentRed,
                    )
                }
            }
        }

        GlagolitsaButton(
            onClick = { refreshStats() },
            enabled = !loading && !actionInFlight,
            modifier = Modifier.fillMaxWidth(),
            style = GlagolitsaButtonStyle.Secondary,
            size = GlagolitsaButtonSize.Medium,
        ) {
            Text("Обновить")
        }
        GlagolitsaButton(
            onClick = {
                actionInFlight = true
                error = null
                scope.launch {
                    runCatching { repository.trimMediaCache(AttachmentRetentionPolicy()) }
                        .onSuccess { stats = repository.mediaCacheStats() }
                        .onFailure { error = it.message ?: "Не удалось оптимизировать кеш" }
                    actionInFlight = false
                }
            },
            enabled = !loading && !actionInFlight,
            modifier = Modifier.fillMaxWidth(),
            style = GlagolitsaButtonStyle.Secondary,
            size = GlagolitsaButtonSize.Medium,
        ) {
            Text("Оптимизировать кеш")
        }
        GlagolitsaButton(
            onClick = { showClearConfirm = true },
            enabled = !loading && !actionInFlight && (stats?.fileCount ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
            style = GlagolitsaButtonStyle.Secondary,
            size = GlagolitsaButtonSize.Medium,
        ) {
            Text("Очистить кеш")
        }
    }
}

@Composable
private fun ProfileAppearanceScreen(
    selected: ProfileHeroOrnament,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onOrnamentSelected: (ProfileHeroOrnament) -> Unit,
) {
    val ornamentItems = ProfileHeroOrnament.entries.map { ornament ->
        ProfileMenuEntry(
            title = ornament.title,
            trailing = if (ornament == selected) "Выбрано" else null,
            icon = {
                ProfileHeroOrnamentPreview(
                    ornament = ornament,
                    alpha = if (ornament == selected) 1f else 0.62f,
                    modifier = Modifier.size(28.dp),
                )
            },
            onClick = { onOrnamentSelected(ornament) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = "Оформление",
            actions = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
        )

        ProfileMenuCard(
            title = "Орнамент профиля",
            items = ornamentItems,
        )
    }
}

private fun formatMediaCacheBytes(bytes: Long): String {
    val mb = 1024L * 1024L
    val kb = 1024L
    return when {
        bytes >= mb -> "${bytes / mb} МБ"
        bytes >= kb -> "${bytes / kb} КБ"
        else -> "$bytes Б"
    }
}

@Composable
private fun AboutGlagolitsaMark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(GlagolitsaColors.Surface700.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Ⰳ",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.OrnamentGold,
            )
        }
        Text(
            text = tr("Глаголица", "Glagolitsa"),
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextTertiary,
        )
    }
}

@Composable
private fun AboutAppInfoCard() {
    val versionLabel = buildString {
        append(AppRuntimeInfo.versionName.ifBlank { "—" })
        if (AppRuntimeInfo.versionCode > 0) {
            append(" · ")
            append(AppRuntimeInfo.versionCode)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = GlagolitsaShapes.md,
        colors = CardDefaults.cardColors(
            containerColor = GlagolitsaColors.SurfacePanel,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr("О приложении", "About"),
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            AboutInfoRow(label = tr("Название", "Name"), value = tr("Глаголица", "Glagolitsa"))
            AboutInfoRow(label = tr("Версия", "Version"), value = versionLabel)
            AboutInfoRow(label = tr("Платформа", "Platform"), value = aboutPlatformLabel(AppRuntimeInfo.platform))
            AboutInfoRow(label = tr("Шифрование", "Encryption"), value = aboutEncryptionLabel(AppRuntimeInfo.platform))
            AboutInfoRow(label = tr("Поддержка", "Support"), value = AboutAppSupportEmail)
            HorizontalDivider(color = GlagolitsaColors.DividerSubtle)
            Text(
                text = "© 2026 Svetlana Zavatskaia",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )
            Text(
                text = "Glagolitsa · github.com/LanaZet/glagolitsa-mobile",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }
    }
}

private const val AboutAppSupportEmail = "support@glagolitsa.app"

private fun aboutPlatformLabel(platform: ClientPlatform): String =
    when (platform) {
        ClientPlatform.ANDROID -> "Android"
        ClientPlatform.IOS -> "iOS"
        ClientPlatform.DESKTOP -> "Desktop"
    }

private fun aboutEncryptionLabel(platform: ClientPlatform): String =
    when (platform) {
        ClientPlatform.ANDROID -> tr("Сквозное (Signal Protocol)", "End-to-end (Signal Protocol)")
        // iOS/desktop crypto is still catching up — don't over-claim full Signal parity.
        ClientPlatform.IOS -> tr("Сквозное (в развитии)", "End-to-end (in development)")
        ClientPlatform.DESKTOP -> tr("Сквозное (в развитии)", "End-to-end (in development)")
    }

@Composable
private fun AboutAppScreen(
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = tr("О приложении", "About"),
            actions = {
                AppTopBarTextAction(text = tr("Назад", "Back"), onClick = onBack)
            },
        )

        AboutAppInfoCard()
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextPrimary,
        )
    }
}

@Composable
private fun HelpSupportScreen(
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
    ) {
        AppTopBar(
            title = "Помощь и поддержка",
            actions = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = GlagolitsaShapes.md,
            colors = CardDefaults.cardColors(
                containerColor = GlagolitsaColors.SurfacePanel,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Чем можем помочь",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlagolitsaColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                AboutInfoRow(label = "Почта поддержки", value = AboutAppSupportEmail)
                AboutInfoRow(label = "Время ответа", value = "Обычно до 24 часов")
                AboutInfoRow(
                    label = "Версия приложения",
                    value = "${AppRuntimeInfo.versionName} · ${AppRuntimeInfo.versionCode}",
                )
                HorizontalDivider(color = GlagolitsaColors.DividerSubtle)
                Text(
                    text = "Перед обращением приложите скриншот, модель устройства и версию " +
                        "приложения — так мы решим вопрос быстрее.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun ProfileTopBar(
    logoutLoading: Boolean,
    onLogout: () -> Unit,
) {
    AppTopBar(
        title = tr("Профиль", "Profile"),
        modifier = Modifier.padding(bottom = GlagolitsaSpacing.lg),
        actions = {
            AppTopBarTextAction(
                text = if (logoutLoading) tr("Выходим…", "Signing out…") else tr("Выйти", "Sign out"),
                onClick = onLogout,
                enabled = !logoutLoading,
                color = GlagolitsaColors.AccentRed,
            )
        },
    )
}
