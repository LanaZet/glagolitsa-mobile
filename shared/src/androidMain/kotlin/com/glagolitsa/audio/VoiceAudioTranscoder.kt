// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedPcm(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val channelCount: Int,
)

internal object VoiceAudioTranscoder {
    fun decodeToPcm(source: File): DecodedPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Нет аудиодорожки")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный кодек")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val pcm = ArrayList<Short>(sampleRate * channels * 8)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        appendPcm(outBuf, info, pcm)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val samples = pcm.toShortArray()
        val mono = if (channels > 1) downmixToMono(samples, channels) else samples
        return DecodedPcm(mono, sampleRate, 1)
    }

    fun encodeAac(pcm: ShortArray, sampleRateHz: Int, target: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC LC
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("Нет AAC-кодировщика")
        val codec = MediaCodec.createByCodecName(codecName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var outputDone = false
        var track = -1
        var presentationUs = 0L
        val frameSamples = (sampleRateHz / 50).coerceAtLeast(256)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs, 0)
                        } else if (offset >= pcm.size) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val count = minOf(frameSamples, pcm.size - offset)
                            inBuf.clear()
                            for (i in 0 until count) {
                                inBuf.putShort(pcm[offset + i])
                            }
                            val bytes = count * 2
                            codec.queueInputBuffer(inIndex, 0, bytes, presentationUs, 0)
                            presentationUs += (count * 1_000_000L) / sampleRateHz
                            offset += count
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0 && track >= 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun appendPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo, into: ArrayList<Short>) {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.position(info.offset)
        view.limit(info.offset + info.size)
        val shorts = ShortArray(view.remaining() / 2)
        view.asShortBuffer().get(shorts)
        shorts.forEach { into.add(it) }
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        val frames = samples.size / channels
        val mono = ShortArray(frames)
        var i = 0
        var o = 0
        while (o < frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += samples[i + c]
            }
            mono[o] = (acc / channels).toShort()
            i += channels
            o++
        }
        return mono
    }
}
