// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.ui.profile.decodeImageBytes
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ChatMediaFilter {
    All,
    Media,
    Documents,
}

@Composable
internal fun ChatMediaFilesPanel(
    files: List<ChatMediaFile>,
    selectedFilter: ChatMediaFilter,
    attachmentPreviews: Map<String, AttachmentPreview>,
    onFilterChange: (ChatMediaFilter) -> Unit,
    onCollapse: () -> Unit,
    onFileClick: (ChatMediaFile) -> Unit,
) {
    val visibleFiles = remember(files, selectedFilter) {
        files.filter { file ->
            when (selectedFilter) {
                ChatMediaFilter.All -> true
                ChatMediaFilter.Media -> file.kind == ChatMediaKind.Media
                ChatMediaFilter.Documents -> file.kind == ChatMediaKind.Document
            }
        }
    }

    InfoSection(
        title = "Все медиа и файлы",
        trailing = {
            CollapseTextButton(
                text = "Свернуть",
                onClick = onCollapse,
                contentDescription = "Свернуть медиа и файлы",
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChip(
                label = "Все",
                selected = selectedFilter == ChatMediaFilter.All,
                onClick = { onFilterChange(ChatMediaFilter.All) },
            )
            ThemeChip(
                label = "Фото и видео",
                selected = selectedFilter == ChatMediaFilter.Media,
                onClick = { onFilterChange(ChatMediaFilter.Media) },
            )
            ThemeChip(
                label = "Документы",
                selected = selectedFilter == ChatMediaFilter.Documents,
                onClick = { onFilterChange(ChatMediaFilter.Documents) },
            )
        }
        if (visibleFiles.isEmpty()) {
            Text(
                text = "Нет файлов",
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
            )
        } else {
            visibleFiles.forEach { file ->
                ChatMediaFileRow(
                    file = file,
                    preview = attachmentPreviews[file.messageId],
                    onClick = { onFileClick(file) },
                )
            }
        }
    }
}

@Composable
internal fun ChatInfoMediaPreviewDialog(
    preview: AttachmentPreview,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var imageBitmap by remember(preview.messageId, preview.bytes.contentHashCode()) { mutableStateOf<ImageBitmap?>(null) }
    var showDownloadAction by remember(preview.messageId, preview.bytes.contentHashCode()) { mutableStateOf(false) }
    var downloadBusy by remember(preview.messageId, preview.bytes.contentHashCode()) { mutableStateOf(false) }
    var downloadStatus by remember(preview.messageId, preview.bytes.contentHashCode()) { mutableStateOf<String?>(null) }
    LaunchedEffect(preview.messageId, preview.bytes.contentHashCode()) {
        imageBitmap = withContext(Dispatchers.Default) {
            decodeImageBytes(preview.bytes)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.44f),
                            Color.Black.copy(alpha = 0.22f),
                        ),
                    ),
                )
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = imageBitmap
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.92f)
                        .clickable { },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = preview.fileName ?: "Превью вложения",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .aspectRatio(
                                (bitmap.width.toFloat() / bitmap.height.toFloat())
                                    .coerceIn(0.4f, 2.4f),
                            )
                            .clickable { showDownloadAction = true },
                    )
                    if (showDownloadAction && AttachmentDownloadSaver.isSupported && preview.isImage) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(
                                enabled = !downloadBusy,
                                onClick = {
                                    downloadStatus = null
                                    downloadBusy = true
                                    scope.launch {
                                        runCatching { AttachmentDownloadSaver.saveImage(preview) }
                                            .onSuccess { savedTo ->
                                                downloadStatus = "Сохранено: $savedTo"
                                            }
                                            .onFailure { err ->
                                                downloadStatus = err.message ?: "Не удалось загрузить изображение"
                                            }
                                        downloadBusy = false
                                    }
                                },
                            ) {
                                Text(if (downloadBusy) "Загрузка..." else "Загрузить")
                            }
                            downloadStatus?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlagolitsaColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(color = GlagolitsaColors.OrnamentGold)
            }
        }
    }
}

@Composable
private fun ChatMediaFileRow(
    file: ChatMediaFile,
    preview: AttachmentPreview?,
    onClick: (() -> Unit)? = null,
) {
    var previewBitmap by remember(preview?.messageId, preview?.bytes?.contentHashCode()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preview?.messageId, preview?.bytes?.contentHashCode()) {
        previewBitmap = when {
            preview?.isImage == true -> withContext(Dispatchers.Default) {
                decodeImageBytes(preview.bytes)
            }
            else -> null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = previewBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlagolitsaColors.Surface700.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (file.kind == ChatMediaKind.Media) "IMG" else "DOC",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
            )
            Text(
                text = file.meta,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
            )
        }
    }
}
