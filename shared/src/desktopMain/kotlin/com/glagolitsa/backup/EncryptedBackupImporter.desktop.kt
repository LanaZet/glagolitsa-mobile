// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.backup

actual object EncryptedBackupImporter {
    actual suspend fun import(bytes: ByteArray, passphrase: CharArray, recoveryKey: String) {
        throw UnsupportedOperationException("Encrypted backup import is only available on Android")
    }
}