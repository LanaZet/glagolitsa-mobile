// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight spectral suppressor used as the RNNoise-class engine.
 *
 * Realtime path keeps a modest gain floor so speech is not erased (A/B safety).
 * Offline HQ uses a deeper floor and a slower noise estimate.
 */
class SpectralNoiseSuppressor(
    private val sampleRateHz: Int,
    private val highQuality: Boolean = false,
) {
    private val fftSize: Int = if (sampleRateHz >= 32_000) 512 else 256
    private val hopSize: Int = fftSize / 2
    private val bins: Int = fftSize / 2
    private val window: FloatArray = hann(fftSize)
    private val pending = FloatArray(fftSize * 2)
    private var pendingCount = 0
    private val overlap = FloatArray(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val noisePsd = FloatArray(bins) { 1e-6f }
    private val prevGain = FloatArray(bins) { 1f }
    private var speechRms = 0.02f
    private var noiseRms = 0.01f
    private var speechPreserveHits = 0
    private var hopsSeen = 0
    private var overSuppressed = false

    private val minGain: Float = if (highQuality) 0.10f else 0.30f
    private val oversub: Float = if (highQuality) 1.45f else 1.08f
    private val noiseLearn: Float = if (highQuality) 0.05f else 0.10f

    val isOverSuppressed: Boolean
        get() = overSuppressed

    fun reset() {
        pendingCount = 0
        pending.fill(0f)
        overlap.fill(0f)
        noisePsd.fill(1e-6f)
        prevGain.fill(1f)
        speechRms = 0.02f
        noiseRms = 0.01f
        speechPreserveHits = 0
        hopsSeen = 0
        overSuppressed = false
    }

    fun process(samples: ShortArray): ShortArray = enhance(samples)

    fun enhance(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        reset()
        val padded = ShortArray(samples.size + fftSize)
        samples.copyInto(padded)
        val acc = FloatArray(padded.size + fftSize)
        val norm = FloatArray(padded.size + fftSize)
        var offset = 0
        while (offset + fftSize <= padded.size) {
            for (i in 0 until fftSize) {
                re[i] = (padded[offset + i] / 32768f) * window[i]
                im[i] = 0f
            }
            applySuppression()
            for (i in 0 until fftSize) {
                acc[offset + i] += re[i] * window[i]
                norm[offset + i] += window[i] * window[i]
            }
            offset += hopSize
        }
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val scale = if (norm[i] > 1e-4f) 1f / norm[i] else 1f
            out[i] = (acc[i] * scale).coerceIn(-1f, 1f).let { (it * 32767f).toInt().toShort() }
        }
        return out
    }

    /**
     * Streaming 10 ms-style callback. Writes processed samples in place when a hop
     * is ready; leftover input is delayed by one hop (algorithmic latency).
     */
    fun processInPlace(samples: FloatArray) {
        if (samples.isEmpty()) return
        for (sample in samples) {
            if (pendingCount < pending.size) {
                pending[pendingCount++] = sample
            }
        }
        var written = 0
        while (pendingCount >= fftSize && written + hopSize <= samples.size) {
            for (i in 0 until fftSize) {
                re[i] = pending[i] * window[i]
                im[i] = 0f
            }
            applySuppression()
            for (i in 0 until hopSize) {
                val mixed = (overlap[i] + re[i] * window[i]).coerceIn(-1f, 1f)
                samples[written + i] = mixed
            }
            for (i in 0 until hopSize) {
                overlap[i] = re[i + hopSize] * window[i + hopSize]
            }
            pending.copyInto(pending, destinationOffset = 0, startIndex = hopSize, endIndex = pendingCount)
            pendingCount -= hopSize
            written += hopSize
        }
    }

    private fun applySuppression() {
        fft(re, im, inverse = false)
        var frameEnergy = 0f
        val power = FloatArray(bins)
        for (k in 0 until bins) {
            power[k] = re[k] * re[k] + im[k] * im[k]
            frameEnergy += power[k]
        }
        frameEnergy = max(frameEnergy / bins, 1e-9f)
        val rms = sqrt(frameEnergy)
        val isSpeech = rms > noiseRms * 2.2f
        hopsSeen++
        val learnNoise = !isSpeech || hopsSeen < if (highQuality) 12 else 6
        if (isSpeech) {
            speechRms = speechRms * 0.9f + rms * 0.1f
        } else {
            noiseRms = noiseRms * 0.82f + rms * 0.18f
        }
        if (learnNoise) {
            for (k in 0 until bins) {
                noisePsd[k] = (1f - noiseLearn) * noisePsd[k] + noiseLearn * power[k]
            }
        }

        var outEnergy = 0f
        for (k in 0 until bins) {
            val prior = max(power[k] / max(noisePsd[k], 1e-12f) - 1f, 0f)
            val raw = 1f - oversub / (prior + 1f)
            val speechProb = 1f - exp(-prior)
            val floor = if (speechProb > 0.62f) max(minGain, 0.58f) else minGain
            val target = raw.coerceIn(floor, 1f)
            val gain = 0.6f * prevGain[k] + 0.4f * target
            prevGain[k] = gain
            re[k] *= gain
            im[k] *= gain
            if (k in 1 until bins) {
                re[fftSize - k] *= gain
                im[fftSize - k] *= gain
            }
            outEnergy += re[k] * re[k] + im[k] * im[k]
        }
        outEnergy = max(outEnergy / bins, 1e-9f)
        if (isSpeech && sqrt(outEnergy) < speechRms * 0.30f) {
            speechPreserveHits++
            if (speechPreserveHits >= 8) overSuppressed = true
        } else if (isSpeech) {
            speechPreserveHits = max(0, speechPreserveHits - 1)
        }
        fft(re, im, inverse = true)
    }

    companion object {
        internal fun waveformBars(samples: ShortArray, count: Int = 34): List<Int> {
            if (samples.isEmpty() || count <= 0) return emptyList()
            val bucket = max(1, samples.size / count)
            return List(count) { index ->
                var peak = 0
                val start = index * bucket
                val end = min(samples.size, start + bucket)
                for (i in start until end) {
                    val mag = abs(samples[i].toInt())
                    if (mag > peak) peak = mag
                }
                (peak / 327.67f).toInt().coerceIn(0, 100)
            }
        }
    }
}

