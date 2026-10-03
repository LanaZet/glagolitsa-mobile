// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.presence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.glagolitsa.repository.MessengerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val HEARTBEAT_INTERVAL_MS = 15_000L

@Composable
fun PresenceHeartbeatEffect(
    token: String?,
    repository: MessengerRepository,
) {
    LaunchedEffect(token, repository) {
        if (token.isNullOrBlank()) return@LaunchedEffect
        while (isActive) {
            repository.presence.heartbeat()
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }
}