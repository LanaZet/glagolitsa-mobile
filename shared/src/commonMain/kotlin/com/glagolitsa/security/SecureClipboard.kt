// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

expect object SecureClipboard {
    fun copyWithAutoClear(label: String, text: String, clearAfterMs: Long = 30_000L)
}