private fun hann(size: Int): FloatArray {
    val twoPi = (2.0 * PI).toFloat()
    return FloatArray(size) { i ->
        0.5f - 0.5f * cos(twoPi * i / (size - 1))
    }
}

/** In-place radix-2 FFT. [re]/[im] length must be a power of two. */
internal fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]
            re[i] = re[j]
            re[j] = tr
            val ti = im[i]
            im[i] = im[j]
            im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = 2.0 * PI / len * if (inverse) 1.0 else -1.0
        val wlenRe = cos(ang).toFloat()
        val wlenIm = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var wRe = 1f
            var wIm = 0f
            for (k in 0 until len / 2) {
                val uRe = re[i + k]
                val uIm = im[i + k]
                val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                re[i + k] = uRe + vRe
                im[i + k] = uIm + vIm
                re[i + k + len / 2] = uRe - vRe
                im[i + k + len / 2] = uIm - vIm
                val nextRe = wRe * wlenRe - wIm * wlenIm
                wIm = wRe * wlenIm + wIm * wlenRe
                wRe = nextRe
            }
            i += len
        }
        len = len shl 1
    }
    if (inverse) {
        val scale = 1f / n
        for (i in 0 until n) {
            re[i] *= scale
            im[i] *= scale
        }
    }
}

internal fun pcmSnrDb(signal: ShortArray, noisy: ShortArray): Float {
    val n = min(signal.size, noisy.size)
    if (n == 0) return 0f
    var sig = 0.0
    var noise = 0.0
    for (i in 0 until n) {
        val s = signal[i].toDouble()
        val e = noisy[i].toDouble() - s
        sig += s * s
        noise += e * e
    }
    if (noise <= 1.0) return 80f
    return (10.0 * ln(sig / noise) / ln(10.0)).toFloat()
}
