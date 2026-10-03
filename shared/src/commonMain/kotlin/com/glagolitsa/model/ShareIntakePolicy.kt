// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Rules for inbound shares (YouTube, Telegram, browser «Поделиться» → Глаголица).
 * Pure — no Android Intent / Compose.
 */
object ShareIntakePolicy {
    /** Soft cap so a shared dump doesn't blow up encryption payload. */
    const val MAX_SHARED_TEXT_LENGTH = 4_000

    private val urlRegex = Regex(
        pattern = """https?://[^\s<>"'`]+""",
        option = RegexOption.IGNORE_CASE,
    )

    fun normalizeSharedText(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_SHARED_TEXT_LENGTH)
    }

    /** First http(s) URL in shared body, if any (for subtitle preview). */
    fun primaryUrl(text: String): String? =
        urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '»', '"', '\'')

    /**
     * Picker order: channels first (user asked to post links into channels),
     * then groups, then DMs — alphabetical within each bucket.
     */
    fun orderShareTargets(chats: List<Chat>): List<Chat> =
        chats.sortedWith(
            compareBy<Chat> {
                when {
                    it.isChannel -> 0
                    it.isGroup -> 1
                    else -> 2
                }
            }.thenBy { it.title.lowercase() },
        )

    fun kindLabel(chat: Chat): String = when {
        chat.isChannel && chat.isPublicChannel && !chat.slug.isNullOrBlank() -> "@${chat.slug}"
        chat.isChannel && chat.isPublicChannel -> "Публичный канал"
        chat.isChannel -> "Личный канал"
        chat.isGroup -> "Группа"
        chat.isDirectMessage -> "Личный чат"
        else -> "Чат"
    }
}
