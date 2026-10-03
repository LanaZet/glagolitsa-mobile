// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Full FLAG_SECURE equivalent is limited on iOS; placeholder for blur-on-background later. */
@Composable
actual fun SecureWindowEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        onDispose { }
    }
}
