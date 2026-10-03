// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.log

/**
 * Safe logging on Kotlin/Native.
 *
 * Do **not** pass Kotlin [String] as `NSLog("%@", …)` format args: CFString bridging
 * is unreliable and can abort during cold start (seen in restoreLocalSessionShell).
 * Signal/Mattermost style: logging must never take down the process.
 */
actual object AppLog {
    actual fun debug(message: String) {
        println("Glagolitsa: $message")
    }

    actual fun warning(message: String) {
        println("Glagolitsa WARNING: $message")
    }
}
