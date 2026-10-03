// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import com.glagolitsa.ui.components.SlavicPillButton
import com.glagolitsa.ui.components.SlavicPillTextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.User
import com.glagolitsa.model.UserPresence
import com.glagolitsa.model.avatarLabel
import com.glagolitsa.model.displayLabel
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.theme.GlagolitsaColors

/** Шапка профиля: аватар, имя, @username, должность и статус. */
@Composable
fun ProfileHeaderSection(
    user: User?,
    form: ProfileFormState,
    onPickAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ProfileAvatar(
            avatarUrl = form.avatarUrl,
            fallbackLabel = user?.avatarLabel() ?: "?",
            presenceColor = form.presence.color(),
        )

        SlavicPillTextButton(onClick = onPickAvatar) {
            Text("Изменить фото")
        }

        Text(
            text = form.displayName.ifBlank { user?.displayLabel() ?: "—" },
            style = MaterialTheme.typography.headlineSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "@${user?.username ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextMuted,
        )

        if (form.status.isNotBlank()) {
            Text(
                text = form.status,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
            )
        }
    }
}

/** Редактируемые поля и данные аккаунта в одной группе. */
@Composable
fun ProfileDetailsSection(
    user: User?,
    form: ProfileFormState,
    onFormChange: (ProfileFormState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileSectionTitle("Данные профиля")
        profileField(
            value = form.displayName,
            label = "Отображаемое имя",
            onValueChange = { onFormChange(form.copy(displayName = it)) },
        )
        profileField(
            value = form.status,
            label = "Короткая фраза",
            onValueChange = { onFormChange(form.copy(status = it)) },
        )
        GlagolitsaInput(
            value = form.bio,
            onValueChange = { onFormChange(form.copy(bio = it)) },
            placeholder = "О себе",
            modifier = Modifier.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Multiline,
            singleLine = false,
            minLines = 3,
            maxLines = 6,
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = GlagolitsaColors.GlassBorder.copy(alpha = 0.15f),
        )

        ProfileInfoRow(
            label = "Имя пользователя",
            value = "@${user?.username ?: "—"}",
            compact = true,
        )
    }
}

@Composable
private fun profileField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    GlagolitsaInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Кнопки сохранения, выхода и сообщение об успехе. */
@Composable
fun ProfileActionsSection(
    saving: Boolean,
    savedMessage: String?,
    onSave: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        savedMessage?.let {
            Text(it, color = GlagolitsaColors.AccentRedText)
        }

        SlavicPillButton(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Сохранение…" else "Сохранить изменения")
        }

        SlavicPillTextButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Выйти из аккаунта")
        }
    }
}
