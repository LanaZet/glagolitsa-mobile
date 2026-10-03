// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

actual class CryptoEngineFactory {
    actual fun create(): CryptoEngine = DesktopStubCryptoEngine()
}