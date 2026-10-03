// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.runtime.Composable

/** Plaintext image for open channel media (never e2e spool). */
data class PickedChannelImage(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String? = null,
    val errorMessage: String? = null,
)

/**
 * Image-only picker for channel open uploads.
 * Separate from [com.glagolitsa.ui.chat.rememberAttachmentPicker] (e2e encrypted spool).
 */
@Composable
expect fun rememberChannelImagePicker(onResult: (PickedChannelImage?) -> Unit): () -> Unit
