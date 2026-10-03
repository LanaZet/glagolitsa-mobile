// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glagolitsa.audio.AudioProcessingCoordinator
import com.glagolitsa.audio.SpectralNoiseSuppressor
import com.glagolitsa.audio.VoiceAudioTranscoder
import com.glagolitsa.audio.VoiceEnhancerEngine
import com.glagolitsa.audio.VoiceRecordingDraft
import com.glagolitsa.media.AttachmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val VOICE_MAX_BYTES = 25L * 1024L * 1024L
private const val VOICE_MIN_DURATION_MS = 700L

@Composable
actual fun rememberVoiceRecorderController(
    onDraft: (VoiceRecordingDraft) -> Unit,
    onError: (String) -> Unit,
): VoiceRecorderController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnDraft by rememberUpdatedState(onDraft)
    val latestOnError by rememberUpdatedState(onError)
    val controller = remember(context, scope) {
        AndroidVoiceRecorderController(
            context = context.applicationContext,
            scope = scope,
            onDraft = { latestOnDraft(it) },
            onError = { latestOnError(it) },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onPermissionResult(granted)
    }
    controller.requestPermission = {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }

    return controller
}

private class AndroidVoiceRecorderController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDraft: (VoiceRecordingDraft) -> Unit,
    private val onError: (String) -> Unit,
) : VoiceRecorderController {
    override var state by mutableStateOf(VoiceRecorderState())
        private set

    var requestPermission: (() -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var tickerJob: Job? = null

    override fun start() {
        if (state.isActive) return
        if (!hasRecordAudioPermission()) {
            requestPermission?.invoke()
            return
        }
        startRecording()
    }

    override fun stop() {
        val startedAt = state.startedAtEpochMs
        if (!state.isRecording || startedAt <= 0L) return
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        state = state.copy(status = VoiceRecorderStatus.Processing, elapsedMs = durationMs, errorMessage = null)
        tickerJob?.cancel()
        tickerJob = null
        val file = outputFile
        val currentRecorder = recorder
        recorder = null
        outputFile = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stopResult = runCatching { currentRecorder?.stop() }
                    runCatching { currentRecorder?.release() }
                    stopResult.getOrElse {
                        file?.delete()
                        throw IllegalStateException("Не удалось сохранить голосовое")
                    }
                    if (durationMs < VOICE_MIN_DURATION_MS) {
                        file?.delete()
                        throw IllegalArgumentException("Слишком короткое голосовое")
                    }
                    val source = file ?: throw IllegalStateException("Файл записи не найден")
                    buildVoiceDraft(source, durationMs)
                }
            }.onSuccess { draft ->
                state = VoiceRecorderState()
                onDraft(draft)
            }.onFailure {
                state = VoiceRecorderState(errorMessage = it.message ?: "Не удалось записать голосовое")
                onError(state.errorMessage.orEmpty())
            }
        }
    }

    override fun cancel() {
        tickerJob?.cancel()
        tickerJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
        state = VoiceRecorderState()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            startRecording()
        } else {
            fail("Нет доступа к микрофону")
        }
    }

    fun destroy() {
        cancel()
    }

    private fun startRecording() {
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val nextRecorder = runCatching {
            createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse {
            file.delete()
            fail("Не удалось запустить запись")
            return
        }
        recorder = nextRecorder
        outputFile = file
        val startedAt = System.currentTimeMillis()
        state = VoiceRecorderState(
            status = VoiceRecorderStatus.Recording,
            startedAtEpochMs = startedAt,
        )
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(250)
                if (state.status == VoiceRecorderStatus.Recording) {
                    state = state.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                }
            }
        }
    }

    private fun fail(message: String) {
        state = VoiceRecorderState(errorMessage = message)
        onError(message)
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun hasRecordAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

    private fun buildVoiceDraft(source: File, durationMs: Long): VoiceRecordingDraft {
        if (!source.exists() || source.length() <= 0L) {
            source.delete()
            throw IllegalArgumentException("Пустое голосовое нельзя отправить")
        }
        if (source.length() > VOICE_MAX_BYTES) {
            source.delete()
            throw IllegalArgumentException("Голосовое слишком большое")
        }
        val originalBytes = source.readBytes()
        val plan = AudioProcessingCoordinator.resolveVoice()
        var processed: PickedAttachment? = null
        var applied = VoiceEnhancerEngine.None
        var waveform = emptyList<Int>()
        if (plan.shouldProcess) {
            val result = runCatching {
                val decoded = VoiceAudioTranscoder.decodeToPcm(source)
                waveform = SpectralNoiseSuppressor.waveformBars(decoded.samples)
                val (enhanced, engine) = AudioProcessingCoordinator.enhancePcm(
                    pcm = decoded.samples,
                    sampleRateHz = decoded.sampleRateHz,
                    plan = plan,
                )
                val cleaned = File(context.cacheDir, "voice-clean-${UUID.randomUUID()}.m4a")
                try {
                    VoiceAudioTranscoder.encodeAac(enhanced, decoded.sampleRateHz, cleaned)
                    if (!cleaned.exists() || cleaned.length() <= 0L) {
                        error("empty processed voice")
                    }
                    PickedAttachment(
                        bytes = cleaned.readBytes(),
                        fileName = "voice-${System.currentTimeMillis()}.m4a",
                        mimeType = "audio/mp4",
                        kind = AttachmentKind.VOICE,
                        durationMs = durationMs,
                        waveform = SpectralNoiseSuppressor.waveformBars(enhanced),
                    ) to engine
                } finally {
                    cleaned.delete()
                }
            }
            result.onSuccess { (attachment, engine) ->
                processed = attachment
                applied = engine
            }
        }
        source.delete()
        return VoiceRecordingDraft(
            original = PickedAttachment(
                bytes = originalBytes,
                fileName = "voice-${System.currentTimeMillis()}-orig.m4a",
                mimeType = "audio/mp4",
                kind = AttachmentKind.VOICE,
                durationMs = durationMs,
                waveform = waveform,
            ),
            processed = processed,
            requestedEngine = plan.requestedEngine,
            appliedEngine = applied,
            reason = plan.reason,
        )
    }
}
