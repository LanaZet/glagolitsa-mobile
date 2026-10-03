// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.ThreadBranchSummary
import com.glagolitsa.model.formatMessageTime

data class ChatThreadBranchPreviewData(
    val replyCount: Int,
    val lastSenderName: String,
    val lastBody: String? = null,
    val lastActivityLabel: String? = null,
    val participantLabels: List<String> = emptyList(),
    val hasUnread: Boolean = false,
)

fun buildChatThreadBranchPreviews(
    summaries: Map<String, ThreadBranchSummary>,
    currentUserId: String?,
    senderNameFor: (String) -> String?,
): Map<String, ChatThreadBranchPreviewData> {
    return summaries
        .filterValues { it.replyCount > 0 }
        .mapValues { (_, summary) ->
            val lastSenderId = summary.lastReply?.sender_id ?: summary.lastReplySenderId
            val lastSenderName = replySenderLabel(
                lastSenderId.orEmpty(),
                currentUserId,
                lastSenderId?.let(senderNameFor),
            )
            val participantLabels = summary.participantSenderIds
                .map { senderId ->
                    replySenderLabel(
                        senderId,
                        currentUserId,
                        senderNameFor(senderId),
                    )
                }
                .distinct()
            ChatThreadBranchPreviewData(
                replyCount = summary.replyCount,
                lastSenderName = lastSenderName,
                lastBody = summary.lastReply?.body,
                lastActivityLabel = formatMessageTime(summary.lastReplyAt),
                participantLabels = participantLabels.ifEmpty { listOf(lastSenderName) },
                hasUnread = !lastSenderId.isNullOrBlank() && lastSenderId != currentUserId,
            )
        }
}
