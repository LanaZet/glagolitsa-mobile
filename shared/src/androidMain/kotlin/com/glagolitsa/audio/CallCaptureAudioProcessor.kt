// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiveKit capture-post processor: RNNoise-class spectral NS on top of WebRTC APM.
 * If speech is over-suppressed, the processor bypasses itself for the rest of the call.
 */
class CallCaptureAudioProcessor(
    private val engine: VoiceEnhancerEngine,
    private val highQuality: Boolean,
) : AudioProcessorInterface {
    private var sampleRateHz: Int = 16_000
    private var suppressor: SpectralNoiseSuppressor? = null
    @Volatile
    private var bypass = false

    override fun isEnabled(): Boolean = !bypass && engine != VoiceEnhancerEngine.None

    override fun getName(): String = when (engine) {
        VoiceEnhancerEngine.DeepFilterNet -> "deepfilternet"
        VoiceEnhancerEngine.Rnnoise -> "rnnoise"
        else -> "none"
    }

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        this.sampleRateHz = sampleRateHz.coerceAtLeast(8_000)
        suppressor = SpectralNoiseSuppressor(this.sampleRateHz, highQuality = highQuality)
        bypass = false
    }

    override fun resetAudioProcessing(newRate: Int) {
        sampleRateHz = newRate.coerceAtLeast(8_000)
        suppressor = SpectralNoiseSuppressor(sampleRateHz, highQuality = highQuality)
        bypass = false
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (bypass || numFrames <= 0) return
        val active = suppressor ?: return
        val order = buffer.order()
        val bands = numBands.coerceAtLeast(1)
        val floats = remainingAsFloats(buffer, bands * numFrames) ?: return
        // Split-band: only the lowest band carries most speech energy.
        val band0 = if (bands > 1) floats.copyOf(numFrames) else floats
        active.processInPlace(band0)
        if (active.isOverSuppressed) {
            bypass = true
            AudioProcessingCoordinator.disableEnhancedExperiment()
            return
        }
        if (bands > 1) {
            band0.copyInto(floats, endIndex = numFrames)
        }
        writeFloats(buffer, floats, order)
    }
}

private fun remainingAsFloats(buffer: ByteBuffer, count: Int): FloatArray? {
    val remaining = buffer.remaining()
    return when (remaining) {
        count * 4 -> {
            val out = FloatArray(count)
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            view.asFloatBuffer().get(out)
            out
        }
        count * 2 -> {
            val shorts = ShortArray(count)
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            view.asShortBuffer().get(shorts)
            FloatArray(count) { shorts[it] / 32768f }
        }
        else -> null
    }
}

private fun writeFloats(buffer: ByteBuffer, values: FloatArray, order: ByteOrder) {
    val remaining = buffer.remaining()
    val view = buffer.duplicate().order(order)
    when (remaining) {
        values.size * 4 -> view.asFloatBuffer().put(values)
        values.size * 2 -> {
            val shorts = ShortArray(values.size) { index ->
                (values[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            view.asShortBuffer().put(shorts)
        }
    }
}
