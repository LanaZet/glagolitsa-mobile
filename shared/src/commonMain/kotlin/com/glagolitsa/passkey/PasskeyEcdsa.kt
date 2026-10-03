// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

class PasskeyEcdsaKey(
    val d: ByteArray,
    val x: ByteArray,
    val y: ByteArray,
)

internal expect object PasskeyEcdsa {
    fun generate(): PasskeyEcdsaKey
    fun sign(key: PasskeyEcdsaKey, data: ByteArray): ByteArray
    fun randomBytes(size: Int): ByteArray
}
