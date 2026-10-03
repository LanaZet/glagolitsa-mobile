// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageReaction(
    val emoji: String,
    val userId: String,
)

/** Aggregate for UI chips under a bubble. */
data class ReactionChip(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

fun List<MessageReaction>.toChips(currentUserId: String?): List<ReactionChip> =
    groupBy { it.emoji }
        .map { (emoji, list) ->
            ReactionChip(
                emoji = emoji,
                count = list.size,
                reactedByMe = currentUserId != null && list.any { it.userId == currentUserId },
            )
        }
        .sortedByDescending { it.count }

/**
 * Toggle my reaction: same emoji again removes it; different emoji replaces mine.
 * Pure transform for unit tests + local store.
 */
fun List<MessageReaction>.toggleMine(emoji: String, userId: String): List<MessageReaction> {
    val withoutMine = filterNot { it.userId == userId }
    val alreadySame = any { it.userId == userId && it.emoji == emoji }
    return if (alreadySame) {
        withoutMine
    } else {
        withoutMine + MessageReaction(emoji = emoji, userId = userId)
    }
}
