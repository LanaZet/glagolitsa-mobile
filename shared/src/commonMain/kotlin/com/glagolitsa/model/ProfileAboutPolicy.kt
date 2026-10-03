// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Pure rules for the profile "About" text.
 *
 * Message delivery statuses live in [MessageStatus]. Network presence lives in
 * [UserPresenceView]/[PresenceStatus]. This policy is only for profile bio/about.
 */
object ProfileAboutPolicy {
    const val MAX_LENGTH = 280

    fun normalize(raw: String): String = raw.trim().take(MAX_LENGTH)

    fun hasChanges(draft: String, saved: String): Boolean =
        normalize(draft) != normalize(saved)

    fun peerAboutLabel(bio: String?): String? =
        bio?.trim()?.takeIf { it.isNotEmpty() }

    fun peerAboutLabelOrPlaceholder(bio: String?): String =
        peerAboutLabel(bio) ?: "Не указано"

    /**
     * Build a profile update that changes only about/bio.
     * The short profile status is independent and must be preserved.
     */
    fun profileUpdateForAbout(
        user: User,
        about: String,
    ): ProfileUpdateInput = ProfileUpdateInput(
        displayName = user.display_name.orEmpty(),
        position = user.position.orEmpty(),
        status = user.status.orEmpty(),
        bio = normalize(about),
        avatarUrl = user.avatar_url,
        presence = user.presenceState().apiValue,
    )

    fun isTransientSyncFailure(message: String?, httpStatus: Int? = null): Boolean {
        if (httpStatus != null && httpStatus in 500..599) return true
        val text = (message ?: "").lowercase()
        return text.contains("failed to connect") ||
            text.contains("connection refused") ||
            text.contains("connection reset") ||
            text.contains("unable to resolve host") ||
            text.contains("network is unreachable") ||
            text.contains("timeout") ||
            text.contains("timed out") ||
            text.contains("socket")
    }

    /**
     * Legacy User.status sometimes contains old presence/mode labels. Those are
     * not profile-about text and must not be surfaced as "About".
     */
    fun looksLikeRuntimeStatusLabel(candidate: String?): Boolean {
        val text = candidate?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        return text in RUNTIME_STATUS_LABELS ||
            text.startsWith("был") ||
            text.startsWith("была") ||
            text.startsWith("был(а)") ||
            text == "только что"
    }

    private val RUNTIME_STATUS_LABELS = setOf(
        "в сети",
        "не в сети",
        "в звонке",
        "online",
        "offline",
        "in_call",
        "hidden",
        "away",
        "dnd",
        "отошёл",
        "отошел",
        "не беспокоить",
        "скрыт",
    )
}
