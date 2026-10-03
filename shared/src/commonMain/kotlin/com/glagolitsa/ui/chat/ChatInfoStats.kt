// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.ChatVisibility
import com.glagolitsa.model.Message
import com.glagolitsa.model.StatusChannels
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.formatMessageTime
import com.glagolitsa.model.isOnlineLike

/**
 * Pure stats for Chat info screen (no mock counts).
 * Unit-testable; UI only binds these values.
 */
data class ChatInfoStats(
    val mediaCount: Int,
    val documentCount: Int,
    val memberCount: Int,
    val onlineCount: Int?,
    val presenceLabel: String,
    val canCall: Boolean,
    val typeLabel: String,
    val publicSlug: String?,
)

enum class ChatMediaKind {
    Media,
    Document,
}

data class ChatMediaFile(
    val messageId: String,
    val name: String,
    val meta: String,
    val kind: ChatMediaKind,
)

private val imageExt = Regex("""\.(png|jpe?g|gif|webp|heic|bmp)(\b|$)""", RegexOption.IGNORE_CASE)
private val videoExt = Regex("""\.(mp4|mov|m4v|webm|mkv|avi)(\b|$)""", RegexOption.IGNORE_CASE)
private val docExt = Regex(
    """\.(pdf|docx?|xlsx?|pptx?|txt|zip|rar|7z|csv)(\b|$)""",
    RegexOption.IGNORE_CASE,
)
private val byteSizeSuffix = Regex("""\((\d+)\s+байт\)\s*$""")

fun Message.looksLikeImageAttachment(): Boolean {
    val b = body
    if (b.contains("🖼") || b.contains("image", ignoreCase = true)) return true
    if (b.startsWith("📎") && imageExt.containsMatchIn(b)) return true
    return imageExt.containsMatchIn(b)
}

fun Message.looksLikeVideoAttachment(): Boolean {
    val b = body
    if (b.contains("🎥") || b.contains("video", ignoreCase = true)) return true
    if (b.startsWith("📎") && videoExt.containsMatchIn(b)) return true
    return videoExt.containsMatchIn(b)
}

fun Message.looksLikeMediaAttachment(): Boolean =
    looksLikeImageAttachment() || looksLikeVideoAttachment()

fun Message.looksLikeDocumentAttachment(): Boolean {
    val b = body
    if (!b.startsWith("📎") && !docExt.containsMatchIn(b)) return false
    if (looksLikeMediaAttachment()) return false
    return b.startsWith("📎") || docExt.containsMatchIn(b)
}

fun buildChatMediaFiles(messages: List<Message>): List<ChatMediaFile> =
    messages.mapNotNull { message ->
        val kind = when {
            message.looksLikeMediaAttachment() -> ChatMediaKind.Media
            message.looksLikeDocumentAttachment() -> ChatMediaKind.Document
            else -> return@mapNotNull null
        }
        val size = byteSizeSuffix.find(message.body)?.groupValues?.getOrNull(1)
            ?.toLongOrNull()
            ?.let(::formatBytes)
        val name = message.body
            .removePrefix("📎")
            .trim()
            .replace(byteSizeSuffix, "")
            .trim()
            .ifBlank {
                if (kind == ChatMediaKind.Media) "Фото или видео" else "Документ"
            }
        val typeLabel = if (kind == ChatMediaKind.Media) "Фото или видео" else "Документ"
        val time = formatMessageTime(message.created_at)
        val meta = listOfNotNull(
            typeLabel,
            size,
            time.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        ChatMediaFile(
            messageId = message.id,
            name = name,
            meta = meta,
            kind = kind,
        )
    }

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toInt() / 10.0} КБ"
    val mb = kb / 1024.0
    return "${(mb * 10).toInt() / 10.0} МБ"
}

/**
 * Resolve 1:1 call peer: repository map first, then chat.member_ids (excluding self).
 * Returns null for channels / multi-member groups where a single callee is ambiguous.
 */
fun resolveChatCallPartnerId(
    chat: Chat,
    currentUserId: String?,
    mappedPartnerId: String?,
): String? {
    if (chat.isChannel) return null
    mappedPartnerId?.takeIf { it.isNotBlank() }?.let { return it }
    val others = chat.member_ids
        .filter { it.isNotBlank() && it != currentUserId }
        .distinct()
    return when {
        chat.isDirectMessage -> others.firstOrNull()
        // 2-person group: call the only other member (same product rule as many messengers).
        others.size == 1 -> others.first()
        else -> null
    }
}

fun buildChatInfoStats(
    chat: Chat,
    messages: List<Message>,
    presenceStatus: String?,
    presenceMap: Map<String, UserPresenceView>,
    currentUserId: String?,
    partnerUserId: String?,
): ChatInfoStats {
    val media = messages.count { it.looksLikeMediaAttachment() }
    val docs = messages.count { it.looksLikeDocumentAttachment() }
    val members = chatParticipantCount(chat)
    val callPartner = resolveChatCallPartnerId(chat, currentUserId, partnerUserId)
    val online = if (chat.isDirectMessage) {
        null
    } else {
        chat.member_ids.count { id ->
            id != currentUserId && presenceMap[id]?.isOnlineLike() == true
        }
    }
    val canCall = when {
        chat.isChannel -> false
        chat.isGroup -> true
        else -> !callPartner.isNullOrBlank()
    }
    val typeLabel = when {
        chat.isDirectMessage -> "Личный чат"
        chat.isChannel && chat.visibility == ChatVisibility.PUBLIC -> "Публичный канал"
        chat.isChannel -> "Приватный канал"
        chat.type == ChatType.GROUP -> "Группа"
        else -> "Чат"
    }
    // presenceLabel is the **network** channel only (never custom user status text).
    val presence = when {
        chat.isDirectMessage -> StatusChannels.networkPresenceLabelOrUnknown(presenceStatus)
        else -> chatInfoPresenceLabel(chat, members, online)
    }
    return ChatInfoStats(
        mediaCount = media,
        documentCount = docs,
        memberCount = members,
        onlineCount = online,
        presenceLabel = presence,
        canCall = canCall,
        typeLabel = typeLabel,
        publicSlug = chat.slug?.takeIf { chat.isChannel && chat.visibility == ChatVisibility.PUBLIC },
    )
}
