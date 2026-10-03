// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.backup

actual object EncryptedBackupExporter {
    actual suspend fun export(passphrase: CharArray): EncryptedBackupResult {
        throw UnsupportedOperationException("Encrypted backup export is not available on iOS yet")
    }
}
