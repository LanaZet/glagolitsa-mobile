// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors

private val ChatEmojiPanelShape = RoundedCornerShape(22.dp)

private val ChatEmojiPanelItems = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
    "😇", "🙂", "😉", "😌", "😅", "🥹", "😢", "😭",
    "😡", "🤔", "🤝", "🙏", "👍", "👎", "👏", "🔥",
    "❤️", "💔", "✨", "🎉", "✅", "❌", "📎", "📌",
)

@Composable
fun EmojiPickerPanel(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ChatEmojiPanelShape)
            .background(GlagolitsaColors.ChatChromeFill)
            .border(1.dp, GlagolitsaColors.OverlayHairlineSoft, ChatEmojiPanelShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChatEmojiPanelItems.chunked(8).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowItems.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = { onEmojiSelected(emoji) })
                            .semantics {
                                role = Role.Button
                                contentDescription = emoji
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.titleMedium,
                            color = GlagolitsaColors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}
