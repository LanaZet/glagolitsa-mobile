// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

import com.glagolitsa.history.SecureHistoryCrypto

/**
 * Saved account recovery code (NIST SP 800-63B-4 §4.2.1.1).
 *
 * 128 bits of CSPRNG entropy, shown as grouped hex. The server stores only a
 * hash. This is not the Secure History backup key and not the login password.
 */
object AccountRecoveryKey {
    const val NORMALIZED_LENGTH = 32

    fun generate(): String = format(SecureHistoryCrypto.generateRecoveryKey().take(NORMALIZED_LENGTH))

    fun normalize(raw: String): String =
        raw.filter { it.isLetterOrDigit() }.lowercase()

    fun format(raw: String): String {
        val normalized = normalize(raw)
        return normalized.chunked(4).joinToString("-")
    }

    fun hint(raw: String): String = normalize(raw).takeLast(4)

    fun validate(raw: String): String? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return "Введите ключ восстановления"
        if (normalized.length < NORMALIZED_LENGTH) {
            return "Ключ восстановления слишком короткий"
        }
        return null
    }
}
