// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message

/**
 * Pure rules for taps on an **inline reply quote** (quoted strip inside a bubble).
 *
 * Separate from bubble / message-menu clicks:
 * 1. Tap quote → show message text preview + “go to” icon (if navigable)
 * 2. In preview: scroll long text; drag-select → Copy / Reply
 * 3. Reply from selection targets the **original** quoted message with the selected fragment
 * 4. Tap go icon → scroll to original message
 * 5. Tap elsewhere on the bubble → message menu (bubble owns that)
 *
 * **Important:** any tap on a quote-related region must **not** open the message action menu.
 * See [shouldOpenMessageActionMenu] / [consumesBubbleClick].
 */
enum class ReplyQuoteUiState {
    /** Compact one-line quote in the bubble. */
    Idle,

    /** After quote tap: visible message preview + optional go-to control. */
    PreviewWithGo,
}

/**
 * Where a pointer event landed relative to quote UI.
 * Used to decide whether the bubble may open the message action menu.
 */
enum class ReplyQuoteTapRegion {
    /** Body / chrome of the message bubble outside the quote block. */
    MessageBody,

    /** Compact one-line quote strip. */
    QuoteStrip,

    /** Expanded “Превью” card (header / chrome). */
    QuotePreviewCard,

    /** Scrollable / selectable body inside the preview card. */
    QuotePreviewBody,

    /** “Go to original” control on the preview. */
    QuoteGoButton,

    /** Copy / Reply popup after text selection in the preview. */
    QuoteSelectionMenu,
}

/** Actions available in the quote-preview text selection menu. */
enum class ReplyQuoteSelectionAction {
    Copy,
    Reply,
}

/**
 * Payload for “Ответить” after selecting text inside the quote preview.
 * [selectedText] is the fragment to show as the composer quote body.
 */
data class ReplyQuoteSelectionReply(
    val targetMessageId: String,
    val targetSenderId: String?,
    val selectedText: String,
    val previewBody: String,
)

object ReplyQuoteInteraction {

    /** Fixed height of the scrollable body viewport inside the quote preview card. */
    const val PREVIEW_BODY_MAX_HEIGHT_DP = 128

    /** How long the source stays highlighted after “go to quote”. */
    const val FOCUS_HIGHLIGHT_MS = 1_800L

    /**
     * Whether a tap on [region] may open the message action menu (tap-on-message menu).
     * Quote strip, preview card, go button, selection menu → never.
     */
    fun shouldOpenMessageActionMenu(region: ReplyQuoteTapRegion): Boolean =
        region == ReplyQuoteTapRegion.MessageBody

    /**
     * Whether the quote UI owns this tap and the bubble must not handle it.
     * Inverse of [shouldOpenMessageActionMenu] for all quote-related regions.
     */
    fun consumesBubbleClick(region: ReplyQuoteTapRegion): Boolean =
        !shouldOpenMessageActionMenu(region)

    fun canNavigate(targetMessageId: String?): Boolean =
        !targetMessageId.isNullOrBlank()

    fun canOpenPreview(body: String, targetMessageId: String?): Boolean =
        body.isNotBlank() || canNavigate(targetMessageId)

    /** Tap on the quote strip: open text preview (+ go icon when target exists). */
    fun onQuoteClick(
        current: ReplyQuoteUiState,
        body: String,
        targetMessageId: String?,
    ): ReplyQuoteUiState {
        if (!canOpenPreview(body, targetMessageId)) return ReplyQuoteUiState.Idle
        // Second tap on quote while open collapses again.
        return if (current == ReplyQuoteUiState.PreviewWithGo) {
            ReplyQuoteUiState.Idle
        } else {
            ReplyQuoteUiState.PreviewWithGo
        }
    }

    fun onGoClick(): ReplyQuoteUiState = ReplyQuoteUiState.Idle

    fun onDismiss(): ReplyQuoteUiState = ReplyQuoteUiState.Idle

    fun showsPreview(state: ReplyQuoteUiState): Boolean =
        state == ReplyQuoteUiState.PreviewWithGo

    fun showsGoControl(state: ReplyQuoteUiState, targetMessageId: String?): Boolean =
        state == ReplyQuoteUiState.PreviewWithGo && canNavigate(targetMessageId)

    /** Text selection is available only while the preview card is open. */
    fun selectionEnabled(state: ReplyQuoteUiState): Boolean =
        state == ReplyQuoteUiState.PreviewWithGo

    /**
     * Selection menu actions for quote preview.
     * Reply is included when the original message id is known (navigable target).
     */
    fun selectionMenuActions(
        state: ReplyQuoteUiState,
        targetMessageId: String?,
    ): List<ReplyQuoteSelectionAction> {
        if (!selectionEnabled(state)) return emptyList()
        val actions = mutableListOf(ReplyQuoteSelectionAction.Copy)
        if (canNavigate(targetMessageId)) {
            actions += ReplyQuoteSelectionAction.Reply
        }
        return actions
    }

    fun canReplyFromSelection(
        state: ReplyQuoteUiState,
        selectedText: String?,
        targetMessageId: String?,
    ): Boolean {
        if (!selectionEnabled(state)) return false
        if (!canNavigate(targetMessageId)) return false
        return !selectedText.isNullOrBlank()
    }

    /**
     * Build a reply payload from a selection inside the quote preview.
     * Returns null if selection / target is not valid for reply.
     */
    fun resolveSelectionReply(
        state: ReplyQuoteUiState,
        selectedText: String?,
        targetMessageId: String?,
        targetSenderId: String?,
        previewBody: String,
    ): ReplyQuoteSelectionReply? {
        val quote = selectedText?.trim().orEmpty()
        if (!canReplyFromSelection(state, quote, targetMessageId)) return null
        return ReplyQuoteSelectionReply(
            targetMessageId = targetMessageId!!,
            targetSenderId = targetSenderId?.takeIf { it.isNotBlank() },
            selectedText = quote,
            previewBody = previewBody,
        )
    }

    /**
     * Message stand-in when the original is not in the loaded window.
     * Uses preview body so the composer still has a sensible quote target.
     */
    fun fallbackReplyMessage(
        reply: ReplyQuoteSelectionReply,
        chatId: String,
    ): Message = Message(
        id = reply.targetMessageId,
        chat_id = chatId,
        sender_id = reply.targetSenderId.orEmpty().ifBlank { "unknown" },
        body = reply.previewBody.ifBlank { reply.selectedText },
    )

    /** List index to scroll to (matches id or pending_id). */
    fun focusIndexIn(
        messages: List<Message>,
        targetMessageId: String,
    ): Int {
        if (targetMessageId.isBlank()) return -1
        return messages.indexOfFirst { matchesFocusTarget(it, targetMessageId) }
    }

    fun matchesFocusTarget(message: Message, targetMessageId: String): Boolean {
        if (targetMessageId.isBlank()) return false
        return message.id == targetMessageId || message.pending_id == targetMessageId
    }

    /** Resolve original message from the loaded list, or null if only id is known. */
    fun findTargetMessage(
        messagesById: Map<String, Message>,
        targetMessageId: String,
    ): Message? =
        messagesById[targetMessageId]
            ?: messagesById.values.firstOrNull { it.pending_id == targetMessageId }
}
