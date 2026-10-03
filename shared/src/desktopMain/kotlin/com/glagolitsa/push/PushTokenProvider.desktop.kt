// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

actual class PushTokenProvider actual constructor() {
    actual val platform: String = "desktop"

    actual suspend fun currentToken(): String? = null
}