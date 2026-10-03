// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import com.glagolitsa.platform.AppLifecycle

actual fun createCallMediaEngine(): CallMediaEngine {
    val ctx = AppLifecycle.applicationContextOrNull()
        ?: return NoopCallMediaEngine()
    return LiveKitCallMediaEngine(ctx)
}
