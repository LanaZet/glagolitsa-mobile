// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Rules for editing own messages (Telegram/WhatsApp-style).
 * Pure — no Compose / IO.
 */
object MessageEditPolicy {

    /**
     * Own, already delivered (not sending/failed), non-empty text body.
     * Attachment-only placeholders are not editable.
     */
    fun canEdit(
        message: Message,
        currentUserId: String?,
        isSystemMessage: Boolean = false,
    ): Boolean {
        if (isSystemMessage) return false
        if (currentUserId.isNullOrBlank() || message.sender_id != currentUserId) return false
        if (message.isSending() || message.isFailed()) return false
        val body = message.body.trim()
        if (body.isEmpty()) return false
        // Pure attachment label (📎 file) — no free text to edit.
        if (body.startsWith("📎") && body.indexOf('\n') < 0) return false
        return true
    }

    fun normalizeBody(raw: String): String = raw.trim()

    /** Commit only when body actually changes and is non-empty. */
    fun canCommitEdit(original: Message, draft: String): Boolean {
        val next = normalizeBody(draft)
        if (next.isEmpty()) return false
        return next != original.body.trim()
    }
}
