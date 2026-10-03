// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

/**
 * Клиентская валидация регистрации.
 *
 * Новые логины при регистрации намеренно ограничены английскими буквами,
 * чтобы имя профиля на кириллице не путали с техническим логином.
 */
object RegistrationValidation {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_USERNAME_LENGTH = 39

    enum class Field {
        USERNAME,
        EMAIL,
        PASSWORD,
        PASSWORD_CONFIRM,
    }

    data class Form(
        val username: String,
        val email: String = "",
        val password: String,
        val passwordConfirm: String,
    )

    /** Удаляет пробелы/whitespace из чувствительных полей входа. */
    fun sanitizeCredentialInput(raw: String): String = raw.filterNot(Char::isWhitespace)

    /** Для регистрации username вводим только английские буквы. */
    fun sanitizeRegistrationUsernameInput(raw: String): String =
        sanitizeCredentialInput(raw).filter { it in 'a'..'z' || it in 'A'..'Z' }

    /** Нормализует username так же, как сервер (lowercase), без пробелов. */
    fun normalizeUsername(raw: String): String = sanitizeCredentialInput(raw).lowercase()

    /** Проверяет форму; пустая map — всё ок. */
    fun validate(form: Form, requireEmail: Boolean = false): Map<Field, String> {
        val errors = linkedMapOf<Field, String>()

        validateUsername(form.username)?.let { errors[Field.USERNAME] = it }

        if (requireEmail) {
            validateEmail(form.email)?.let { errors[Field.EMAIL] = it }
        } else if (form.email.isNotBlank()) {
            validateEmail(form.email)?.let { errors[Field.EMAIL] = it }
        }

        validatePassword(form.password)?.let { errors[Field.PASSWORD] = it }
        if (form.password != form.passwordConfirm) {
            errors[Field.PASSWORD_CONFIRM] = "Пароли не совпадают"
        }

        return errors
    }

    fun isUsernameUsable(raw: String): Boolean = validateUsername(raw) == null

    internal fun validateUsername(raw: String): String? {
        if (raw.any(Char::isWhitespace)) return "Логин не может содержать пробелы"
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Введите имя пользователя"

        val name = normalizeUsername(trimmed)
        if (name.length > MAX_USERNAME_LENGTH) {
            return "Имя пользователя не длиннее $MAX_USERNAME_LENGTH символов"
        }
        if (!USERNAME_PATTERN.matches(name)) {
            return "Логин должен содержать только английские буквы a-z"
        }

        if (name in DEV_RESERVED_USERNAMES) {
            return "Это служебный тестовый аккаунт — выберите другое имя"
        }
        if (name in RESERVED_USERNAMES) {
            return "Это имя зарезервировано"
        }

        return null
    }

    internal fun validateEmail(raw: String): String? {
        val email = raw.trim()
        if (email.isEmpty()) return "Введите email"
        if (!EMAIL_PATTERN.matches(email)) return "Некорректный email"
        return null
    }

    fun validatePassword(raw: String): String? {
        if (raw.isEmpty()) return "Введите пароль"
        if (raw.any(Char::isWhitespace)) return "Пароль не может содержать пробелы"
        if (raw.length < MIN_PASSWORD_LENGTH) {
            return "Пароль не короче $MIN_PASSWORD_LENGTH символов"
        }
        return null
    }

    private val USERNAME_PATTERN = Regex("""^[a-z]+$""")

    private val EMAIL_PATTERN = Regex(
        """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""",
    )

    /** Служебные dev-аккаунты сервера (seed Marco/Polo) — не для реальной регистрации. */
    private val DEV_RESERVED_USERNAMES = setOf("marco", "polo")

    /** Зарезервированные имена — маршруты и служебные аккаунты. */
    private val RESERVED_USERNAMES = setOf(
        ".", "..", "api", "admin", "login", "user", "org", "explore",
        "ghost", "metrics", "assets", "attachments", "captcha", "swagger",
        "glagolitsa", "system", "root", "support", "help",
    )
}

class RegistrationValidationException(
    val fieldErrors: Map<RegistrationValidation.Field, String>,
) : Exception(fieldErrors.values.firstOrNull() ?: "Проверьте данные регистрации")
