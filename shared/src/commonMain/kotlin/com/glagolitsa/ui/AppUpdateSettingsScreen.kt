// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.api.ApiException
import com.glagolitsa.model.ClientUpdateDownloadSession
import com.glagolitsa.model.ClientUpdateDownloadState
import com.glagolitsa.model.ClientUpdateEvaluator
import com.glagolitsa.model.ClientUpdateInstallLogic
import com.glagolitsa.model.UpdateDecision
import com.glagolitsa.model.UpdateDecisionKind
import com.glagolitsa.platform.AppRuntimeInfo
import com.glagolitsa.platform.toClientAppIdentity
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch

/**
 * Settings → Обновления: check policy, download APK in-app with progress,
 * then ask the user to open the system installer.
 */
@Composable
fun AppUpdateSettingsScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onDecisionChanged: (UpdateDecision) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf<UpdateDecision?>(null) }
    val downloadState by ClientUpdateDownloadSession.state.collectAsState()

    fun runCheck() {
        scope.launch {
            checking = true
            error = null
            val result = runCatching { repository.fetchClientUpdatePolicy() }
            result.onSuccess { policy ->
                ClientFeatureFlags.update(policy.features)
                val nextDecision = ClientUpdateEvaluator.evaluate(
                    local = AppRuntimeInfo.toClientAppIdentity(),
                    policy = policy,
                )
                decision = nextDecision
                onDecisionChanged(nextDecision)
                // Banner may have already downloaded the APK — restore Ready from cache.
                ClientUpdateDownloadSession.reconcilePending(
                    ClientUpdateInstallLogic.primaryInstallUrl(nextDecision),
                )
            }.onFailure {
                error = updateCheckErrorMessage(it)
            }
            checking = false
            loading = false
        }
    }

    fun startDownload(url: String?) {
        error = null
        ClientUpdateDownloadSession.clearError()
        // Reuses cache if this URL was already pulled via soft/hard banner.
        ClientUpdateDownloadSession.start(url, forceRedownload = false)
    }

    LaunchedEffect(Unit) {
        // Immediate restore if APK is already on disk (before policy returns).
        ClientUpdateDownloadSession.reconcilePending(null)
        runCheck()
    }

    // Surface download failures into the local error banner.
    LaunchedEffect(downloadState) {
        val failed = downloadState as? ClientUpdateDownloadState.Failed
        if (failed != null) {
            error = failed.message
        }
    }

    val downloading = downloadState as? ClientUpdateDownloadState.Downloading
    val ready = downloadState is ClientUpdateDownloadState.Ready
    val busy = downloading != null
    val canShowInstallActions = ClientUpdateInstallLogic.canShowInstallActions(decision)
    val readyToInstall = shouldSurfaceCachedUpdate(ready, decision)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .padding(horizontal = GlagolitsaSpacing.xl)
            .padding(top = GlagolitsaSpacing.md, bottom = bottomContentPadding),
    ) {
        AppTopBar(
            title = "Обновления",
            navigation = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
        )

        Spacer(modifier = Modifier.height(GlagolitsaSpacing.lg))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
        ) {
            VersionCard()

            when {
                loading || checking -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
                    }
                }
                error != null &&
                    !ready &&
                    downloading == null &&
                    downloadState !is ClientUpdateDownloadState.Failed &&
                    decision == null -> {
                    StatusCard(
                        title = "Проверка не удалась",
                        body = error.orEmpty(),
                        highlight = true,
                    )
                }
                decision != null -> {
                    val d = decision!!
                    when {
                        readyToInstall -> StatusCard(
                            title = ClientUpdateInstallLogic.LABEL_READY_TITLE,
                            body = ClientUpdateInstallLogic.LABEL_READY_BODY,
                            highlight = true,
                        )
                        d.kind == UpdateDecisionKind.NONE -> StatusCard(
                            title = "У вас актуальная версия",
                            body = "Дополнительное обновление не требуется.",
                            highlight = false,
                        )
                        d.kind == UpdateDecisionKind.SOFT -> StatusCard(
                            title = "Доступна новая версия",
                            body = buildUpdateBody(d),
                            highlight = true,
                        )
                        d.kind == UpdateDecisionKind.HARD -> StatusCard(
                            title = "Требуется обновление",
                            body = buildUpdateBody(d),
                            highlight = true,
                        )
                    }
                }
            }

            DownloadProgressBlock(downloadState)

            val installError = (downloadState as? ClientUpdateDownloadState.Ready)?.installError
            val downloadError = (downloadState as? ClientUpdateDownloadState.Failed)?.message
            val surfaceError = installError ?: downloadError ?: error?.takeIf {
                readyToInstall || downloading != null || downloadState is ClientUpdateDownloadState.Failed
            }
            if (!surfaceError.isNullOrBlank()) {
                Text(
                    text = surfaceError,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }

            GlagolitsaButton(
                onClick = { runCheck() },
                enabled = !checking && !loading && !busy,
                loading = checking,
                style = GlagolitsaButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить обновления", color = GlagolitsaColors.TextPrimary)
            }

            if (readyToInstall) {
                ReadyInstallActions(
                    onInstall = { ClientUpdateDownloadSession.confirmInstall() },
                    onLater = { ClientUpdateDownloadSession.dismissReady() },
                )
            } else if (canShowInstallActions) {
                val d = decision!!
                DownloadInstallActions(
                    decision = d,
                    busy = busy,
                    progressPercent = downloading?.percent,
                    loadingIndeterminate = busy && downloading?.progress == null,
                    onStartDownload = ::startDownload,
                )
            }
        }
    }
}

