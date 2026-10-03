// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import kotlinx.coroutines.flow.StateFlow

enum class NetworkPathKind {
    Unknown,
    Available,
    /** Captive portal / unvalidated path — not ordinary cellular. */
    Constrained,
    Unavailable,
}

data class NetworkPathState(
    val kind: NetworkPathKind = NetworkPathKind.Unknown,
    val isConstrained: Boolean = false,
)

expect object NetworkPathMonitor {
    val state: StateFlow<NetworkPathState>
}
