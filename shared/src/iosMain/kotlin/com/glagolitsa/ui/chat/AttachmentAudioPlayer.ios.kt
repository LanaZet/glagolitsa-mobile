// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.glagolitsa.platform.iosRemoveFile
import com.glagolitsa.platform.iosWriteTemporaryFile
import com.glagolitsa.repository.AttachmentPreview
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSURL
import platform.darwin.NSObject

@Composable
actual fun rememberAttachmentAudioPlayerController(): AttachmentAudioPlayerController {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { IosAttachmentAudioPlayerController(scope) }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private class IosAttachmentAudioPlayerController(
    private val scope: CoroutineScope,
) : AttachmentAudioPlayerController {
    override var state by mutableStateOf(AttachmentAudioPlayerState())
        private set

    private var player: AVAudioPlayer? = null
    private var tempUrl: NSURL? = null
    private var prepareJob: Job? = null
    private val delegate = PlayerDelegate(::finishPlayback)

    override fun play(preview: AttachmentPreview) {
        if (state.playingMessageId == preview.messageId) {
            stop()
            return
        }
        if (preview.bytes.isEmpty()) {
            state = AttachmentAudioPlayerState(errorMessage = "Голосовое пока не загружено")
            return
        }
        stop()
        prepareJob = scope.launch {
            var pendingUrl: NSURL? = null
            runCatching {
                pendingUrl = withContext(Dispatchers.Default) {
                    iosWriteTemporaryFile(preview.bytes, "glagolitsa-audio", "m4a")
                }
                coroutineContext.ensureActive()
                check(AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null))
                val next = AVAudioPlayer(contentsOfURL = pendingUrl, error = null) ?: error("player")
                next.delegate = delegate
                if (!next.prepareToPlay()) error("prepare")
                if (!next.play()) error("play")
                tempUrl = pendingUrl
                pendingUrl = null
                player = next
                val resolvedDuration = (next.duration * 1000.0).toLong()
                    .takeIf { it > 0L }
                    ?: preview.durationMs?.takeIf { it > 0L }
                    ?: 0L
                state = AttachmentAudioPlayerState(
                    playingMessageId = preview.messageId,
                    durationMs = resolvedDuration,
                )
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    state = AttachmentAudioPlayerState(errorMessage = "Не удалось воспроизвести голосовое")
                }
            }
            iosRemoveFile(pendingUrl)
        }
    }

    override fun stop() {
        prepareJob?.cancel()
        prepareJob = null
        player?.delegate = null
        runCatching { player?.stop() }
        player = null
        iosRemoveFile(tempUrl)
        tempUrl = null
        state = AttachmentAudioPlayerState()
    }

    fun destroy() {
        stop()
    }

    private fun finishPlayback(completedPlayer: AVAudioPlayer, failed: Boolean) {
        if (player !== completedPlayer) return
        prepareJob = null
        completedPlayer.delegate = null
        player = null
        iosRemoveFile(tempUrl)
        tempUrl = null
        state = if (failed) {
            AttachmentAudioPlayerState(errorMessage = "Не удалось воспроизвести голосовое")
        } else {
            AttachmentAudioPlayerState()
        }
    }

    private class PlayerDelegate(
        private val onFinished: (AVAudioPlayer, Boolean) -> Unit,
    ) : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            onFinished(player, !successfully)
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: platform.Foundation.NSError?) {
            onFinished(player, true)
        }
    }
}
