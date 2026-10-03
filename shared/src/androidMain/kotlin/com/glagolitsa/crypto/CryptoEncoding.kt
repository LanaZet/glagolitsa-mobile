// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import android.util.Base64

internal fun ByteArray.toBase64(): String =
    Base64.encodeToString(this, Base64.NO_WRAP)

internal fun String.decodeBase64(): ByteArray =
    Base64.decode(this, Base64.NO_WRAP)

internal fun encodeHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
    ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
}

internal fun decodeHex(value: String): ByteArray {
    check(value.length % 2 == 0) { "Invalid hex" }
    return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}