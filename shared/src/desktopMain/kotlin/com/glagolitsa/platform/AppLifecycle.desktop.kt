// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

actual object AppLifecycle {
    actual fun restart() {
        // Desktop session restore picks up the replaced database on next launch.
    }

    actual fun isInForeground(): Boolean = true
}