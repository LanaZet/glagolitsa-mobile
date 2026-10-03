// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import com.glagolitsa.model.ProfileUpdateInput
import com.glagolitsa.model.User
import com.glagolitsa.model.UserPresence
import com.glagolitsa.model.presenceState

/**
 * Состояние формы профиля на экране.
 *
 * Живёт отдельно от [User] в SessionStore: пользователь редактирует поля,
 * не дожидаясь ответа сервера. При смене аккаунта подтягиваем данные через [fromUser].
 */
data class ProfileFormState(
    val displayName: String = "",
    val position: String = "",
    val status: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val presence: UserPresence = UserPresence.Online,
) {
    fun fromUser(user: User?): ProfileFormState = copy(
        displayName = user?.display_name.orEmpty(),
        position = user?.position.orEmpty(),
        status = user?.status.orEmpty(),
        bio = user?.bio.orEmpty(),
        avatarUrl = user?.avatar_url,
        presence = user?.presenceState() ?: UserPresence.Online,
    )

    fun toUpdateInput(): ProfileUpdateInput = ProfileUpdateInput(
        displayName = displayName,
        position = position,
        status = status,
        bio = bio,
        avatarUrl = avatarUrl,
        presence = presence.apiValue,
    ).normalized()
}

/** Ключи локальных настроек профиля (SQLDelight app_settings). */
object ProfileSettingsKeys {
    const val NOTIFICATIONS = "profile_notifications"
}