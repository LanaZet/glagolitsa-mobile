// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
actual fun rememberVoiceDictationController(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): VoiceDictationController {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)
    val latestOnError by rememberUpdatedState(onError)
    val controller = remember(context) {
        AndroidVoiceDictationController(
            context = context.applicationContext,
            onResult = { latestOnResult(it) },
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

private class AndroidVoiceDictationController(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) : VoiceDictationController {
    override var state by mutableStateOf(VoiceDictationState())
        private set

    var requestPermission: (() -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null
    private var cancelledByUser = false

    override fun start() {
        cancelledByUser = false
        if (!hasRecordAudioPermission()) {
            requestPermission?.invoke()
            return
        }
        startListening()
    }

    override fun stop() {
        state = state.copy(status = VoiceDictationStatus.Processing, errorMessage = null)
        recognizer?.stopListening()
    }

    override fun cancel() {
        cancelledByUser = true
        recognizer?.cancel()
        state = VoiceDictationState()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            startListening()
        } else {
            fail("Нет доступа к микрофону")
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListening() {
        val speechRecognizer = recognizer ?: createRecognizer() ?: return
        state = VoiceDictationState(status = VoiceDictationStatus.Listening)
        speechRecognizer.startListening(recognitionIntent())
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val message = "Распознавание речи недоступно на устройстве"
            state = VoiceDictationState(
                status = VoiceDictationStatus.Unsupported,
                errorMessage = message,
            )
            onError(message)
            return null
        }
        val speechRecognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                .getOrElse { SpeechRecognizer.createSpeechRecognizer(context) }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = speechRecognizer
        speechRecognizer.setRecognitionListener(listener)
        return speechRecognizer
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = state.copy(
                status = VoiceDictationStatus.Listening,
                errorMessage = null,
            )
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            state = state.copy(status = VoiceDictationStatus.Processing)
        }

        override fun onError(error: Int) {
            if (cancelledByUser) {
                cancelledByUser = false
                return
            }
            fail(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            val text = bestResult(results)
            state = VoiceDictationState()
            if (text.isBlank()) {
                onError("Не удалось распознать речь")
            } else {
                onResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestResult(partialResults)
            if (text.isNotBlank()) {
                state = state.copy(
                    status = VoiceDictationStatus.Listening,
                    partialText = text,
                    errorMessage = null,
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()

    private fun fail(message: String) {
        recognizer?.cancel()
        state = VoiceDictationState(errorMessage = message)
        onError(message)
    }

    private fun errorMessage(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи с микрофона"
            SpeechRecognizer.ERROR_CLIENT -> "Не удалось запустить распознавание"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> "Распознавание речи временно недоступно"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышала, попробуйте еще раз"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон уже занят"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не слышу речь"
            else -> "Не удалось распознать речь"
        }

    private fun hasRecordAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
}
