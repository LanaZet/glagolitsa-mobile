// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.log

actual object AppLog {
    actual fun debug(message: String) {
        println("Glagolitsa: $message")
    }

    actual fun warning(message: String) {
        println("Glagolitsa: $message")
    }
}
