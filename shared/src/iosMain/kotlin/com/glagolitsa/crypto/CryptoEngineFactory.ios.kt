// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

actual class CryptoEngineFactory {
    actual fun create(): CryptoEngine {
        val host = IosCryptoBackends.signal
        return if (host != null) {
            IosLibSignalCryptoEngine(host)
        } else {
            IosDisabledCryptoEngine()
        }
    }
}
