// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import kotlin.concurrent.Volatile

/**
 * Optional DeepFilterNet 3 backend. Not bundled by default — register a native
 * ONNX/NNAPI implementation when the model and latency budget are available.
 */
interface DeepFilterNetBackend {
    val available: Boolean
    val name: String
    fun enhance(pcm: ShortArray, sampleRateHz: Int): ShortArray
}

object DeepFilterNetRegistry {
    @Volatile
    var backend: DeepFilterNetBackend? = null

    val available: Boolean
        get() = backend?.available == true

    fun enhanceOrNull(pcm: ShortArray, sampleRateHz: Int): ShortArray? {
        val current = backend ?: return null
        if (!current.available) return null
        return runCatching { current.enhance(pcm, sampleRateHz) }.getOrNull()
    }
}
