// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.glagolitsa.model.GlagolitsaInviteLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.math.min
import kotlin.math.roundToInt

private val MessageSelectionHighlight = GlagolitsaColors.AccentRed.copy(alpha = 0.28f)

/**
 * Message body with drag-to-select. On selection end shows a compact menu:
 * Copy / Reply (reply only if [onReplyToSelection] is provided).
 * Reply receives the selected quote text, not the full message body.
 *
 * @param longPressToSelect when true, selection starts after a long-press so normal
 * vertical drags can scroll a parent (used in quote preview).
 * @param maxLines optional line clamp (e.g. collapsed long bubbles).
 */
@Composable
internal fun SelectableAutoCopyMessageText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onReplyToSelection: ((selectedText: String) -> Unit)? = null,
    onInviteLinkClick: ((raw: String) -> Unit)? = null,
    longPressToSelect: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var selectionStart by remember(text) { mutableStateOf<Int?>(null) }
    var selectionEnd by remember(text) { mutableStateOf<Int?>(null) }
    var menuVisible by remember(text) { mutableStateOf(false) }
    var selectedQuote by remember(text) { mutableStateOf<String?>(null) }

    fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        menuVisible = false
        selectedQuote = null
    }

    val selectionRange = messageSelectionRange(
        start = selectionStart,
        end = selectionEnd,
        textLength = text.length,
    )
    val inviteSpans = remember(text) { GlagolitsaInviteLink.findInText(text) }
    val displayText = buildAnnotatedString {
        append(text)
        inviteSpans.forEach { span ->
            addStyle(
                SpanStyle(
                    color = GlagolitsaColors.OrnamentGold,
                    textDecoration = TextDecoration.Underline,
                ),
                start = span.start,
                end = span.endExclusive,
            )
        }
        if (selectionRange != null) {
            addStyle(
                SpanStyle(background = MessageSelectionHighlight),
                start = selectionRange.first,
                end = selectionRange.last + 1,
            )
        }
    }

    fun onSelectionDragStart(offset: androidx.compose.ui.geometry.Offset) {
        menuVisible = false
        selectedQuote = null
        val layout = layoutResult ?: return
        val startOffset = layout.getOffsetForPosition(offset).coerceIn(0, text.length)
        selectionStart = startOffset
        selectionEnd = startOffset
    }

    fun onSelectionDrag(position: androidx.compose.ui.geometry.Offset) {
        val layout = layoutResult ?: return
        selectionEnd = layout.getOffsetForPosition(position).coerceIn(0, text.length)
    }

    fun onSelectionDragEnd() {
        val range = messageSelectionRange(
            start = selectionStart,
            end = selectionEnd,
            textLength = text.length,
        )
        if (range == null) {
            clearSelection()
            return
        }
        val quote = text.substring(range.first, range.last + 1)
        if (quote.isBlank()) {
            clearSelection()
            return
        }
        selectedQuote = quote
        menuVisible = true
    }

    Box(modifier = modifier) {
        Text(
            text = displayText,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .pointerInput(text, onInviteLinkClick, inviteSpans) {
                    if (onInviteLinkClick == null || inviteSpans.isEmpty()) return@pointerInput
                    detectTapGestures { offset ->
                        if (selectionStart != null) {
                            clearSelection()
                            return@detectTapGestures
                        }
                        val layout = layoutResult ?: return@detectTapGestures
                        val pos = layout.getOffsetForPosition(offset).coerceIn(0, text.length)
                        val span = GlagolitsaInviteLink.spanAt(text, pos.coerceAtMost(text.lastIndex.coerceAtLeast(0)))
                            ?: return@detectTapGestures
                        onInviteLinkClick(span.raw)
                    }
                }
                .pointerInput(text, longPressToSelect) {
                    if (longPressToSelect) {
                        // Leave normal vertical drags free for parent verticalScroll.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onSelectionDragStart(it) },
                            onDrag = { change, _ ->
                                onSelectionDrag(change.position)
                                change.consume()
                            },
                            onDragEnd = { onSelectionDragEnd() },
                            onDragCancel = { clearSelection() },
                        )
                    } else {
                        detectDragGestures(
                            onDragStart = { onSelectionDragStart(it) },
                            onDrag = { change, _ ->
                                onSelectionDrag(change.position)
                                change.consume()
                            },
                            onDragEnd = { onSelectionDragEnd() },
                            onDragCancel = { clearSelection() },
                        )
                    }
                }
                .testTag("chat-message-body-text"),
        )

        val quote = selectedQuote
        if (menuVisible && quote != null) {
            // Anchor to this text box in window coords (not TopCenter of a nested scroll root,
            // which previously sent the menu to the top of the chat).
            val selectionMidY = selectionMenuAnchorY(
                layout = layoutResult,
                range = selectionRange,
            )
            val positionProvider = remember(selectionMidY) {
                SelectionMenuPopupPositionProvider(preferredLocalY = selectionMidY)
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { clearSelection() },
                properties = PopupProperties(focusable = true),
            ) {
                MessageTextSelectionMenu(
                    canReply = onReplyToSelection != null,
                    onCopy = {
                        SecureClipboard.copyWithAutoClear(
                            label = "message-selection",
                            text = quote,
                        )
                        clearSelection()
                    },
                    onReply = {
                        onReplyToSelection?.invoke(quote)
                        clearSelection()
                    },
                )
            }
        }
    }
}

