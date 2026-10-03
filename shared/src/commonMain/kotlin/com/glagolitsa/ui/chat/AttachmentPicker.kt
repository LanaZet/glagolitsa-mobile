// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import com.glagolitsa.media.AttachmentKind

data class PickedAttachment(
    val bytes: ByteArray = byteArrayOf(),
    val fileName: String?,
    val mimeType: String?,
    val errorMessage: String? = null,
    val encryptedSpool: PickedEncryptedAttachmentSpool? = null,
    val kind: AttachmentKind = AttachmentKind.UNKNOWN,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailBytes: ByteArray? = null,
    val waveform: List<Int> = emptyList(),
)

data class PickedEncryptedAttachmentSpool(
    val path: String,
    val fileKey: ByteArray,
    val encryptedSize: Long,
    val plaintextSize: Long,
)

data class AttachmentPickerActions(
    val pickPhotoOrVideo: () -> Unit,
    val pickFile: () -> Unit,
    val openCamera: () -> Unit,
)

@Composable
expect fun rememberAttachmentPicker(onResult: (PickedAttachment?) -> Unit): AttachmentPickerActions
