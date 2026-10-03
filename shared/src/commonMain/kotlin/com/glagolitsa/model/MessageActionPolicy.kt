// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Which message context-menu actions are available (iMessage/Telegram long-press).
 * Pure rules — no Compose / no IO.
 */
enum class MessageAction {
    REPLY_CHAT,
    REPLY_THREAD,
    DOWNLOAD,
    OPEN,
    PIN,
    UNPIN,
    FORWARD,
    ADD_TO_FAVORITES,
    COPY,
    EDIT,
    SELECT,
    CANCEL_SEND,
    RETRY,
    DELETE,
}

object MessageActionPolicy {

    /** iMessage/Telegram quick reactions shown above the action list. */
    val QuickReactions: List<String> = listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

    fun availableActions(
        message: Message,
        chat: Chat,
        currentUserId: String?,
        isSystemMessage: Boolean = false,
        hasImageAttachment: Boolean = false,
        isPinned: Boolean = false,
    ): List<MessageAction> {
        if (isSystemMessage) return emptyList()
        val isOwn = currentUserId != null && message.sender_id == currentUserId
        val actions = mutableListOf<MessageAction>()

        actions += MessageAction.REPLY_CHAT
        if (MessageReplyPolicy.canReplyInThread(chat)) {
            actions += MessageAction.REPLY_THREAD
        }
        // Pin is a primary action — keep it near the top of the tap menu.
        if (!message.isSending()) {
            actions += if (isPinned) MessageAction.UNPIN else MessageAction.PIN
        }
        if (hasImageAttachment) {
            actions += MessageAction.DOWNLOAD
        }
        if (!message.isSending()) {
            actions += MessageAction.ADD_TO_FAVORITES
        }
        if (message.body.isNotBlank()) {
            actions += MessageAction.FORWARD
            actions += MessageAction.COPY
        }
        if (MessageEditPolicy.canEdit(message, currentUserId, isSystemMessage)) {
            actions += MessageAction.EDIT
        }
        actions += MessageAction.SELECT
        if (isOwn && message.isSending()) {
            actions += MessageAction.CANCEL_SEND
        }
        // Failed and stuck-sending can both be re-queued from the action menu.
        if (isOwn && (message.isFailed() || message.isSending())) {
            actions += MessageAction.RETRY
        }
        if (!message.isSending()) {
            actions += MessageAction.DELETE
        }
        return actions
    }

    /**
     * Compact tap menu for file bubbles: reactions stay in the tray;
     * list is Open / Download / Forward / Delete.
     */
    fun availableMediaActions(
        message: Message,
        currentUserId: String?,
        canDownload: Boolean,
        canOpen: Boolean,
    ): List<MessageAction> {
        val isOwn = currentUserId != null && message.sender_id == currentUserId
        val actions = mutableListOf<MessageAction>()
        if (canOpen) actions += MessageAction.OPEN
        if (canDownload) actions += MessageAction.DOWNLOAD
        if (!message.isSending()) actions += MessageAction.FORWARD
        if (isOwn && (message.isFailed() || message.isSending())) {
            actions += MessageAction.RETRY
        }
        if (!message.isSending()) actions += MessageAction.DELETE
        return actions
    }

    fun availableAttachmentViewerActions(
        message: Message,
        canDownload: Boolean,
        isPinned: Boolean,
    ): List<MessageAction> {
        val actions = mutableListOf<MessageAction>()
        if (!message.isSending()) actions += MessageAction.FORWARD
        actions += MessageAction.REPLY_CHAT
        if (canDownload) actions += MessageAction.DOWNLOAD
        if (!message.isSending()) {
            actions += if (isPinned) MessageAction.UNPIN else MessageAction.PIN
        }
        return actions
    }

    fun label(action: MessageAction): String = when (action) {
        MessageAction.REPLY_CHAT -> "Ответить"
        MessageAction.REPLY_THREAD -> "В ветку"
        MessageAction.DOWNLOAD -> "Загрузить"
        MessageAction.OPEN -> "Открыть"
        MessageAction.PIN -> "Закрепить"
        MessageAction.UNPIN -> "Открепить"
        MessageAction.FORWARD -> "Переслать"
        MessageAction.ADD_TO_FAVORITES -> "Добавить в избранное"
        MessageAction.COPY -> "Копировать"
        MessageAction.EDIT -> "Редактировать"
        MessageAction.SELECT -> "Выбрать"
        MessageAction.CANCEL_SEND -> "Отменить отправку"
        MessageAction.RETRY -> "Переслать"
        MessageAction.DELETE -> "Удалить"
    }

    fun isDestructive(action: MessageAction): Boolean =
        action == MessageAction.DELETE || action == MessageAction.CANCEL_SEND

    fun selectionBarLabel(count: Int): String =
        if (count == 1) "1 выбрано" else "$count выбрано"

    fun canDeleteSelected(
        selectedIds: Set<String>,
        messagesById: Map<String, Message>,
    ): Boolean {
        if (selectedIds.isEmpty()) return false
        return selectedIds.all { id ->
            val m = messagesById[id] ?: return false
            !m.isSending()
        }
    }

    fun canCopySelected(
        selectedIds: Set<String>,
        messagesById: Map<String, Message>,
    ): Boolean =
        selectedIds.any { id -> messagesById[id]?.body?.isNotBlank() == true }
}
