// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform GIF / video surfaces for the in-chat media viewer.
 * Android and iOS play video; Android animates GIF. Desktop shows a still.
 */
@Composable
expect fun AttachmentAnimatedGif(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
)

@Composable
expect fun AttachmentVideoPlayer(
    bytes: ByteArray,
    mimeType: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
)
