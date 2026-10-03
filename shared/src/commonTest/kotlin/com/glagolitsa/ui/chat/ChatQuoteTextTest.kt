// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatQuoteTextTest {
    @Test
    fun threadRootPreviewDropsInlineReplyPrefix() {
        assertEquals(
            "hh",
            "↩ Вы: v hh".normalizeThreadRootPreview(replyBody = "v"),
        )
    }

    @Test
    fun threadRootPreviewKeepsPlainBody() {
        assertEquals(
            "Обычное сообщение",
            "Обычное сообщение".normalizeThreadRootPreview(),
        )
    }

    @Test
    fun quotePreviewUsesReadableImageAttachmentLabel() {
        assertEquals(
            "Фото",
            "📎 portrait.jpg (120 байт)".normalizeQuotePreview(),
        )
    }

    @Test
    fun quotePreviewUsesReadableVideoAttachmentLabel() {
        assertEquals(
            "Видео",
            "📎 clip.mp4".normalizeQuotePreview(),
        )
    }

    @Test
    fun quotePreviewUsesReadableDocumentAttachmentLabel() {
        assertEquals(
            "Документ",
            "📎 report.pdf".normalizeQuotePreview(),
        )
    }
}
