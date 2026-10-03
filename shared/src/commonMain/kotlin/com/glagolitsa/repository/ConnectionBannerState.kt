// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.platform.NetworkPathKind
import com.glagolitsa.platform.NetworkPathState

/**
 * User-visible connection plate under the chat title (replaces partner presence briefly).
 *
 * Design: **transient process only** — never a permanent status line.
 * - Healthy connected → [Hidden]
 * - No network → [Hidden] (don’t nag; presence stays)
 * - Network up + handshake → short Connecting/Reconnecting, then hide
 * - Reauth → sticky [Failed] until the user signs in again
 */
enum class ConnectionBannerKind {
    Hidden,
    Connecting,
    Reconnecting,
    Degraded,
    Offline,
    Failed,
}

data class ConnectionBannerState(
    val kind: ConnectionBannerKind = ConnectionBannerKind.Hidden,
)

/** WebSocket lifecycle (transport). Separate from UI so brief flaps can be debounced. */
enum class RealtimeConnectionState {
    Hidden,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

/**
 * Pure reducer: WS transport + path quality → chat plate kind (before timing).
 *
 * - Cellular is normal; only [NetworkPathKind.Constrained] is degraded.
 * - Offline path never surfaces a plate (user asked: no permanent “нет сети”).
 * - Partner presence is independent (HTTP); this only reflects our realtime path.
 */
fun reduceConnectionBannerState(
    wsState: RealtimeConnectionState,
    networkState: NetworkPathState,
    reauthRequired: Boolean,
): ConnectionBannerState {
    if (reauthRequired || wsState == RealtimeConnectionState.Failed) {
        return ConnectionBannerState(ConnectionBannerKind.Failed)
    }
    if (wsState == RealtimeConnectionState.Hidden) {
        return ConnectionBannerState()
    }

    val offline = networkState.kind == NetworkPathKind.Unavailable
    // No link: stay quiet. When the path returns, Connecting/Reconnecting will flash once.
    if (offline) {
        return ConnectionBannerState(ConnectionBannerKind.Hidden)
    }

    val degraded = networkState.kind == NetworkPathKind.Constrained || networkState.isConstrained

    val kind = when (wsState) {
        RealtimeConnectionState.Connecting -> when {
            degraded -> ConnectionBannerKind.Degraded
            else -> ConnectionBannerKind.Connecting
        }
        RealtimeConnectionState.Connected -> when {
            degraded -> ConnectionBannerKind.Degraded
            else -> ConnectionBannerKind.Hidden
        }
        RealtimeConnectionState.Reconnecting -> when {
            degraded -> ConnectionBannerKind.Degraded
            else -> ConnectionBannerKind.Reconnecting
        }
        RealtimeConnectionState.Failed -> ConnectionBannerKind.Failed
        RealtimeConnectionState.Hidden -> ConnectionBannerKind.Hidden
    }
    return ConnectionBannerState(kind)
}

/**
 * Process plates: wait out brief flaps, show briefly, then auto-hide
 * (see [presentConnectionBannerOverTime]).
 */
fun ConnectionBannerKind.isTransientProcess(): Boolean =
    this == ConnectionBannerKind.Connecting ||
        this == ConnectionBannerKind.Reconnecting ||
        this == ConnectionBannerKind.Degraded ||
        this == ConnectionBannerKind.Offline

/** Account-level: stays until condition clears. */
fun ConnectionBannerKind.isSticky(): Boolean =
    this == ConnectionBannerKind.Failed

/** @deprecated Prefer [isTransientProcess]. */
@Deprecated("Renamed", ReplaceWith("isTransientProcess()"))
fun ConnectionBannerKind.shouldDebounceBeforeShow(): Boolean = isTransientProcess()

/**
 * Timing for the status-line plate.
 *
 * - Sticky → emit as-is forever (until reducer changes).
 * - Transient → debounce → show → auto-[Hidden] so presence returns.
 * - Already hidden → emit once.
 *
 * [emit] is called from the repository flow; pure sequence of states for tests.
 */
fun presentConnectionBannerSequence(
    banner: ConnectionBannerState,
): ConnectionBannerPresentation {
    return when {
        banner.kind == ConnectionBannerKind.Hidden ->
            ConnectionBannerPresentation.Immediate(banner)
        banner.kind.isSticky() ->
            ConnectionBannerPresentation.Immediate(banner)
        banner.kind.isTransientProcess() ->
            ConnectionBannerPresentation.Transient(
                showAfterMs = CONNECTION_BANNER_DEBOUNCE_MS,
                shown = banner,
                hideAfterMs = CONNECTION_BANNER_MAX_VISIBLE_MS,
            )
        else ->
            ConnectionBannerPresentation.Immediate(banner)
    }
}

sealed class ConnectionBannerPresentation {
    data class Immediate(val state: ConnectionBannerState) : ConnectionBannerPresentation()
    data class Transient(
        val showAfterMs: Long,
        val shown: ConnectionBannerState,
        val hideAfterMs: Long,
    ) : ConnectionBannerPresentation()
}

/** Hide Connecting/Reconnecting unless the process lasts this long (avoid flap). */
const val CONNECTION_BANNER_DEBOUNCE_MS = 1_200L

/** After showing a process plate, return to partner presence. */
const val CONNECTION_BANNER_MAX_VISIBLE_MS = 3_500L

/**
 * Copy for the chat **status line** under the peer name (replaces «в сети» temporarily).
 * Null = keep partner presence. Not a top-of-screen banner.
 */
fun ConnectionBannerState.connectionStatusText(): String? =
    when (kind) {
        ConnectionBannerKind.Hidden -> null
        ConnectionBannerKind.Connecting -> "Обновляем соединение…"
        ConnectionBannerKind.Reconnecting -> "Переподключаемся…"
        ConnectionBannerKind.Degraded -> "Соединение ограничено"
        ConnectionBannerKind.Offline -> "Нет соединения"
        ConnectionBannerKind.Failed -> "Требуется вход"
    }

/** @deprecated Use [connectionStatusText]. */
@Deprecated("Renamed to connectionStatusText", ReplaceWith("connectionStatusText()"))
fun ConnectionBannerState.headerStatusText(): String? = connectionStatusText()