private fun shouldSurfaceCachedUpdate(ready: Boolean, decision: UpdateDecision?): Boolean {
    // A cached APK can outlive the update policy that prompted it; only surface it
    // while the current policy still says this build is old.
    return ClientUpdateInstallLogic.shouldOfferCachedInstall(
        decision = decision,
        hasPendingApk = ready,
    )
}

@Composable
private fun ReadyInstallActions(
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    GlagolitsaButton(
        onClick = onInstall,
        style = GlagolitsaButtonStyle.Primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            ClientUpdateInstallLogic.LABEL_INSTALL,
            color = GlagolitsaColors.TextPrimary,
        )
    }
    GlagolitsaButton(
        onClick = onLater,
        style = GlagolitsaButtonStyle.Tertiary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            ClientUpdateInstallLogic.LABEL_LATER,
            color = GlagolitsaColors.TextPrimary,
        )
    }
}

@Composable
private fun DownloadInstallActions(
    decision: UpdateDecision,
    busy: Boolean,
    progressPercent: Int?,
    loadingIndeterminate: Boolean,
    onStartDownload: (String?) -> Unit,
) {
    GlagolitsaButton(
        onClick = {
            onStartDownload(ClientUpdateInstallLogic.primaryInstallUrl(decision))
        },
        style = GlagolitsaButtonStyle.Primary,
        enabled = !busy,
        loading = loadingIndeterminate,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            ClientUpdateInstallLogic.primarySettingsButtonLabel(
                downloading = busy,
                decision = decision,
                progressPercent = progressPercent,
            ),
            color = GlagolitsaColors.TextPrimary,
        )
    }
    // Store only when there is no APK (sideload-only phase hides Play).
    if (ClientUpdateInstallLogic.secondaryStoreButtonVisible(decision)) {
        GlagolitsaButton(
            onClick = { onStartDownload(decision.storeUrl) },
            style = GlagolitsaButtonStyle.Tertiary,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                ClientUpdateInstallLogic.LABEL_OPEN_STORE,
                color = GlagolitsaColors.TextPrimary,
            )
        }
    }
}

private fun buildUpdateBody(decision: UpdateDecision): String = buildString {
    append(decision.body)
    decision.latestVersion?.let {
        append("\n\nДоступна: $it")
    }
    decision.minVersion?.let {
        append("\nМинимум: $it")
    }
    append("\nУ вас: ${AppRuntimeInfo.versionName} (${AppRuntimeInfo.versionCode})")
}

private fun updateCheckErrorMessage(error: Throwable): String =
    when {
        error is ApiException && error.status == HttpStatusCode.NotFound ->
            "Проверка выполнена, но сервер обновлений не отдаёт политику приложения."
        error is ApiException ->
            "Сервер обновлений ответил ошибкой ${error.status.value}."
        !error.message.isNullOrBlank() ->
            error.message ?: "Не удалось проверить обновления"
        else ->
            "Не удалось проверить обновления"
    }

@Composable
private fun VersionCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.72f))
            .padding(GlagolitsaSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Текущая версия",
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextTertiary,
        )
        Text(
            text = AppRuntimeInfo.versionName,
            style = MaterialTheme.typography.titleLarge,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Сборка ${AppRuntimeInfo.versionCode} · ${AppRuntimeInfo.platform.name.lowercase()}",
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    highlight: Boolean,
) {
    val borderColor = if (highlight) {
        GlagolitsaColors.OrnamentGold.copy(alpha = 0.55f)
    } else {
        GlagolitsaColors.GlassBorder.copy(alpha = 0.4f)
    }
    val titleColor = if (highlight) {
        GlagolitsaColors.OrnamentGold
    } else {
        GlagolitsaColors.TextPrimary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.72f))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(GlagolitsaSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}
