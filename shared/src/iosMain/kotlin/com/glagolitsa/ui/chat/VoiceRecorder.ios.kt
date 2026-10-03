// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.glagolitsa.audio.VoiceRecordingDraft
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.platform.iosRemoveFile
import com.glagolitsa.platform.iosProtectTemporaryFile
import com.glagolitsa.platform.iosRunOnMain
import com.glagolitsa.platform.iosTemporaryFileUrl
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import com.glagolitsa.platform.toByteArray
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithInt
import platform.Foundation.numberWithUnsignedInt

private const val VOICE_MAX_BYTES = 25L * 1024L * 1024L
private const val VOICE_MIN_DURATION_MS = 700L

@Composable
actual fun rememberVoiceRecorderController(
    onDraft: (VoiceRecordingDraft) -> Unit,
    onError: (String) -> Unit,
): VoiceRecorderController {
    val scope = rememberCoroutineScope()
    val latestOnDraft by rememberUpdatedState(onDraft)
    val latestOnError by rememberUpdatedState(onError)
    val controller = remember(scope) {
        IosVoiceRecorderController(
            scope = scope,
            onDraft = { latestOnDraft(it) },
            onError = { latestOnError(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosVoiceRecorderController(
    private val scope: CoroutineScope,
    private val onDraft: (VoiceRecordingDraft) -> Unit,
    private val onError: (String) -> Unit,
) : VoiceRecorderController {
    override var state by mutableStateOf(VoiceRecorderState())
        private set

    private var recorder: AVAudioRecorder? = null
    private var outputUrl: NSURL? = null
    private var tickerJob: Job? = null
    private var processingJob: Job? = null
    private var closed = false

    override fun start() {
        if (closed || state.isActive) return
        val session = AVAudioSession.sharedInstance()
        when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> startRecording()
            else -> session.requestRecordPermission { granted ->
                iosRunOnMain {
                    if (closed) return@iosRunOnMain
                    if (granted) startRecording() else fail("Нет доступа к микрофону")
                }
            }
        }
    }

    override fun stop() {
        val startedAt = state.startedAtEpochMs
        if (!state.isRecording || startedAt <= 0L) return
        val durationMs = (currentTimeMillis() - startedAt).coerceAtLeast(0L)
        state = state.copy(status = VoiceRecorderStatus.Processing, elapsedMs = durationMs, errorMessage = null)
        tickerJob?.cancel()
        tickerJob = null
        val url = outputUrl
        val current = recorder
        recorder = null
        outputUrl = null
        current?.stop()
        current?.delegate = null
        processingJob = scope.launch {
            try {
                val draft = withContext(Dispatchers.Default) {
                    try {
                        if (durationMs < VOICE_MIN_DURATION_MS) {
                            throw IllegalArgumentException("Слишком короткое голосовое")
                        }
                        val path = url?.path ?: throw IllegalStateException("Файл записи не найден")
                        val data = NSData.dataWithContentsOfFile(path)
                            ?: throw IllegalStateException("Не удалось сохранить голосовое")
                        if (data.length > VOICE_MAX_BYTES.toULong()) {
                            throw IllegalArgumentException("Голосовое слишком большое")
                        }
                        val bytes = data.toByteArray()
                        if (bytes.isEmpty()) throw IllegalArgumentException("Пустое голосовое нельзя отправить")
                        VoiceRecordingDraft(
                            original = PickedAttachment(
                                bytes = bytes,
                                fileName = "voice-${currentTimeMillis()}-orig.m4a",
                                mimeType = "audio/mp4",
                                kind = AttachmentKind.VOICE,
                                durationMs = durationMs,
                            ),
                        )
                    } finally {
                        iosRemoveFile(url)
                    }
                }
                state = VoiceRecorderState()
                onDraft(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = VoiceRecorderState(errorMessage = error.message ?: "Не удалось записать голосовое")
                onError(state.errorMessage.orEmpty())
            } finally {
                if (processingJob === coroutineContext[Job]) processingJob = null
            }
        }
    }

    override fun cancel() {
        tickerJob?.cancel()
        tickerJob = null
        processingJob?.cancel()
        processingJob = null
        recorder?.stop()
        recorder = null
        iosRemoveFile(outputUrl)
        outputUrl = null
        state = VoiceRecorderState()
    }

    fun destroy() {
        closed = true
        cancel()
    }

    private fun startRecording() {
        val session = AVAudioSession.sharedInstance()
        val sessionConfigured = runCatching {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        }.getOrDefault(false)
        if (!sessionConfigured) {
            fail("Не удалось настроить микрофон")
            return
        }
        val url = iosTemporaryFileUrl("glagolitsa-voice", "m4a")
        val settings = hashMapOf<Any?, Any>(
            AVFormatIDKey to NSNumber.numberWithUnsignedInt(kAudioFormatMPEG4AAC),
            AVSampleRateKey to NSNumber.numberWithDouble(44_100.0),
            AVNumberOfChannelsKey to NSNumber.numberWithInt(1),
            AVEncoderAudioQualityKey to NSNumber.numberWithInt(AVAudioQualityHigh.toInt()),
        )
        val next = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!next.prepareToRecord()) {
            iosRemoveFile(url)
            fail("Не удалось запустить запись")
            return
        }
        if (runCatching { iosProtectTemporaryFile(url) }.isFailure) {
            iosRemoveFile(url)
            fail("Не удалось защитить файл записи")
            return
        }
        if (!next.record()) {
            iosRemoveFile(url)
            fail("Не удалось запустить запись")
            return
        }
        recorder = next
        outputUrl = url
        val startedAt = currentTimeMillis()
        state = VoiceRecorderState(
            status = VoiceRecorderStatus.Recording,
            startedAtEpochMs = startedAt,
        )
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(250)
                if (state.status == VoiceRecorderStatus.Recording) {
                    state = state.copy(elapsedMs = currentTimeMillis() - startedAt)
                }
            }
        }
    }

    private fun fail(message: String) {
        state = VoiceRecorderState(errorMessage = message)
        onError(message)
    }
}
