// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Glagolitsa keeps profile text separate from status-like runtime channels.
 *
 * 1. **Network presence** — realtime connectivity from the presence service
 *    ([UserPresenceView]: online / offline / in_call / hidden + last-seen buckets).
 *    Controlled by privacy ([PresencePrivacySettings]) and shown as «в сети» / «не в сети».
 *
 * 2. **Short profile status** — free-text profile field [User.status].
 *
 * 3. **Profile about** — free-text profile field [User.bio].
 *
 * 4. **Manual presence mode** — profile preference [User.presence]
 *    ([UserPresence]: online / away / dnd / offline). Availability mode, not the
 *    realtime line under a chat title and not profile about text.
 *
 * UI must not mix these channels in one string or one API call's primary payload.
 */
object StatusChannels {

    // ── Network privacy ────────────────────────────────────────────────────

    /** True when contacts may learn online / last-seen from the presence service. */
    fun isNetworkStatusVisible(privacy: PresencePrivacySettings): Boolean =
        privacy.online_visibility != PresenceVisibility.NOBODY ||
            privacy.last_seen_visibility != PresenceVisibility.NOBODY

    /**
     * Toggle both online + last-seen visibility together (profile network switch).
     * Does not touch profile about or manual presence mode.
     */
    fun privacyForNetworkVisible(visible: Boolean): PresencePrivacySettings {
        val v = if (visible) PresenceVisibility.CONTACTS else PresenceVisibility.NOBODY
        return PresencePrivacySettings(
            online_visibility = v,
            last_seen_visibility = v,
        )
    }

    fun updatePrivacyRequest(visible: Boolean): UpdatePresencePrivacyRequest {
        val v = if (visible) PresenceVisibility.CONTACTS else PresenceVisibility.NOBODY
        return UpdatePresencePrivacyRequest(
            online_visibility = v,
            last_seen_visibility = v,
        )
    }

    /**
     * Profile card copy for the **network** switch only.
     * Does not include profile about text or manual presence mode labels
     * (avoids «Скрыт виден контактам»).
     */
    fun profileNetworkVisibilityCaption(visible: Boolean): String =
        if (visible) {
            "Сетевой статус виден контактам"
        } else {
            "Сетевой статус скрыт от других"
        }

    // ── Network presence line (chat / rings) ───────────────────────────────

    /**
     * Realtime line under a DM title or on chat info.
     * Uses only [UserPresenceView] — never [User.bio] / profile about text.
     */
    fun networkPresenceLabel(view: UserPresenceView?): String =
        view?.statusLabel()?.takeIf { it.isNotBlank() } ?: "не в сети"

    /**
     * Chat info network line for a DM when only a preformatted presence string is known.
     * Empty → neutral network placeholder (not profile about).
     */
    fun networkPresenceLabelOrUnknown(preformatted: String?): String =
        preformatted?.takeIf { it.isNotBlank() } ?: "нет данных о сети"

    // ── Profile text ───────────────────────────────────────────────────────

    /**
     * Free-text short profile status for profile / chat info.
     * Never falls back to network labels or profile about.
     */
    fun profileStatusLabel(status: String?): String? =
        status?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Free-text profile "About" for profile / chat info.
     * Never falls back to short status or network labels.
     */
    fun profileAboutLabel(bio: String?): String? =
        ProfileAboutPolicy.peerAboutLabel(bio)

    fun profileAboutLabelOrPlaceholder(bio: String?): String =
        ProfileAboutPolicy.peerAboutLabelOrPlaceholder(bio)

    data class ProfileTextLines(
        val status: String?,
        val about: String?,
    )

    fun profileTextLines(status: String?, bio: String?): ProfileTextLines = ProfileTextLines(
        status = profileStatusLabel(status),
        about = profileAboutLabel(bio),
    )

    // ── Chat info composition (kept separate fields) ───────────────────────

    /**
     * Pair of independent lines for chat info header.
     * [status] is a short profile phrase, [about] is bio, and [networkPresence]
     * is realtime presence — never merged.
     */
    data class ChatInfoStatusLines(
        val status: String?,
        val about: String?,
        val networkPresence: String,
    )

    fun chatInfoStatusLines(
        status: String?,
        about: String?,
        networkPresence: UserPresenceView?,
        preformattedNetwork: String? = null,
    ): ChatInfoStatusLines = ChatInfoStatusLines(
        status = profileStatusLabel(status),
        about = profileAboutLabel(about),
        networkPresence = when {
            !preformattedNetwork.isNullOrBlank() -> preformattedNetwork
            else -> networkPresenceLabel(networkPresence)
        },
    )

    // ── Profile update: about must not clobber presence mode ───────────────

    /**
     * Build a profile update that changes **only** about text,
     * preserving display name, avatar, and manual presence mode.
     */
    fun profileUpdateForAbout(
        user: User,
        about: String,
    ): ProfileUpdateInput = ProfileAboutPolicy.profileUpdateForAbout(user, about)

    /**
     * True when [candidate] looks like a **network** label, not free-text profile about.
     * Used defensively so UI never writes network copy into profile about by mistake.
     */
    fun looksLikeNetworkPresenceLabel(candidate: String?): Boolean =
        ProfileAboutPolicy.looksLikeRuntimeStatusLabel(candidate)
}
