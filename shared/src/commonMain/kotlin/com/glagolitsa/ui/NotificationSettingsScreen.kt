// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.NotificationPreferences
import com.glagolitsa.model.NotificationSettingsPolicy
import com.glagolitsa.model.UpdateNotificationPreferencesRequest
import com.glagolitsa.push.LocalMessageNotifier
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.GlagolitsaSwitch
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import kotlinx.coroutines.launch

/**
 * Settings → Уведомления.
 * Signal: OS permission first, then account toggles. Preview off by default (E2EE).
 */
@Composable
fun NotificationSettingsScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val permission = rememberNotificationPermissionController()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf<NotificationPreferences?>(null) }

    fun reload() {
        scope.launch {
            loading = prefs == null
            error = null
            permission.refresh()
            runCatching { repository.getNotificationPreferences() }
                .onSuccess { prefs = it }
                .onFailure { error = it.message ?: "Не удалось загрузить настройки" }
            loading = false
        }
    }

    fun patch(
        request: UpdateNotificationPreferencesRequest,
        optimistic: (NotificationPreferences) -> NotificationPreferences,
    ) {
        val previous = prefs ?: return
        prefs = optimistic(previous)
        error = null
        saving = true
        scope.launch {
            runCatching { repository.updateNotificationPreferences(request) }
                .onSuccess { updated ->
                    prefs = updated
                    if (!updated.messages_enabled) {
                        LocalMessageNotifier.cancelMessages()
                    }
                }
                .onFailure {
                    prefs = previous
                    error = it.message ?: "Не удалось сохранить"
                }
            saving = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950)
            .screenTopSafeArea()
            .padding(bottom = bottomContentPadding),
    ) {
        AppTopBar(
            title = "Уведомления",
            navigation = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
            modifier = Modifier.padding(horizontal = GlagolitsaSpacing.xl),
        )

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }
            return
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GlagolitsaSpacing.xl, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val systemBlocked = NotificationSettingsPolicy.systemPermissionBlocked(
                permission.notificationsEnabled,
            )
            if (systemBlocked) {
                SystemPermissionCard(
                    onAllow = {
                        permission.request { granted ->
                            if (granted) {
                                scope.launch { repository.registerPushTokenIfNeeded() }
                            }
                        }
                    },
                    onOpenSettings = { permission.openSystemSettings() },
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.AccentRed,
                )
            }

            val current = prefs
            if (current == null) {
                Text(
                    text = "Нет данных с сервера. Проверьте соединение и откройте экран снова.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
            } else {
                SectionLabel("События")
                SettingsSwitchCard(
                    title = "Сообщения",
                    subtitle = "Новые письма в фоне. Текст не уходит в Google/Apple — только локально после расшифровки.",
                    checked = current.messages_enabled,
                    enabled = !saving,
                    onCheckedChange = { checked ->
                        if (checked && systemBlocked) {
                            permission.request { granted ->
                                if (granted) {
                                    scope.launch { repository.registerPushTokenIfNeeded() }
                                }
                            }
                        }
                        patch(
                            UpdateNotificationPreferencesRequest(messages_enabled = checked),
                        ) { it.copy(messages_enabled = checked) }
                    },
                )
                SettingsSwitchCard(
                    title = "Звонки",
                    subtitle = "Входящий и пропущенный звонок.",
                    checked = current.calls_enabled,
                    enabled = !saving,
                    onCheckedChange = { checked ->
                        patch(
                            UpdateNotificationPreferencesRequest(calls_enabled = checked),
                        ) { it.copy(calls_enabled = checked) }
                    },
                )
                SettingsSwitchCard(
                    title = "Новое устройство",
                    subtitle = "Если к аккаунту вошли с другого телефона.",
                    checked = current.new_device_enabled,
                    enabled = !saving,
                    onCheckedChange = { checked ->
                        patch(
                            UpdateNotificationPreferencesRequest(new_device_enabled = checked),
                        ) { it.copy(new_device_enabled = checked) }
                    },
                )

                SectionLabel("Конфиденциальность")
                SettingsSwitchCard(
                    title = "Имя отправителя",
                    subtitle = "Показывать имя в шторке. По умолчанию выкл — как в Signal.",
                    checked = current.show_sender_name,
                    enabled = !saving && current.messages_enabled,
                    onCheckedChange = { checked ->
                        patch(
                            UpdateNotificationPreferencesRequest(show_sender_name = checked),
                        ) { it.copy(show_sender_name = checked) }
                    },
                )
                SettingsSwitchCard(
                    title = "Текст сообщения",
                    subtitle = "Показывать расшифрованный текст в шторке. На заблокированном экране это видно всем рядом.",
                    checked = current.show_message_preview,
                    enabled = !saving && current.messages_enabled,
                    onCheckedChange = { checked ->
                        patch(
                            UpdateNotificationPreferencesRequest(show_message_preview = checked),
                        ) { it.copy(show_message_preview = checked) }
                    },
                )
                SettingsSwitchCard(
                    title = "Значок непрочитанных",
                    subtitle = "Число на иконке приложения.",
                    checked = current.badge_enabled,
                    enabled = !saving,
                    onCheckedChange = { checked ->
                        patch(
                            UpdateNotificationPreferencesRequest(badge_enabled = checked),
                        ) { it.copy(badge_enabled = checked) }
                    },
                )

                if (permission.notificationsEnabled) {
                    GlagolitsaButton(
                        onClick = { permission.openSystemSettings() },
                        style = GlagolitsaButtonStyle.Tertiary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Системные настройки уведомлений", color = GlagolitsaColors.TextPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = GlagolitsaColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SystemPermissionCard(
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlagolitsaColors.AccentRed.copy(alpha = 0.12f))
            .border(0.8.dp, GlagolitsaColors.AccentRed.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Система блокирует уведомления",
            style = MaterialTheme.typography.titleSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Без разрешения Android/iOS пуши не появятся, даже если тумблеры ниже включены.",
            style = MaterialTheme.typography.bodySmall,
            color = GlagolitsaColors.TextSecondary,
        )
        GlagolitsaButton(
            onClick = onAllow,
            style = GlagolitsaButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Разрешить", color = GlagolitsaColors.TextPrimary)
        }
        GlagolitsaButton(
            onClick = onOpenSettings,
            style = GlagolitsaButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть настройки системы", color = GlagolitsaColors.TextPrimary)
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.72f))
            .border(0.8.dp, GlagolitsaColors.GlassBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextTertiary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextSecondary,
            )
        }
        GlagolitsaSwitch(
            checked = checked,
            enabled = enabled,
            compactTouchTarget = true,
            onCheckedChange = onCheckedChange,
        )
    }
}
