// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val latestApnsToken = MutableStateFlow<String?>(null)

/** Called from iosApp when didRegisterForRemoteNotifications succeeds. */
fun updateApnsDeviceToken(hexToken: String?) {
    latestApnsToken.value = hexToken?.takeIf { it.isNotBlank() }
}

/**
 * Holds the latest APNs device token hex string set from Swift/AppDelegate.
 * [platform] is the server token platform id.
 */
actual class PushTokenProvider actual constructor() {
    actual suspend fun currentToken(): String? {
        latestApnsToken.value?.let { return it }
        return withTimeoutOrNull(2_000) {
            latestApnsToken.first { it != null }
        }
    }

    actual val platform: String = "ios"
}
