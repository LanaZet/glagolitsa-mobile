// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

/**
 * Shared classification of transport blips that must not panic the chat UI.
 * Used by the chat error line and sync effects so the allowlists cannot drift.
 */
object TransientNetworkErrors {
    fun isTransientMessage(raw: String?): Boolean {
        val kind = classify(raw)?.kind ?: return false
        return kind in TRANSIENT_KINDS
    }

    fun isTransient(err: Throwable): Boolean =
        isTransientMessage(err.message)

    /** Map raw failures to safe, actionable chat copy. Raw internals belong in logs. */
    fun userVisibleChatError(raw: String?): String? =
        classify(raw)?.userMessage()

    /** Compact delivery-state label for a message bubble footer. */
    fun compactSendErrorLabel(raw: String?): String? {
        val failure = classify(raw) ?: return null
        // Waiting for a mailbox is the one-check state, not a send error.
        if (failure.kind == FailureKind.RECIPIENT) return null
        return failure.sendLabel()
    }

    const val NETWORK_ERROR = "Нет соединения. Проверьте интернет и попробуйте ещё раз"
    const val TIMEOUT_ERROR = "Сервер не ответил вовремя. Попробуйте ещё раз"
    const val AUTH_REQUIRED_ERROR = "Сессия истекла. Войдите снова"
    const val RATE_LIMIT_ERROR = "Слишком много попыток. Подождите немного и попробуйте снова"
    const val SECURITY_KEY_ERROR = "Ключ безопасности собеседника изменился. Подтвердите его перед отправкой"
    const val RECIPIENT_UNAVAILABLE_ERROR = "Собеседник пока не готов получать сообщения. Попросите его открыть приложение"
    const val ACCESS_DENIED_ERROR = "Нет доступа к этому действию"
    const val ATTACHMENT_TOO_LARGE_ERROR = "Файл слишком большой. Выберите файл поменьше"
    const val ATTACHMENT_UNAVAILABLE_ERROR = "Файл недоступен. Обновите чат и попробуйте снова"
    const val MESSAGE_UNAVAILABLE_ERROR = "Сообщение недоступно. Обновите чат и попробуйте снова"
    const val CHAT_UNAVAILABLE_ERROR = "Чат недоступен. Вернитесь к списку чатов и откройте его снова"
    const val CHAT_REFRESH_ERROR = "Не удалось обновить чат. Проверьте соединение и попробуйте ещё раз"
    const val SERVER_ERROR = "Сервер временно недоступен. Попробуйте позже"
    const val GENERIC_CHAT_ACTION_ERROR = "Не удалось выполнить действие. Попробуйте ещё раз"

    private val TRANSIENT_KINDS = setOf(
        FailureKind.AUTH,
        FailureKind.NETWORK,
        FailureKind.TIMEOUT,
    )

    private val FAILURE_RULES = listOf(
        FailureRule(
            kind = FailureKind.AUTH,
            tokens = listOf(
                "session expired",
                "not authenticated",
                "auth pending",
                "auth_refresh_unavailable",
                "missing bearer token",
                "invalid token",
                "unauthorized",
                "http 401",
                "нужен вход",
                "нужно войти",
            ),
        ),
        FailureRule(
            kind = FailureKind.TIMEOUT,
            tokens = listOf(
                "request timeout",
                "request_timeout",
                "timed out",
                "timeout",
                "request timed out",
                "http 408",
            ),
        ),
        FailureRule(
            kind = FailureKind.NETWORK,
            tokens = listOf(
                "failed to connect",
                "connection refused",
                "connection reset",
                "unable to resolve",
                "unknown host",
                "network is unreachable",
                "no route to host",
                "software caused connection abort",
                "broken pipe",
                // Emulator tunnel / private IPs must never surface raw.
                "10.0.2.2",
                "127.0.0.1",
                "localhost",
            ),
        ),
        FailureRule(
            kind = FailureKind.RATE_LIMIT,
            tokens = listOf(
                "too many requests",
                "rate limit",
                "http 429",
                "слишком много попыток",
            ),
        ),
        FailureRule(
            kind = FailureKind.SECURITY,
            tokens = listOf(
                "ключ безопасности",
                "safety number",
                "identity key",
                "untrusted",
            ),
        ),
        FailureRule(
            kind = FailureKind.RECIPIENT,
            tokens = listOf(
                "partner has no registered devices",
                "no registered devices",
                "у контакта нет зарегистрированных устройств",
            ),
        ),
        FailureRule(
            kind = FailureKind.ACCESS,
            tokens = listOf(
                "forbidden",
                "permission denied",
                "access denied",
                "http 403",
                "нет доступа",
                "нельзя отправлять",
            ),
        ),
        FailureRule(
            kind = FailureKind.ATTACHMENT_SIZE,
            tokens = listOf(
                "attachment too large",
                "payload too large",
                "request entity too large",
                "http 413",
                "файл слишком большой",
                "фото слишком большое",
            ),
        ),
        FailureRule(
            kind = FailureKind.ATTACHMENT,
            tokens = listOf(
                "attachment expired",
                "attachment slot expired",
                "attachment not uploaded",
                "failed to create media record",
                "failed to open output stream",
                "upload did not finalize",
                "media upload failed",
                "attachment upload failed",
                "encrypted attachment body is required",
                "encrypted media body is required",
            ),
        ),
        FailureRule(
            kind = FailureKind.REFRESH,
            tokens = listOf("не удалось обновить чат"),
        ),
        FailureRule(
            kind = FailureKind.MESSAGE_MISSING,
            tokens = listOf(
                "message not found",
                "message not found after enqueue",
                "original message not found",
                "сообщение не найдено",
            ),
        ),
        FailureRule(
            kind = FailureKind.CHAT_MISSING,
            tokens = listOf(
                "chat not found",
                "dm partner unknown",
                "could not create conversation",
            ),
        ),
        FailureRule(
            kind = FailureKind.SERVER,
            tokens = listOf(
                "http 500",
                "http 502",
                "http 503",
                "http 504",
                "internal server error",
                "bad gateway",
                "service unavailable",
                "gateway timeout",
                "server error",
                "object storage is not configured",
                "invalid json",
                "server identity missing",
            ),
        ),
    )

