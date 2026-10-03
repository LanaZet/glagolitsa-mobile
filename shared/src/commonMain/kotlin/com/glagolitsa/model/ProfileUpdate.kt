// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Параметры сохранения профиля: UI собирает их в форму, repository отправляет на сервер
 * и применяет только ответ сервера, чтобы другие пользователи видели те же данные.
 */
data class ProfileUpdateInput(
    val displayName: String,
    val position: String,
    val status: String,
    val bio: String,
    val avatarUrl: String?,
    val presence: String,
) {
    /** Убираем пробелы по краям перед отправкой. */
    fun normalized(): ProfileUpdateInput = copy(
        displayName = displayName.trim(),
        position = position.trim(),
        status = status.trim(),
        bio = bio.trim(),
    )
}

/**
 * Применяет правки к кэшированному пользователю.
 * Пустые строки превращаются в null — поле считается очищенным.
 */
fun User.applyProfileEdits(input: ProfileUpdateInput): User = copy(
    display_name = input.displayName.takeIf { it.isNotBlank() },
    position = input.position.takeIf { it.isNotBlank() },
    status = input.status.takeIf { it.isNotBlank() },
    bio = input.bio.takeIf { it.isNotBlank() },
    avatar_url = input.avatarUrl?.takeIf { it.isNotBlank() },
    presence = input.presence,
)