/**
 * Places the selection menu just above the selection (or the text box),
 * falling back below if there is no room near the top of the window.
 */
private class SelectionMenuPopupPositionProvider(
    private val preferredLocalY: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 8
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        // preferredLocalY is relative to the text box top (selection center).
        val selectionWindowY = anchorBounds.top + preferredLocalY
        val aboveY = selectionWindowY - popupContentSize.height - 12
        val y = if (aboveY >= margin) {
            aboveY
        } else {
            (selectionWindowY + 16)
                .coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        }
        return IntOffset(x, y)
    }
}

/** Y of the selection midpoint inside the text layout, or 0 as fallback. */
internal fun selectionMenuAnchorY(
    layout: TextLayoutResult?,
    range: IntRange?,
): Int {
    if (layout == null || range == null) return 0
    val startBox = layout.getBoundingBox(range.first)
    val endBox = layout.getBoundingBox(range.last.coerceAtMost(layout.layoutInput.text.length - 1).coerceAtLeast(0))
    return ((startBox.top + endBox.bottom) / 2f).roundToInt()
}

@Composable
private fun MessageTextSelectionMenu(
    canReply: Boolean,
    onCopy: () -> Unit,
    onReply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(min = 156.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.98f))
            .testTag("message-text-selection-menu")
            .semantics { contentDescription = "Меню выделения текста" },
    ) {
        SelectionMenuItem(
            icon = "⧉",
            label = "Копировать",
            testTag = "message-text-selection-copy",
            onClick = onCopy,
        )
        if (canReply) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                thickness = 0.5.dp,
            )
            SelectionMenuItem(
                icon = "↩",
                label = "Ответить",
                testTag = "message-text-selection-reply",
                onClick = onReply,
            )
        }
    }
}

@Composable
private fun SelectionMenuItem(
    icon: String,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleSmall,
            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.9f),
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = GlagolitsaColors.TextPrimary,
        )
    }
}

internal fun messageSelectionRange(
    start: Int?,
    end: Int?,
    textLength: Int,
): IntRange? {
    if (start == null || end == null || start == end || textLength <= 0) return null
    val from = min(start, end).coerceIn(0, textLength - 1)
    val toExclusive = maxOf(start, end).coerceIn(0, textLength)
    if (toExclusive <= from) return null
    return from until toExclusive
}

internal fun isAttachmentPlaceholderBody(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("📎")) return false
    return trimmed.indexOf('\n') < 0
}