    private fun classify(raw: String?): ChatFailure? {
        val text = raw
            ?.trim()
            ?.replace(WHITESPACE_PATTERN, " ")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val lower = text.lowercase()
        val kind = FAILURE_RULES.firstOrNull { it.matches(lower) }?.kind
            ?: if (text.isSafeUserFacingRussian()) FailureKind.SAFE_RUSSIAN else FailureKind.GENERIC
        return ChatFailure(kind = kind, text = text)
    }

    private data class ChatFailure(
        val kind: FailureKind,
        val text: String,
    ) {
        fun userMessage(): String =
            when (kind) {
                FailureKind.AUTH -> AUTH_REQUIRED_ERROR
                FailureKind.TIMEOUT -> TIMEOUT_ERROR
                FailureKind.NETWORK -> NETWORK_ERROR
                FailureKind.RATE_LIMIT -> RATE_LIMIT_ERROR
                FailureKind.SECURITY -> SECURITY_KEY_ERROR
                FailureKind.RECIPIENT -> RECIPIENT_UNAVAILABLE_ERROR
                FailureKind.ACCESS -> ACCESS_DENIED_ERROR
                FailureKind.ATTACHMENT_SIZE -> ATTACHMENT_TOO_LARGE_ERROR
                FailureKind.ATTACHMENT -> ATTACHMENT_UNAVAILABLE_ERROR
                FailureKind.REFRESH -> CHAT_REFRESH_ERROR
                FailureKind.MESSAGE_MISSING -> MESSAGE_UNAVAILABLE_ERROR
                FailureKind.CHAT_MISSING -> CHAT_UNAVAILABLE_ERROR
                FailureKind.SERVER -> SERVER_ERROR
                FailureKind.SAFE_RUSSIAN -> text
                FailureKind.GENERIC -> GENERIC_CHAT_ACTION_ERROR
            }

        fun sendLabel(): String =
            when (kind) {
                FailureKind.NETWORK -> "Нет соединения"
                FailureKind.TIMEOUT -> "Таймаут"
                FailureKind.AUTH -> "Нужен вход"
                FailureKind.SECURITY -> "Проверьте ключ"
                FailureKind.RECIPIENT -> "Не отправлено"
                FailureKind.ACCESS -> "Нет доступа"
                FailureKind.RATE_LIMIT -> "Подождите"
                else -> "Не отправлено"
            }
    }

    private data class FailureRule(
        val kind: FailureKind,
        val tokens: List<String>,
    ) {
        fun matches(lower: String): Boolean =
            tokens.any { lower.contains(it) }
    }

    private enum class FailureKind {
        AUTH,
        TIMEOUT,
        NETWORK,
        RATE_LIMIT,
        SECURITY,
        RECIPIENT,
        ACCESS,
        ATTACHMENT_SIZE,
        ATTACHMENT,
        REFRESH,
        MESSAGE_MISSING,
        CHAT_MISSING,
        SERVER,
        SAFE_RUSSIAN,
        GENERIC,
    }

    private fun String.isSafeUserFacingRussian(): Boolean =
        any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } &&
            !contains(TECHNICAL_DETAIL_PATTERN)

    private val WHITESPACE_PATTERN = Regex("\\s+")

    private val TECHNICAL_DETAIL_PATTERN =
        Regex("(?i)(http\\s*\\d{3}|token|bearer|localhost|10\\.0\\.2\\.2|127\\.0\\.0\\.1|exception|stack|json|sql)")
}
