// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import android.content.Context

actual class CryptoEngineFactory(
    private val context: Context,
) {
    actual fun create(): CryptoEngine = LibSignalCryptoEngine(context)
}