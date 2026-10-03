// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import platform.UIKit.UIPasteboard
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

actual object SecureClipboard {
    actual fun copyWithAutoClear(label: String, text: String, clearAfterMs: Long) {
        val pasteboard = UIPasteboard.generalPasteboard
        pasteboard.string = text
        val nanos = clearAfterMs * 1_000_000L
        val delay = dispatch_time(DISPATCH_TIME_NOW, nanos)
        dispatch_after(delay, dispatch_get_main_queue()) {
            if (pasteboard.string == text) {
                pasteboard.string = ""
            }
        }
    }
}
