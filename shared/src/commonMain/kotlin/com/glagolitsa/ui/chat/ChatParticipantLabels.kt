// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.isOnlineLike

fun chatParticipantCount(chat: Chat): Int =
    chat.member_ids.distinct().let { ids ->
        if (ids.isNotEmpty()) ids.size else if (chat.isDirectMessage) 2 else 0
    }

fun chatHeaderParticipantSubtitle(
    chat: Chat,
    presenceMap: Map<String, UserPresenceView>,
): String? {
    if (chat.isDirectMessage) return null
    val members = chat.member_ids.distinct()
    if (members.isEmpty()) return null
    val total = members.size
    val online = members.count { presenceMap[it]?.isOnlineLike() == true }
    return if (chat.isChannel) {
        val onlineSuffix = if (online > 0) ", $online в сети" else ""
        "$total ${subscriberLabel(total)}$onlineSuffix"
    } else {
        "$total ${participantLabel(total)}, $online в сети"
    }
}

fun chatInfoPresenceLabel(
    chat: Chat,
    memberCount: Int,
    onlineCount: Int?,
): String {
    if (chat.isChannel) {
        val slug = chat.slug?.takeIf { it.isNotBlank() }
        val base = "$memberCount ${subscriberShortLabel(memberCount)}"
        return if (slug != null) "@$slug · $base" else base
    }
    val onlinePart = onlineCount?.let { ", $it в сети" }.orEmpty()
    return "$memberCount ${participantLabel(memberCount)}$onlinePart"
}

internal fun participantLabel(count: Int): String =
    russianCount(count, one = "участник", few = "участника", many = "участников")

internal fun subscriberLabel(count: Int): String =
    russianCount(count, one = "подписчик", few = "подписчика", many = "подписчиков")

private fun subscriberShortLabel(count: Int): String =
    russianCount(count, one = "подп.", few = "подп.", many = "подп.")

private fun russianCount(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1 -> one
        in 2..4 -> few
        else -> many
    }
}
