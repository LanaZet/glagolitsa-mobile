// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

internal class CborMapBuilder {
    private val pairs = ArrayList<Pair<ByteArray, ByteArray>>()

    fun text(key: String, value: String) {
        pairs += cborText(key) to cborText(value)
    }

    fun bytes(key: String, value: ByteArray) {
        pairs += cborText(key) to cborBytes(value)
    }

    fun map(key: String, value: ByteArray) {
        pairs += cborText(key) to value
    }

    fun unsigned(key: Int, value: Long) {
        pairs += cborInt(key.toLong()) to cborInt(value)
    }

    fun bytes(key: Int, value: ByteArray) {
        pairs += cborInt(key.toLong()) to cborBytes(value)
    }

    fun build(): ByteArray {
        val out = ArrayList<Byte>()
        out += cborHead(5, pairs.size.toLong()).toList()
        pairs.forEach { (key, value) ->
            out += key.toList()
            out += value.toList()
        }
        return out.toByteArray()
    }
}

internal fun cborEmptyMap(): ByteArray = cborHead(5, 0)

internal fun cborInt(value: Long): ByteArray =
    if (value >= 0) cborHead(0, value) else cborHead(1, -value - 1)

internal fun cborText(value: String): ByteArray {
    val raw = value.encodeToByteArray()
    return cborHead(3, raw.size.toLong()) + raw
}

internal fun cborBytes(value: ByteArray): ByteArray =
    cborHead(2, value.size.toLong()) + value

internal fun cborHead(major: Int, n: Long): ByteArray = when {
    n < 24 -> byteArrayOf(((major shl 5) or n.toInt()).toByte())
    n < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), n.toByte())
    n < 65536 -> byteArrayOf(
        ((major shl 5) or 25).toByte(),
        (n shr 8).toByte(),
        n.toByte(),
    )
    else -> error("CBOR length too large")
}
