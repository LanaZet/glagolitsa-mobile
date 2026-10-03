// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteMessageRef(
    val messageId: String,
    val chatId: String,
    val addedAt: String,
)

data class FavoriteMessageItem(
    val message: Message,
    val chat: Chat?,
    val addedAt: String,
)

data class FavoriteMessagesResolveResult(
    val items: List<FavoriteMessageItem>,
    val aliveRefs: List<FavoriteMessageRef>,
    val sourceRefCount: Int,
) {
    val pruned: Boolean
        get() = aliveRefs.size != sourceRefCount
}

data class FavoriteMessagePresentation(
    val body: String,
    val icon: String,
    val meta: String,
)

/**
 * Pure favorites rules: add/remove refs and resolve against local message cache.
 */
object FavoriteMessagesLogic {
    fun add(
        refs: List<FavoriteMessageRef>,
        messageId: String,
        chatId: String,
        addedAt: String,
    ): List<FavoriteMessageRef> {
        if (refs.any { it.messageId == messageId }) return refs
        return listOf(
            FavoriteMessageRef(
                messageId = messageId,
                chatId = chatId,
                addedAt = addedAt,
            ),
        ) + refs
    }

    fun remove(
        refs: List<FavoriteMessageRef>,
        messageId: String,
    ): List<FavoriteMessageRef> = refs.filterNot { it.messageId == messageId }

    fun resolve(
        refs: List<FavoriteMessageRef>,
        findMessage: (String) -> Message?,
        findChat: (String) -> Chat?,
        limit: Int = 100,
    ): FavoriteMessagesResolveResult {
        if (refs.isEmpty()) {
            return FavoriteMessagesResolveResult(
                items = emptyList(),
                aliveRefs = emptyList(),
                sourceRefCount = 0,
            )
        }
        val items = mutableListOf<FavoriteMessageItem>()
        val aliveRefs = mutableListOf<FavoriteMessageRef>()
        refs.forEach { ref ->
            val message = findMessage(ref.messageId) ?: return@forEach
            aliveRefs += ref
            items += FavoriteMessageItem(
                message = message,
                chat = findChat(ref.chatId),
                addedAt = ref.addedAt,
            )
        }
        val sorted = items
            .sortedByDescending { it.addedAt }
            .take(limit.coerceAtLeast(0))
        return FavoriteMessagesResolveResult(
            items = sorted,
            aliveRefs = aliveRefs,
            sourceRefCount = refs.size,
        )
    }

    fun presentation(item: FavoriteMessageItem): FavoriteMessagePresentation {
        val body = item.message.body.trim().ifBlank { "Пустое сообщение" }
        val chatTitle = item.chat?.title?.ifBlank { "Чат" } ?: "Чат"
        val isFile = body.startsWith("📎")
        val meta = buildString {
            append(chatTitle)
            val ts = item.message.created_at?.take(16)?.replace('T', ' ')
            if (!ts.isNullOrBlank()) {
                append(" · ")
                append(ts)
            }
        }
        return FavoriteMessagePresentation(
            body = body,
            icon = if (isFile) "📎" else "💬",
            meta = meta,
        )
    }
}
