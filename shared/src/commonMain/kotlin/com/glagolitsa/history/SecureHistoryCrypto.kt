// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.history

/**
 * Signal Secure Backups–style seal for chat history.
 *
 * Format (GLSBR1 — GlaGolitsa Secure Backup Recovery v1):
 * - magic "GLSBR1" (6 bytes)
 * - salt (16)
 * - iv (12)
 * - AES-GCM ciphertext of [AccountHistoryArchive]
 *
 * Key = PBKDF2-HMAC-SHA256(recoveryKey, salt, 210_000) → 32 bytes.
 * Recovery key is 64 hex chars (~256 bit); server never receives it.
 *
 * Login password is **not** used. That avoids offline crack of weak passwords
 * against the cloud blob (the Telegram-style loophole).
 */
expect object SecureHistoryCrypto {
    /** 64 lowercase hex characters (256-bit entropy). */
    fun generateRecoveryKey(): String

    fun seal(recoveryKey: String, plaintext: ByteArray): ByteArray

    fun open(recoveryKey: String, sealed: ByteArray): ByteArray?

    fun isSecureBackupBlob(sealed: ByteArray): Boolean
}
