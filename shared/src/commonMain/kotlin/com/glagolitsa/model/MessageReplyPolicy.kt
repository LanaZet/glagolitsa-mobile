// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

object MessageReplyPolicy {
    fun canReplyInThread(chat: Chat): Boolean =
        !chat.isDirectMessage

    fun chatReplyRelation(
        parentMessage: Message,
        quoteBody: String? = null,
    ): MessageRelationDraft =
        MessageRelationDraft(
            replyToMessageId = parentMessage.replyReferenceId(),
            replyPreviewSenderId = parentMessage.sender_id,
            replyPreviewBody = resolveQuoteBody(quoteBody, parentMessage.body),
            visibility = MESSAGE_VISIBILITY_MAIN,
        )

    fun threadReplyRelation(
        threadRootMessage: Message,
        replyToMessage: Message? = null,
        quoteBody: String? = null,
    ): MessageRelationDraft =
        MessageRelationDraft(
            replyToMessageId = replyToMessage?.replyReferenceId(),
            replyPreviewSenderId = replyToMessage?.sender_id,
            replyPreviewBody = when {
                replyToMessage == null -> null
                else -> resolveQuoteBody(quoteBody, replyToMessage.body)
            },
            threadRootId = threadRootMessage.id,
            threadParentId = replyToMessage?.replyReferenceId() ?: threadRootMessage.id,
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

    /** Prefer non-blank selected quote; otherwise full message body. */
    internal fun resolveQuoteBody(quoteBody: String?, fullBody: String): String {
        val quote = quoteBody?.trim().orEmpty()
        return quote.ifBlank { fullBody }
    }

    fun normalizeRelationForChat(chat: Chat, relation: MessageRelationDraft?): MessageRelationDraft? {
        if (relation == null || canReplyInThread(chat)) return relation
        if (!relation.isThreadRelation()) return relation

        val replyToMessageId = relation.replyToMessageId
            ?: relation.threadParentId
            ?: relation.threadRootId
            ?: return null

        return MessageRelationDraft(
            replyToMessageId = replyToMessageId,
            replyPreviewSenderId = relation.replyPreviewSenderId,
            replyPreviewBody = relation.replyPreviewBody,
            visibility = MESSAGE_VISIBILITY_MAIN,
        )
    }
}

private fun Message.replyReferenceId(): String =
    pending_id?.takeIf { it.isNotBlank() } ?: id

private fun MessageRelationDraft.isThreadRelation(): Boolean =
    visibility == MESSAGE_VISIBILITY_THREAD_ONLY ||
        threadRootId != null ||
        threadParentId != null
