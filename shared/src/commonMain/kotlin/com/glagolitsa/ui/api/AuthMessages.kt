// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.api

import com.glagolitsa.api.ApiException
import com.glagolitsa.auth.DeviceRegistrationException
import com.glagolitsa.auth.RegistrationDeferredException
import com.glagolitsa.auth.RegistrationValidation
import io.ktor.http.HttpStatusCode

data class RegistrationUiError(
    val message: String,
    val field: RegistrationValidation.Field? = null,
    val bannerHint: String? = null,
    val suggestLogin: Boolean = false,
)

private fun Throwable.isNetworkFailure(): Boolean {
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

private fun Throwable.networkFailureMessage(): String =
    "Не удалось подключиться к серверу. Проверьте, что выбранный сервер запущен и доступен с этого устройства."

private fun deviceRegistrationMessage(): String =
    "Аккаунт создан, но устройство не зарегистрировано на сервере. " +
        "Проверьте VPN/интернет и войдите снова — без device_id аккаунт остаётся неактивным."

private fun humanizeDeviceRegistrationError(raw: String): String =
    if (raw.contains("account pending device registration", ignoreCase = true)) {
        "Сервер не принял регистрацию устройства (ошибка API). Обновите сервер и войдите снова."
    } else {
        raw
    }

/** Куда и что показать на экране регистрации. */
fun Throwable.registrationUiError(): RegistrationUiError = when {
    this is DeviceRegistrationException -> RegistrationUiError(
        message = message?.takeIf { it.isNotBlank() } ?: deviceRegistrationMessage(),
        field = null,
        bannerHint = deviceRegistrationMessage(),
        suggestLogin = true,
    )
    isNetworkFailure() -> RegistrationUiError(
        message = networkFailureMessage(),
        field = null,
    )
    this is RegistrationDeferredException -> RegistrationUiError(
        message = "Логин уже занят — аккаунт, возможно, уже создан",
        field = RegistrationValidation.Field.USERNAME,
        bannerHint = "Если вы уже регистрировались, войдите с тем же логином и паролем. " +
            "Отображаемое имя в профиле — это другое поле, оно не подходит для входа.",
        suggestLogin = true,
    )
    this is ApiException -> registrationApiError(this)
    else -> RegistrationUiError(
        message = message?.takeIf { it.isNotBlank() } ?: "Не удалось зарегистрироваться",
        field = null,
    )
}

private fun registrationApiError(error: ApiException): RegistrationUiError {
    val text = error.errorMessage?.takeIf { it.isNotBlank() }
        ?: return RegistrationUiError("Не удалось зарегистрироваться", field = null)

    val lower = text.lowercase()
    val field = when {
        lower.contains("username") ||
            lower.contains("имя пользователя") ||
            lower.contains("reserved for development") ->
            RegistrationValidation.Field.USERNAME
        lower.contains("email") ->
            RegistrationValidation.Field.EMAIL
        lower.contains("password") || lower.contains("парол") ->
            RegistrationValidation.Field.PASSWORD
        lower.contains("proof of work") ->
            null
        else -> null
    }

    val message = when {
        error.status == HttpStatusCode.BadRequest && lower == "invalid username" ->
            "Логин должен содержать только английские буквы a-z"
        error.status == HttpStatusCode.Forbidden ->
            "Аккаунт создан. Войдите с тем же логином и паролем"
        error.status == HttpStatusCode.TooManyRequests ->
            "Слишком много попыток. Подождите немного"
        error.status.value in 500..599 -> "Сервер временно недоступен"
        else -> text
    }

    return RegistrationUiError(
        message = message,
        field = field,
        suggestLogin = error.status == HttpStatusCode.Forbidden,
    )
}

/** @deprecated Используйте [registrationUiError]. */
fun Throwable.authRegistrationMessage(): String = registrationUiError().let { ui ->
    listOfNotNull(ui.message, ui.bannerHint).joinToString("\n\n")
}

fun Throwable.authRecoveryMessage(): String = when {
    isNetworkFailure() -> networkFailureMessage()
    this is ApiException -> when (status.value) {
        HttpStatusCode.Unauthorized.value -> "Не удалось подтвердить восстановление"
        HttpStatusCode.TooManyRequests.value -> "Слишком много попыток. Подождите немного"
        in 500..599 -> "Сервер временно недоступен"
        else -> errorMessage?.takeIf { it.isNotBlank() } ?: "Не удалось восстановить доступ"
    }
    else -> message?.takeIf { it.isNotBlank() } ?: "Не удалось восстановить доступ"
}

fun Throwable.authLoginMessage(): String = when {
    this is DeviceRegistrationException ->
        message?.takeIf { it.isNotBlank() }?.let(::humanizeDeviceRegistrationError)
            ?: deviceRegistrationMessage()
    isNetworkFailure() -> networkFailureMessage()
    this is ApiException -> when (status.value) {
        HttpStatusCode.Unauthorized.value -> "Неверное имя пользователя или пароль"
        HttpStatusCode.Forbidden.value -> errorMessage?.let(::humanizeDeviceRegistrationError)
            ?: deviceRegistrationMessage()
        HttpStatusCode.TooManyRequests.value -> "Слишком много попыток. Подождите немного"
        in 500..599 -> "Сервер временно недоступен"
        else -> errorMessage?.takeIf { it.isNotBlank() } ?: "Не удалось войти"
    }
    else -> message?.takeIf { it.isNotBlank() } ?: "Не удалось войти"
}
