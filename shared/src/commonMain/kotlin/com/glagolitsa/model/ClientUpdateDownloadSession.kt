// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.platform.AppRuntimeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-wide APK download session so progress / ready state survives banner dismiss
 * and navigation to Settings → Обновления.
 *
 * Disk is the source of truth: a valid cached APK (+ URL sidecar) means Ready,
 * even if UI state was cleared with [dismissReady] or the process restarted.
 */
object ClientUpdateDownloadSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<ClientUpdateDownloadState>(ClientUpdateDownloadState.Idle)
    val state: StateFlow<ClientUpdateDownloadState> = _state.asStateFlow()

    /**
     * Align in-memory/disk update state with the latest policy decision.
     * - No update needed → drop stale cache so the soft banner cannot reappear.
     * - Soft/hard still relevant → restore [Ready] from disk when the APK is present.
     */
    fun reconcileWithPolicy(decision: UpdateDecision) {
        if (!decision.shouldPrompt) {
            discardStalePending()
            return
        }
        reconcilePending(ClientUpdateInstallLogic.primaryInstallUrl(decision))
    }

    /**
     * Restore [Ready] from the on-disk APK cache when present.
     * [preferredUrl] from policy is used when the sidecar is missing or matches.
     * If the sidecar URL differs from policy, stays Idle so [start] re-downloads.
     */
    fun reconcilePending(preferredUrl: String? = null) {
        if (_state.value is ClientUpdateDownloadState.Downloading) return
        if (!AppRuntimeInfo.hasPendingUpdateApk()) {
            if (_state.value is ClientUpdateDownloadState.Ready) {
                _state.value = ClientUpdateDownloadState.Idle
            }
            return
        }

        val preferred = ClientUpdateInstallLogic.normalizeUrl(preferredUrl)
        val cachedSource = ClientUpdateInstallLogic.normalizeUrl(AppRuntimeInfo.pendingUpdateSourceUrl())
        val url = when {
            preferred != null && cachedSource != null && preferred != cachedSource -> {
                // Policy points at a newer APK — drop obsolete cache so user re-downloads.
                AppRuntimeInfo.clearPendingUpdateApk()
                if (_state.value is ClientUpdateDownloadState.Ready) {
                    _state.value = ClientUpdateDownloadState.Idle
                }
                return
            }
            preferred != null -> preferred
            cachedSource != null -> cachedSource
            else -> return
        }

        val current = _state.value
        if (current is ClientUpdateDownloadState.Ready &&
            current.url == url &&
            current.installError == null
        ) {
            return
        }
        _state.value = ClientUpdateDownloadState.Ready(url = url)
    }

    /** Drop disk cache + ready UI when the installed app is already up to date. */
    fun discardStalePending() {
        if (_state.value is ClientUpdateDownloadState.Downloading) return
        AppRuntimeInfo.clearPendingUpdateApk()
        when (_state.value) {
            is ClientUpdateDownloadState.Ready,
            is ClientUpdateDownloadState.Failed,
            -> _state.value = ClientUpdateDownloadState.Idle
            else -> Unit
        }
    }

    /**
     * Start download, or jump to [Ready] if this URL is already cached on disk.
     * Pass [forceRedownload] = true to replace the cached APK.
     */
    fun start(url: String?, forceRedownload: Boolean = false) {
        val target = ClientUpdateInstallLogic.normalizeUrl(url) ?: return
        scope.launch {
            startMutex.withLock {
                val current = _state.value
                if (current is ClientUpdateDownloadState.Downloading && current.url == target) {
                    return@withLock
                }

                if (!forceRedownload &&
                    ClientUpdateInstallLogic.routeFor(target) ==
                    ClientUpdateInstallLogic.Route.DOWNLOAD_APK &&
                    AppRuntimeInfo.hasPendingUpdateApk()
                ) {
                    val cachedSource = ClientUpdateInstallLogic.normalizeUrl(
                        AppRuntimeInfo.pendingUpdateSourceUrl(),
                    )
                    // Reuse cache when sidecar matches, or when sidecar is missing (legacy).
                    if (cachedSource == null || cachedSource == target) {
                        _state.value = ClientUpdateDownloadState.Ready(url = target)
                        return@withLock
                    }
                }

                if (!forceRedownload &&
                    current is ClientUpdateDownloadState.Ready &&
                    current.url == target &&
                    AppRuntimeInfo.hasPendingUpdateApk()
                ) {
                    return@withLock
                }

                downloadJob?.cancel()
                downloadJob = scope.launch {
                    runDownload(target)
                }
            }
        }
    }

    /**
     * Open package installer for the cached APK after user confirmation.
     */
    fun confirmInstall() {
        scope.launch {
            reconcilePending(
                (_state.value as? ClientUpdateDownloadState.Ready)?.url
                    ?: AppRuntimeInfo.pendingUpdateSourceUrl(),
            )
            val ready = _state.value as? ClientUpdateDownloadState.Ready
            val url = ready?.url ?: AppRuntimeInfo.pendingUpdateSourceUrl()
            if (!AppRuntimeInfo.hasPendingUpdateApk()) {
                _state.value = ClientUpdateDownloadState.Failed(
                    url = url,
                    message = ClientUpdateInstallLogic.ERROR_NO_PENDING_APK,
                )
                return@launch
            }
            runCatching {
                AppRuntimeInfo.launchPendingUpdateInstaller()
                if (!url.isNullOrBlank()) {
                    _state.value = ClientUpdateDownloadState.Ready(url = url, installError = null)
                }
            }.onFailure { error ->
                _state.value = ClientUpdateDownloadState.Ready(
                    url = url.orEmpty(),
                    installError = ClientUpdateInstallLogic.errorMessageFromThrowable(error),
                )
            }
        }
    }

    /**
     * Hide soft-banner install prompt. Does not delete the APK —
     * [reconcilePending] restores Ready in Settings → Обновления.
     */
    fun dismissReady() {
        when (_state.value) {
            is ClientUpdateDownloadState.Ready,
            is ClientUpdateDownloadState.Failed,
            -> _state.value = ClientUpdateDownloadState.Idle
            else -> Unit
        }
    }

    fun clearError() {
        if (_state.value is ClientUpdateDownloadState.Failed) {
            _state.value = ClientUpdateDownloadState.Idle
        }
    }

    private suspend fun runDownload(url: String) {
        when (ClientUpdateInstallLogic.routeFor(url)) {
            ClientUpdateInstallLogic.Route.NOOP -> {
                _state.value = ClientUpdateDownloadState.Idle
            }
            ClientUpdateInstallLogic.Route.OPEN_URL -> {
                runCatching { AppRuntimeInfo.openUrl(url) }
                    .onFailure {
                        _state.value = ClientUpdateDownloadState.Failed(
                            url = url,
                            message = ClientUpdateInstallLogic.errorMessageFromThrowable(it),
                        )
                    }
                    .onSuccess {
                        _state.value = ClientUpdateDownloadState.Idle
                    }
            }
            ClientUpdateInstallLogic.Route.DOWNLOAD_APK -> {
                _state.value = ClientUpdateDownloadState.Downloading(
                    url = url,
                    bytesRead = 0L,
                    contentLength = null,
                    progress = null,
                )
                runCatching {
                    AppRuntimeInfo.downloadUpdate(url) { bytesRead, contentLength ->
                        _state.value = ClientUpdateDownloadState.Downloading(
                            url = url,
                            bytesRead = bytesRead,
                            contentLength = contentLength,
                            progress = ClientUpdateInstallLogic.progressFraction(
                                bytesRead,
                                contentLength,
                            ),
                        )
                    }
                }.onSuccess {
                    _state.value = ClientUpdateDownloadState.Ready(url = url)
                }.onFailure { error ->
                    _state.value = ClientUpdateDownloadState.Failed(
                        url = url,
                        message = ClientUpdateInstallLogic.errorMessageFromThrowable(error),
                    )
                }
            }
        }
    }
}

sealed class ClientUpdateDownloadState {
    data object Idle : ClientUpdateDownloadState()

    data class Downloading(
        val url: String,
        val bytesRead: Long,
        val contentLength: Long?,
        /** 0f..1f when known, else null (indeterminate). */
        val progress: Float?,
    ) : ClientUpdateDownloadState() {
        val percent: Int?
            get() = ClientUpdateInstallLogic.progressPercent(bytesRead, contentLength)
    }

    data class Ready(
        val url: String,
        /** Set when opening the installer failed (e.g. unknown-apps permission). */
        val installError: String? = null,
    ) : ClientUpdateDownloadState()

data class Failed(val url: String?, val message: String) : ClientUpdateDownloadState()
}
