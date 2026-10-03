// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import kotlin.concurrent.Volatile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.ClientUpdateDownloadSession
import com.glagolitsa.model.ClientUpdateDownloadState
import com.glagolitsa.model.ClientUpdateEvaluator
import com.glagolitsa.model.ClientUpdateInstallLogic
import com.glagolitsa.model.ClientUpdatePromptPolicy
import com.glagolitsa.model.UpdateDecision
import com.glagolitsa.platform.AppRuntimeInfo
import com.glagolitsa.platform.toClientAppIdentity
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Shared update-policy prompt. Soft card is in-flow on the tab shell;
 * hard gate is a full-screen overlay from [ClientUpdateGate].
 */
data class ClientUpdatePromptState(
    val decision: UpdateDecision?,
    val downloadState: ClientUpdateDownloadState,
    val softDismissed: Boolean,
    val onDownload: () -> Unit,
    val onConfirmInstall: () -> Unit,
    val onLater: () -> Unit,
)

@Composable
internal fun rememberClientUpdatePrompt(
    repository: MessengerRepository,
): ClientUpdatePromptState {
    var decision by remember { mutableStateOf<UpdateDecision?>(null) }
    val token by SessionStore.token.collectAsState()
    val user by SessionStore.user.collectAsState()
    val reauthRequired by SessionStore.reauthRequired.collectAsState()
    val promptUserId = user?.id
    val updateCheckAllowed = promptUserId != null && token != null && !reauthRequired
    var softDismissed by remember(promptUserId) {
        mutableStateOf(ClientUpdateSoftPromptMemory.dismissedFor(promptUserId))
    }
    val downloadState by ClientUpdateDownloadSession.state.collectAsState()

    LaunchedEffect(promptUserId) {
        ClientUpdateSoftPromptMemory.syncUser(promptUserId)
        softDismissed = ClientUpdateSoftPromptMemory.dismissedFor(promptUserId)
    }

    LaunchedEffect(repository, updateCheckAllowed) {
        if (!updateCheckAllowed) {
            decision = null
            return@LaunchedEffect
        }
        while (isActive) {
            val policy = runCatching { repository.fetchClientUpdatePolicy() }.getOrNull()
            if (policy != null) {
                ClientFeatureFlags.update(policy.features)
                val next = ClientUpdateEvaluator.evaluate(
                    local = AppRuntimeInfo.toClientAppIdentity(),
                    policy = policy,
                )
                decision = next
                // Drop stale cached APK when local build is already current; otherwise
                // restore Ready so Settings can finish install.
                ClientUpdateDownloadSession.reconcileWithPolicy(next)
            }
            delay(CLIENT_UPDATE_POLL_MS)
        }
    }

    val current = decision
    return ClientUpdatePromptState(
        decision = current,
        downloadState = downloadState,
        softDismissed = softDismissed,
        onDownload = {
            if (current != null) {
                ClientUpdateDownloadSession.start(
                    ClientUpdateInstallLogic.primaryInstallUrl(current),
                )
            }
        },
        onConfirmInstall = { ClientUpdateDownloadSession.confirmInstall() },
        onLater = {
            ClientUpdateDownloadSession.dismissReady()
            ClientUpdateSoftPromptMemory.dismissSoft(promptUserId)
            softDismissed = true
        },
    )
}

/**
 * Hard full-screen gate only. Soft card is [ClientUpdateSoftBannerHost] on Main.
 */
@Composable
fun ClientUpdateGate(
    prompt: ClientUpdatePromptState,
    rootModifier: Modifier = Modifier,
) {
    val current = prompt.decision ?: return
    if (!ClientUpdatePromptPolicy.shouldShowHardGate(current.kind)) return
    HardUpdateScreen(
        decision = current,
        downloadState = prompt.downloadState,
        onDownload = prompt.onDownload,
        onConfirmInstall = prompt.onConfirmInstall,
        rootModifier = rootModifier,
    )
}

/** In-flow soft card for the tab shell. Never used as a conversation overlay. */
@Composable
internal fun ClientUpdateSoftBannerHost(
    prompt: ClientUpdatePromptState,
    modifier: Modifier = Modifier,
) {
    val current = prompt.decision ?: return
    if (!ClientUpdatePromptPolicy.shouldShowSoftBanner(
            kind = current.kind,
            dismissed = prompt.softDismissed,
            onMainShell = true,
        )
    ) {
        return
    }
    SoftUpdateBanner(
        decision = current,
        downloadState = prompt.downloadState,
        onDownload = prompt.onDownload,
        onConfirmInstall = prompt.onConfirmInstall,
        onLater = prompt.onLater,
        rootModifier = modifier,
    )
}

/** Last known feature flags from update policy (for optional UI gates). */
object ClientFeatureFlags {
    @Volatile
    var snapshot: Map<String, Boolean> = emptyMap()
        private set

    fun update(features: Map<String, Boolean>) {
        snapshot = features
    }

    fun enabled(key: String, default: Boolean = false): Boolean =
        snapshot[key] ?: default
}

private object ClientUpdateSoftPromptMemory {
    @Volatile
    private var activeUserId: String? = null

    @Volatile
    private var dismissedUserId: String? = null

    fun syncUser(userId: String?) {
        if (userId == null) {
            activeUserId = null
            dismissedUserId = null
            return
        }
        if (activeUserId != userId) {
            activeUserId = userId
            dismissedUserId = null
        }
    }

    fun dismissedFor(userId: String?): Boolean {
        syncUser(userId)
        return userId != null && dismissedUserId == userId
    }

    fun dismissSoft(userId: String?) {
        if (userId == null) return
        syncUser(userId)
        dismissedUserId = userId
    }
}

