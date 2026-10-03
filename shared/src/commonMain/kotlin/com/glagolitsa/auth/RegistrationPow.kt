// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

import com.glagolitsa.util.sha256

/**
 * Proof-of-work для регистрации — тот же алгоритм, что на сервере
 * (SHA256(challenge:solution), leading zero bits).
 */
object RegistrationPow {
    fun solve(challenge: String, difficulty: Int, maxAttempts: Long = 5_000_000L): String? {
        var nonce = 0L
        while (nonce < maxAttempts) {
            val solution = nonce.toString()
            if (verify(challenge, solution, difficulty)) {
                return solution
            }
            nonce++
        }
        return null
    }

    fun verify(challenge: String, solution: String, difficulty: Int): Boolean {
        if (solution.isEmpty()) return false
        val digest = sha256("$challenge:$solution".encodeToByteArray())
        return leadingZeroBits(digest) >= difficulty
    }

    private fun leadingZeroBits(data: ByteArray): Int {
        var bits = 0
        for (byte in data) {
            if (byte == 0.toByte()) {
                bits += 8
                continue
            }
            for (shift in 7 downTo 0) {
                if (byte.toInt() and (1 shl shift) == 0) {
                    bits++
                } else {
                    return bits
                }
            }
        }
        return bits
    }
}