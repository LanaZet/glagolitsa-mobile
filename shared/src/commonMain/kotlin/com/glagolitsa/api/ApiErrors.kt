// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

/**
 * Человекочитаемые сообщения об ошибках API.
 * Без dev-скриптов и сырых HTTP-кодов в UI.
 */
fun Throwable.profileSaveMessage(): String = when (this) {
    is ApiException -> when (status.value) {
        401 -> "Сессия истекла. Выйдите и войдите снова"
        in 500..599 -> "Сервер временно недоступен"
        else -> errorMessage?.takeIf { it.isNotBlank() } ?: "Не удалось сохранить профиль"
    }
    else -> "Не удалось сохранить профиль"
}