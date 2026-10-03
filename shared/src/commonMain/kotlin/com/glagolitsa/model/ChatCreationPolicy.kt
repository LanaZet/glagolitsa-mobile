// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Pure validation for create group/channel forms (unit-testable, no IO).
 * Product presets (variant 1) map onto conversation policies (variant 3).
 */
object ChatCreationPolicy {

    private val slugRegex = Regex("^[a-z0-9_]{3,32}$")
    private const val SLUG_MIN = 3
    private const val SLUG_MAX = 32

    fun isTitleValid(title: String): Boolean =
        title.trim().length in 1..64

    fun normalizeSlug(raw: String): String =
        raw.trim().lowercase()
            .mapNotNull { ch ->
                when {
                    ch == ' ' || ch == '-' || ch == '.' -> '_'
                    else -> {
                        val t = transliterateChar(ch)
                        if (t == '\u0000') null else t
                    }
                }
            }
            .joinToString("")
            .filter { it.isLetterOrDigit() || it == '_' }
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(SLUG_MAX)

    /**
     * Suggest a public address from the channel title (Telegram-style).
     * Always returns a format-valid slug when title has enough material;
     * pads with `ch` if the normalized title is shorter than 3 chars.
     */
    fun suggestSlugFromTitle(title: String): String {
        val base = normalizeSlug(title)
        if (base.length >= SLUG_MIN) return base.take(SLUG_MAX)
        if (base.isEmpty()) return ""
        // Pad short bases: "ab" → "ab_ch", "a" → "a_ch"
        val padded = (base + "_ch").take(SLUG_MAX)
        return if (padded.length >= SLUG_MIN) padded else "channel"
    }

    /**
     * Next alternative when [base] is taken: news → news_2 → news_3 …
     * [attempt] is 0-based index of free-slot search (0 = first alternative after base).
     */
    fun suggestSlugAlternative(base: String, attempt: Int): String {
        val root = normalizeSlug(base).ifBlank { "channel" }.take(SLUG_MAX - 4).trimEnd('_')
        val n = (attempt + 2).coerceAtLeast(2)
        val suffix = "_$n"
        val maxRoot = (SLUG_MAX - suffix.length).coerceAtLeast(1)
        return (root.take(maxRoot) + suffix).take(SLUG_MAX)
    }

    fun isSlugFormatValid(slug: String): Boolean =
        slugRegex.matches(slug)

    fun requiresSlug(visibility: String): Boolean =
        visibility == ChatVisibility.PUBLIC

    fun canCreateChannel(
        title: String,
        visibility: String,
        slug: String,
        slugAvailable: Boolean?,
    ): Boolean {
        if (!isTitleValid(title)) return false
        if (!requiresSlug(visibility)) return true
        val normalized = normalizeSlug(slug)
        if (!isSlugFormatValid(normalized)) return false
        // null = not checked yet → disable Create (Apple: disable until valid)
        return slugAvailable == true
    }

    fun canCreateGroup(title: String): Boolean = isTitleValid(title)

    fun slugHelperText(slug: String, available: Boolean?): String? {
        val normalized = normalizeSlug(slug)
        if (normalized.isEmpty()) return "Предложится из названия, например news"
        if (!isSlugFormatValid(normalized)) return "3–32 символа: a–z, 0–9, _"
        return when (available) {
            true -> "Адрес свободен · @$normalized"
            false -> "Адрес уже занят — выберите другой"
            null -> "Проверяем…"
        }
    }

    fun encryptionDisclaimer(visibility: String): String =
        if (visibility == ChatVisibility.PUBLIC) {
            "Публичный канал не защищён end-to-end шифрованием. Содержимое видно серверу."
        } else {
            "Приватный канал: только по приглашению. Шифрование канала — server-side (не e2e)."
        }

    fun visibilitySubtitle(visibility: String): String =
        when (visibility) {
            ChatVisibility.PUBLIC -> "Найти и вступить могут все, у кого есть ссылка или адрес"
            else -> "Только по приглашению"
        }

    /** Minimal cyrillic → latin for slug suggestions (common Russian letters). */
    private fun transliterateChar(ch: Char): Char {
        val lower = ch.lowercaseChar()
        val mapped = when (lower) {
            'а' -> 'a'; 'б' -> 'b'; 'в' -> 'v'; 'г' -> 'g'; 'д' -> 'd'
            'е', 'ё' -> 'e'; 'ж' -> 'z'; 'з' -> 'z'; 'и', 'й' -> 'i'
            'к' -> 'k'; 'л' -> 'l'; 'м' -> 'm'; 'н' -> 'n'; 'о' -> 'o'
            'п' -> 'p'; 'р' -> 'r'; 'с' -> 's'; 'т' -> 't'; 'у' -> 'u'
            'ф' -> 'f'; 'х' -> 'h'; 'ц' -> 'c'; 'ч' -> 'c'; 'ш', 'щ' -> 's'
            'ъ', 'ь' -> '\u0000'
            'ы' -> 'y'; 'э' -> 'e'; 'ю' -> 'u'; 'я' -> 'y'
            else -> lower
        }
        return if (mapped == '\u0000') '\u0000' else mapped
    }
}
