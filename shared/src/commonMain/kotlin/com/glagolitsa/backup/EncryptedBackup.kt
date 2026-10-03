// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.backup

data class EncryptedBackupResult(
    val bytes: ByteArray,
    val recoveryKey: String,
    val createdAt: String,
)

data class CloudBackupInfo(
    val eventId: Long,
    val createdAt: String,
)

expect object EncryptedBackupExporter {
    suspend fun export(passphrase: CharArray): EncryptedBackupResult
}

expect object EncryptedBackupImporter {
    suspend fun import(bytes: ByteArray, passphrase: CharArray, recoveryKey: String)
}