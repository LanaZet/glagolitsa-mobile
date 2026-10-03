// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberAttachmentPicker(onResult: (PickedAttachment?) -> Unit): AttachmentPickerActions =
    remember {
        AttachmentPickerActions(
            pickPhotoOrVideo = { onResult(null) },
            pickFile = { onResult(null) },
            openCamera = { onResult(null) },
        )
    }
