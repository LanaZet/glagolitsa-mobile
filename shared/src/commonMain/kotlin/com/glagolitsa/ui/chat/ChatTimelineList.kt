// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.model.Message
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun ChatTimelineList(
    timeline: List<ChatTimelineItem>,
    listState: LazyListState,
    loadingOlder: Boolean,
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = true,
    itemSpacing: Dp = 2.dp,
    attachmentKindFor: (Message) -> AttachmentKind? = { null },
    isOwnMessage: (Message) -> Boolean = { false },
    onJumpToLatest: (() -> Unit)? = null,
    bubbleContent: @Composable (message: Message, messageIndex: Int, modifier: Modifier) -> Unit,
) {
    val visual = remember(timeline, loadingOlder) {
        visualTimelineItems(timeline, loadingOlder)
    }
    val pinnedToLatest by remember(listState, reverseLayout) {
        derivedStateOf {
            isPinnedToLatest(
                reverseLayout = reverseLayout,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                canScrollForward = listState.canScrollForward,
            )
        }
    }
    val stickyDate by remember(listState, visual, reverseLayout) {
        derivedStateOf {
            stickyDateLabelForVisibleIndices(
                visual = visual,
                visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index },
                reverseLayout = reverseLayout,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = reverseLayout,
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat-messages"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            items(
                items = visual,
                key = { it.listKey },
                contentType = { visualItemContentType(it, attachmentKindFor) },
            ) { item ->
                when (item) {
                    ChatTimelineVisualItem.LoadingOlder -> LoadingOlderRow()
                    is ChatTimelineVisualItem.Row -> when (val row = item.item) {
                        is ChatTimelineItem.DayHeader -> ChatTimelineDayHeader(row.label)
                        is ChatTimelineItem.Bubble -> {
                            val own = isOwnMessage(row.message)
                            bubbleContent(
                                row.message,
                                row.messageIndex,
                                if (own) {
                                    Modifier.animateItem()
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }

        stickyDate?.let { label ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .testTag("chat-sticky-date"),
            ) {
                ChatDayPill(text = label)
            }
        }

        if (onJumpToLatest != null && !pinnedToLatest && visual.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GlagolitsaColors.Surface800.copy(alpha = 0.92f))
                    .clickable(onClick = onJumpToLatest)
                    .testTag("chat-jump-latest"),
                contentAlignment = Alignment.Center,
            ) {
                Text("↓", color = GlagolitsaColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun LoadingOlderRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = GlagolitsaColors.AccentRed,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
internal fun ChatTimelineDayHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ChatDayPill(text = label)
    }
}
