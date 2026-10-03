// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.profile.rememberAvatarPicker
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.profile_avatar_camera
import org.jetbrains.compose.resources.painterResource

/**
 * Group/channel icon: tap the camera badge to pick a photo from the gallery.
 */
@Composable
fun ConversationIconEditor(
    avatarUrl: String?,
    fallbackLabel: String,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    enabled: Boolean = true,
    saving: Boolean = false,
    hint: String = "Иконка из галереи",
) {
    val openPicker = rememberAvatarPicker { dataUrl ->
        if (dataUrl.isNullOrBlank()) return@rememberAvatarPicker
        onPicked(dataUrl)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = if (enabled && !saving) {
                Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = openPicker,
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Выбрать иконку из галереи"
                    }
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(
                avatarUrl = avatarUrl,
                fallbackLabel = fallbackLabel,
                size = size,
            )
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size((size.value * 0.32f).coerceIn(18f, 32f).dp),
                    color = GlagolitsaColors.AccentRed,
                    strokeWidth = 2.dp,
                )
            }
            if (enabled && !saving) {
                val badgeSize = (size.value * 0.32f).coerceIn(26f, 34f).dp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(GlagolitsaColors.Background950.copy(alpha = 0.94f))
                        .border(
                            width = 1.dp,
                            color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.72f),
                            shape = CircleShape,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = openPicker,
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = "Выбрать иконку из галереи"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(Res.drawable.profile_avatar_camera),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(badgeSize * 0.68f),
                    )
                }
            }
        }
        Text(
            text = if (enabled) "Выбрать из галереи" else hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) GlagolitsaColors.OrnamentGold else GlagolitsaColors.TextTertiary,
            modifier = Modifier
                .padding(top = 8.dp)
                .then(
                    if (enabled && !saving) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = openPicker,
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = "Выбрать иконку из галереи"
                            }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
