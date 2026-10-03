// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

data class ThreadBranchSummary(
    val rootMessageId: String,
    val replyCount: Int,
    val lastReplyAt: String?,
    val lastReplySenderId: String?,
    val lastReply: Message? = null,
    val participantSenderIds: List<String> = emptyList(),
)

fun buildThreadBranchSummaries(
    rootMessages: List<Message>,
    threadReplies: List<Message>,
): Map<String, ThreadBranchSummary> {
    val localSummaries = buildLocalThreadBranchSummaries(threadReplies)
    val backendSummaries = rootMessages
        .asSequence()
        .filter { it.thread_reply_count > 0 }
        .associate { root ->
            val local = localSummaries[root.id]
            root.id to ThreadBranchSummary(
                rootMessageId = root.id,
                replyCount = root.thread_reply_count,
                lastReplyAt = root.last_thread_reply_at ?: local?.lastReplyAt,
                lastReplySenderId = root.last_thread_reply_sender_id ?: local?.lastReplySenderId,
                lastReply = local?.lastReply,
                participantSenderIds = local?.participantSenderIds.orEmpty(),
            )
        }
    return localSummaries + backendSummaries
}

private fun buildLocalThreadBranchSummaries(threadReplies: List<Message>): Map<String, ThreadBranchSummary> {
    return threadReplies
        .asSequence()
        .filter { message ->
            val rootId = message.thread_root_id
            message.visibility == MESSAGE_VISIBILITY_THREAD_ONLY &&
                !rootId.isNullOrBlank() &&
                message.id != rootId
        }
        .groupBy { it.thread_root_id.orEmpty() }
        .mapValues { (rootId, replies) ->
            val lastReply = replies.sortedForChat().last()
            ThreadBranchSummary(
                rootMessageId = rootId,
                replyCount = replies.size,
                lastReplyAt = lastReply.created_at,
                lastReplySenderId = lastReply.sender_id,
                lastReply = lastReply,
                participantSenderIds = replies
                    .sortedForChat()
                    .map { it.sender_id }
                    .distinct(),
            )
        }
}
