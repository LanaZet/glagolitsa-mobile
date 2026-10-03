// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glagolitsa.model.MessageAction
import com.glagolitsa.model.MessageActionPolicy
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.ui.profile.decodeImageBytes
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentMediaViewer(
    preview: AttachmentPreview,
    actions: List<MessageAction>,
    onAction: (MessageAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var menuExpanded by remember(preview.messageId) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .testTag("attachment-media-viewer"),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    AttachmentViewerBody(preview = preview)
                }
            }

            if (actions.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 18.dp, end = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.46f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                            .clickable { menuExpanded = true }
                            .semantics {
                                role = Role.Button
                                contentDescription = "Действия с вложением"
                            }
                            .testTag("attachment-viewer-menu"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(GlagolitsaColors.Surface800),
                    ) {
                        actions.forEach { action ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        MessageActionPolicy.label(action),
                                        color = if (MessageActionPolicy.isDestructive(action)) {
                                            GlagolitsaColors.AccentRed
                                        } else {
                                            GlagolitsaColors.TextPrimary
                                        },
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAction(action)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentViewerBody(preview: AttachmentPreview) {
    val description = preview.fileName ?: when {
        preview.isVideo -> "Видео"
        preview.isAnimatedGif -> "GIF"
        else -> "Изображение"
    }
    val mediaRatio = run {
        val w = preview.width
        val h = preview.height
        if (w != null && h != null && w > 0 && h > 0) {
            (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.4f)
        } else if (preview.isVideo) {
            16f / 9f
        } else {
            0.72f
        }
    }
    when {
        preview.isVideo && preview.bytes.isNotEmpty() -> {
            AttachmentVideoPlayer(
                bytes = preview.bytes,
                mimeType = preview.mimeType,
                contentDescription = description,
                modifier = Modifier.fillMaxWidth().aspectRatio(mediaRatio),
            )
        }
        preview.isAnimatedGif && preview.bytes.isNotEmpty() -> {
            AttachmentAnimatedGif(
                bytes = preview.bytes,
                contentDescription = description,
                modifier = Modifier.fillMaxWidth().aspectRatio(mediaRatio),
            )
        }
        else -> {
            StillPreview(preview = preview, contentDescription = description)
        }
    }
}

@Composable
private fun StillPreview(preview: AttachmentPreview, contentDescription: String) {
    val imageBytes = preview.displayBytes
    var imageBitmap by remember(preview.messageId, imageBytes.contentHashCode()) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(preview.messageId, imageBytes.contentHashCode()) {
        imageBitmap = null
        if (imageBytes.isNotEmpty()) {
            imageBitmap = withContext(Dispatchers.Default) { decodeImageBytes(imageBytes) }
        }
    }
    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.4f, 2.4f),
                ),
        )
    } else {
        Text(
            text = contentDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
