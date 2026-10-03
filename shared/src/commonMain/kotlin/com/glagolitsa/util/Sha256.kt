// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.util

expect fun sha256(data: ByteArray): ByteArray

fun sha256Hex(data: ByteArray): String =
    sha256(data).joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }