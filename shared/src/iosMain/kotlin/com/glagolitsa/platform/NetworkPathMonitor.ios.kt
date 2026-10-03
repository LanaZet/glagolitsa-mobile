// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight path monitor for iOS. Full NWPathMonitor bindings can replace this later.
 * Default Available so messaging/sync is not stuck offline on simulator.
 */
actual object NetworkPathMonitor {
    private val _state = MutableStateFlow(NetworkPathState(kind = NetworkPathKind.Available))
    actual val state: StateFlow<NetworkPathState> = _state

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        _state.value = NetworkPathState(kind = NetworkPathKind.Available)
    }
}
