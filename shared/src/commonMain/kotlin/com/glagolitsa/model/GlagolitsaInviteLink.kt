// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * In-app invite links. Channels/groups are private: the URL is the join key,
 * not a public directory listing.
 *
 * - `https://glagolitsa.app/c/{slug}` — channel by slug
 * - `https://glagolitsa.app/join/{token}` — invite token (group/channel)
 */
sealed class GlagolitsaInviteTarget {
    data class ChannelSlug(val slug: String) : GlagolitsaInviteTarget()
    data class JoinToken(val token: String) : GlagolitsaInviteTarget()
    data class CallId(val callId: String) : GlagolitsaInviteTarget()
}

data class GlagolitsaInviteSpan(
    val start: Int,
    val endExclusive: Int,
    val raw: String,
    val target: GlagolitsaInviteTarget,
)

object GlagolitsaInviteLink {
    private val inText = Regex(
        """(?:https?://)?glagolitsa\.app/(c|join|call)/([A-Za-z0-9_-]{3,64})""",
        RegexOption.IGNORE_CASE,
    )
    private val slugPattern = Regex("""^[a-z0-9_]{3,32}$""")

    fun parse(raw: String): GlagolitsaInviteTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val match = inText.find(trimmed)
        if (match != null && match.range.first == 0 && match.range.last == trimmed.lastIndex) {
            return targetFrom(match.groupValues[1], match.groupValues[2])
        }
        parseInviteToken(trimmed)?.let { return GlagolitsaInviteTarget.JoinToken(it) }
        val slug = trimmed.removePrefix("@").lowercase()
        if (slugPattern.matches(slug)) {
            return GlagolitsaInviteTarget.ChannelSlug(slug)
        }
        return null
    }

    fun findInText(text: String): List<GlagolitsaInviteSpan> =
        inText.findAll(text).mapNotNull { match ->
            val target = targetFrom(match.groupValues[1], match.groupValues[2]) ?: return@mapNotNull null
            GlagolitsaInviteSpan(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                raw = match.value,
                target = target,
            )
        }.toList()

    fun spanAt(text: String, offset: Int): GlagolitsaInviteSpan? {
        if (offset < 0 || offset > text.length) return null
        return findInText(text).firstOrNull { span ->
            offset >= span.start && offset < span.endExclusive
        }
    }

    private fun targetFrom(kind: String, value: String): GlagolitsaInviteTarget? {
        return when (kind.lowercase()) {
            "c" -> {
                val slug = value.lowercase()
                if (slugPattern.matches(slug)) GlagolitsaInviteTarget.ChannelSlug(slug) else null
            }
            "join" -> {
                if (value.length < 8) null else GlagolitsaInviteTarget.JoinToken(value)
            }
            "call" -> {
                if (value.length < 8) null else GlagolitsaInviteTarget.CallId(value)
            }
            else -> null
        }
    }
}

private val callLinkIdPattern = Regex("""^[A-Za-z0-9_-]{8,64}$""")

fun callJoinLink(callId: String): String {
    val safeCallId = callId.trim()
    require(callLinkIdPattern.matches(safeCallId)) { "invalid call id" }
    return "https://glagolitsa.app/call/$safeCallId"
}

fun CallSession.isGroupChatCall(): Boolean =
    !chat_id.isNullOrBlank() || call_scope.equals(CallScope.GROUP, ignoreCase = true)
