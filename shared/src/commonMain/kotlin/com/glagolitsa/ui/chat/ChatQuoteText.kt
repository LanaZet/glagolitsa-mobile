// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

internal const val EMPTY_QUOTE_LABEL = "Без текста"
internal const val UNKNOWN_SENDER_LABEL = "Автор"

private val imageAttachmentPreviewExt = Regex(
    """\.(png|jpe?g|webp|gif|heic|heif)(\b|$)""",
    RegexOption.IGNORE_CASE,
)
private val videoAttachmentPreviewExt = Regex(
    """\.(mp4|mov|m4v|webm|mkv|avi)(\b|$)""",
    RegexOption.IGNORE_CASE,
)

fun String.normalizeQuotePreview(): String =
    replace('\n', ' ')
        .trim()
        .attachmentQuoteLabel()
        .ifBlank { EMPTY_QUOTE_LABEL }

private fun String.attachmentQuoteLabel(): String {
    if (!startsWith("📎")) return this
    return when {
        imageAttachmentPreviewExt.containsMatchIn(this) -> "Фото"
        videoAttachmentPreviewExt.containsMatchIn(this) -> "Видео"
        else -> "Документ"
    }
}

fun String.normalizeThreadRootPreview(replyBody: String? = null): String {
    val normalized = normalizeQuotePreview()
    val replyPrefix = Regex("^↩\\s*[^:]{1,48}:\\s*")
    val withoutPrefix = normalized
        .replace(replyPrefix, "")
        .trim()
        .ifBlank { normalized }
    val quotedBody = replyBody
        ?.normalizeQuotePreview()
        ?.takeIf { it != EMPTY_QUOTE_LABEL }
        ?: return withoutPrefix

    return withoutPrefix
        .removePrefix(quotedBody)
        .trim()
        .ifBlank { withoutPrefix }
}

fun replySenderLabel(senderId: String, currentUserId: String?, displayName: String?): String = when {
    senderId == currentUserId -> "Вы"
    displayName.isNullOrBlank() -> UNKNOWN_SENDER_LABEL
    else -> displayName
}
