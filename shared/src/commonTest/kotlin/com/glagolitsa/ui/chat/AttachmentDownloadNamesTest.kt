// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentDownloadNamesTest {
    @Test
    fun displayNameStripsUnsafePathCharacters() {
        assertEquals(
            "secret_photo.png",
            AttachmentDownloadNames.displayName("../secret/photo.jpg", "image/png", fallbackMillis = 42),
        )
    }

    @Test
    fun displayNameFallsBackWhenNameIsUnsafe() {
        assertEquals(
            "glagolitsa-42.jpg",
            AttachmentDownloadNames.displayName("..", "image/jpeg", fallbackMillis = 42),
        )
    }
}