@Composable
private fun SoftUpdateBanner(
    decision: UpdateDecision,
    downloadState: ClientUpdateDownloadState,
    onDownload: () -> Unit,
    onConfirmInstall: () -> Unit,
    onLater: () -> Unit,
    rootModifier: Modifier = Modifier,
) {
    val downloading = downloadState as? ClientUpdateDownloadState.Downloading
    val ready = downloadState is ClientUpdateDownloadState.Ready
    val failed = downloadState as? ClientUpdateDownloadState.Failed
    val busy = downloading != null

    // In-flow card: wrap height so the tab shell header stays visible and tappable.
    Box(
        modifier = rootModifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(
                start = GlagolitsaSpacing.lg,
                end = GlagolitsaSpacing.lg,
                top = GlagolitsaSpacing.sm,
                bottom = GlagolitsaSpacing.md,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlagolitsaColors.Surface800.copy(alpha = 0.96f))
                .padding(GlagolitsaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (ready) {
                    ClientUpdateInstallLogic.LABEL_READY_TITLE
                } else {
                    decision.title
                },
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (ready) {
                    ClientUpdateInstallLogic.LABEL_READY_BODY
                } else {
                    decision.body
                },
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
            )
            if (!ready) {
                decision.latestVersion?.let { latest ->
                    Text(
                        text = "Доступна: $latest · у вас ${AppRuntimeInfo.versionName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                }
            }
            DownloadProgressBlock(downloadState)
            val installError = (downloadState as? ClientUpdateDownloadState.Ready)?.installError
            val errorText = failed?.message ?: installError
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlagolitsaButton(
                    onClick = onLater,
                    style = GlagolitsaButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    // Always allow dismiss: download continues in ClientUpdateDownloadSession.
                    enabled = true,
                ) {
                    Text(
                        if (ready || busy) ClientUpdateInstallLogic.LABEL_LATER else "Позже",
                        color = GlagolitsaColors.TextPrimary,
                    )
                }
                if (ready) {
                    GlagolitsaButton(
                        onClick = onConfirmInstall,
                        style = GlagolitsaButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            ClientUpdateInstallLogic.LABEL_INSTALL,
                            color = GlagolitsaColors.TextPrimary,
                        )
                    }
                } else {
                    GlagolitsaButton(
                        onClick = onDownload,
                        style = GlagolitsaButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                        enabled = !decision.installUrl.isNullOrBlank() && !busy,
                        loading = busy && downloading?.progress == null,
                    ) {
                        Text(
                            ClientUpdateInstallLogic.softInstallButtonLabel(
                                downloading = busy,
                                progressPercent = downloading?.percent,
                            ),
                            color = GlagolitsaColors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HardUpdateScreen(
    decision: UpdateDecision,
    downloadState: ClientUpdateDownloadState,
    onDownload: () -> Unit,
    onConfirmInstall: () -> Unit,
    rootModifier: Modifier = Modifier,
) {
    val downloading = downloadState as? ClientUpdateDownloadState.Downloading
    val ready = downloadState is ClientUpdateDownloadState.Ready
    val failed = downloadState as? ClientUpdateDownloadState.Failed
    val busy = downloading != null
    val canAct = ClientUpdateInstallLogic.primaryInstallUrl(decision) != null

    Box(
        modifier = rootModifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950.copy(alpha = 0.97f))
            .screenTopSafeArea()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GlagolitsaColors.Surface800.copy(alpha = 0.95f))
                .padding(24.dp),
        ) {
            Text(
                text = if (ready) {
                    ClientUpdateInstallLogic.LABEL_READY_TITLE
                } else {
                    decision.title
                },
                style = MaterialTheme.typography.headlineSmall,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (ready) {
                    ClientUpdateInstallLogic.LABEL_READY_BODY
                } else {
                    decision.body
                },
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Box(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("У вас: ${AppRuntimeInfo.versionName} (${AppRuntimeInfo.versionCode})")
                    decision.minVersion?.let { append("\nНужна ≥ $it") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = GlagolitsaColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
            DownloadProgressBlock(downloadState)
            val installError = (downloadState as? ClientUpdateDownloadState.Ready)?.installError
            val errorText = failed?.message ?: installError
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.AccentRed,
                    textAlign = TextAlign.Center,
                )
            }
            Box(modifier = Modifier.height(8.dp))
            if (ready) {
                GlagolitsaButton(
                    onClick = onConfirmInstall,
                    style = GlagolitsaButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        ClientUpdateInstallLogic.LABEL_INSTALL,
                        color = GlagolitsaColors.TextPrimary,
                    )
                }
            } else {
                GlagolitsaButton(
                    onClick = onDownload,
                    style = GlagolitsaButtonStyle.Primary,
                    enabled = canAct && !busy,
                    loading = busy && downloading?.progress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        ClientUpdateInstallLogic.hardPrimaryButtonLabel(
                            downloading = busy,
                            decision = decision,
                            progressPercent = downloading?.percent,
                        ),
                        color = GlagolitsaColors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadProgressBlock(downloadState: ClientUpdateDownloadState) {
    val downloading = downloadState as? ClientUpdateDownloadState.Downloading ?: return
    val progress = downloading.progress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = GlagolitsaColors.AccentRed,
            trackColor = GlagolitsaColors.GlassBorder.copy(alpha = 0.35f),
        )
        Text(
            text = "${downloading.percent ?: 0}%",
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextTertiary,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = GlagolitsaColors.AccentRed,
            trackColor = GlagolitsaColors.GlassBorder.copy(alpha = 0.35f),
        )
    }
}

private const val CLIENT_UPDATE_POLL_MS = 6 * 60 * 60 * 1000L
