// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Manual availability mode stored on the profile ([User.presence]).
 *
 * Not the same as realtime [UserPresenceView] (network line under chat titles)
 * and not free-text profile about ([User.bio]).
 *
 * Offline mode means «appear offline / invisible» to contacts when privacy allows
 * showing a mode — it is **not** the privacy switch «скрыть сетевой статус».
 */
enum class UserPresence(
    val apiValue: String,
    val label: String,
) {
    Online("online", "В сети"),
    Away("away", "Отошёл"),
    Dnd("dnd", "Не беспокоить"),
    /** Manual «appear offline» preference — not privacy and not profile about text. */
    Offline("offline", "Невидимый"),
    ;

    companion object {
        fun fromApi(value: String?): UserPresence =
            entries.find { it.apiValue == value?.lowercase() } ?: Online
    }
}
