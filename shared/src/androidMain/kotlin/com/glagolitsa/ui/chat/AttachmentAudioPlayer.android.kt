// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glagolitsa.repository.AttachmentPreview
import java.io.File
import java.util.UUID

@Composable
actual fun rememberAttachmentAudioPlayerController(): AttachmentAudioPlayerController {
    val context = LocalContext.current
    val controller = remember(context) {
        AndroidAttachmentAudioPlayerController(context.applicationContext)
    }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}

private class AndroidAttachmentAudioPlayerController(
    private val context: Context,
) : AttachmentAudioPlayerController {
    override var state by mutableStateOf(AttachmentAudioPlayerState())
        private set

    private var player: MediaPlayer? = null
    private var tempFile: File? = null

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
        val file = File(context.cacheDir, "attachment-audio-${UUID.randomUUID()}.m4a")
        runCatching {
            file.writeBytes(preview.bytes)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    this@AndroidAttachmentAudioPlayerController.stop()
                }
                setOnErrorListener { _, _, _ ->
                    this@AndroidAttachmentAudioPlayerController.stop()
                    state = AttachmentAudioPlayerState(errorMessage = "Не удалось воспроизвести голосовое")
                    true
                }
                prepare()
                start()
            }
        }.onSuccess { mediaPlayer ->
            tempFile = file
            player = mediaPlayer
            val resolvedDuration = mediaPlayer.duration.toLong()
                .takeIf { it > 0L }
                ?: preview.durationMs?.takeIf { it > 0L }
                ?: 0L
            state = AttachmentAudioPlayerState(
                playingMessageId = preview.messageId,
                durationMs = resolvedDuration,
            )
        }.onFailure {
            file.delete()
            stop()
            state = AttachmentAudioPlayerState(errorMessage = "Не удалось воспроизвести голосовое")
        }
    }

    override fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        tempFile?.delete()
        tempFile = null
        state = AttachmentAudioPlayerState()
    }

    fun destroy() {
        stop()
    }
}
