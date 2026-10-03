// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object NetworkPathMonitor {
    private val _state = MutableStateFlow(NetworkPathState())
    actual val state: StateFlow<NetworkPathState> = _state
}
