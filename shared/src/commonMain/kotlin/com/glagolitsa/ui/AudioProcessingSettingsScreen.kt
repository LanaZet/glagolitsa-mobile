// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.audio.AudioProcessingCoordinator
import com.glagolitsa.audio.AudioProcessingSettingsKeys
import com.glagolitsa.audio.AudioProcessingUserSettings
import com.glagolitsa.audio.CallNoisePreference
import com.glagolitsa.audio.CallNsArm
import com.glagolitsa.audio.DeepFilterNetRegistry
import com.glagolitsa.audio.VoiceNoisePreference
import com.glagolitsa.audio.toStorageMap
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import kotlinx.coroutines.launch

@Composable
fun AudioProcessingSettingsScreen(
    repository: MessengerRepository,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
) {
    val settings by AudioProcessingCoordinator.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val capability = remember { AudioProcessingCoordinator.capability }
    val dfn = DeepFilterNetRegistry.available

    LaunchedEffect(repository) {
        AudioProcessingCoordinator.hydrate(
            loadAudioProcessingSettings(repository),
        )
    }

    fun persist(next: AudioProcessingUserSettings) {
        AudioProcessingCoordinator.updateSettings(next)
        scope.launch {
            next.toStorageMap().forEach { (key, value) ->
                repository.saveProfileSetting(key, value)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950)
            .screenTopSafeArea()
            .padding(bottom = bottomContentPadding),
    ) {
        AppTopBar(
            title = "Шумоподавление",
            navigation = {
                AppTopBarTextAction(text = "Назад", onClick = onBack)
            },
            modifier = Modifier.padding(horizontal = GlagolitsaSpacing.xl),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GlagolitsaSpacing.xl, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Звонки",
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "База всегда WebRTC AEC + NS + AGC. Усиленный режим — рядом, с A/B, чтобы не убивать голос. DeepFilterNet только на сильных устройствах.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
            )
            CallNoisePreference.entries.forEach { option ->
                PreferenceChoiceRow(
                    title = callPreferenceTitle(option),
                    subtitle = callPreferenceSubtitle(option, capability.strongForHqCalls, dfn),
                    selected = settings.callPreference == option,
                    onClick = { persist(settings.copy(callPreference = option)) },
                )
            }
            Text(
                text = "Голосовые сообщения",
                style = MaterialTheme.typography.titleSmall,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Оригинал остаётся на устройстве до отправки. Очищенную версию можно послушать и отправить. В E2EE уходит уже выбранный файл.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
            )
            VoiceNoisePreference.entries.forEach { option ->
                PreferenceChoiceRow(
                    title = voicePreferenceTitle(option),
                    subtitle = voicePreferenceSubtitle(option, dfn),
                    selected = settings.voicePreference == option,
                    onClick = { persist(settings.copy(voicePreference = option)) },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusFooter(settings, capability.strongForHqCalls, dfn),
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun PreferenceChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    GlagolitsaColors.OrnamentGold.copy(alpha = 0.12f)
                } else {
                    GlagolitsaColors.Surface800.copy(alpha = 0.72f)
                },
            )
            .border(
                0.8.dp,
                if (selected) GlagolitsaColors.OrnamentGold.copy(alpha = 0.45f) else GlagolitsaColors.GlassBorder,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
            )
            if (selected) {
                Text(
                    text = "Выбрано",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.OrnamentGold,
                )
            }
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}

private fun callPreferenceTitle(option: CallNoisePreference): String = when (option) {
    CallNoisePreference.Auto -> "Авто"
    CallNoisePreference.Off -> "Выкл"
    CallNoisePreference.Base -> "База (AEC + NS + AGC)"
    CallNoisePreference.Enhanced -> "Усиленный (RNNoise)"
    CallNoisePreference.HighQuality -> "Высокое качество"
}

private fun callPreferenceSubtitle(
    option: CallNoisePreference,
    strongDevice: Boolean,
    dfn: Boolean,
): String = when (option) {
    CallNoisePreference.Auto -> "A/B база против RNNoise. DeepFilterNet — только если устройство тянет."
    CallNoisePreference.Off -> "Без обработки захвата. Эхо и шум могут остаться."
    CallNoisePreference.Base -> "Нативный WebRTC APM на каждом звонке."
    CallNoisePreference.Enhanced -> "APM плюс RNNoise-класс. Если голос проседает — режим сам отключится."
    CallNoisePreference.HighQuality -> if (strongDevice && dfn) {
        "DeepFilterNet на этом устройстве доступен."
    } else {
        "Нужен сильный аппарат и DeepFilterNet. Иначе останется RNNoise."
    }
}

private fun voicePreferenceTitle(option: VoiceNoisePreference): String = when (option) {
    VoiceNoisePreference.Auto -> "Авто"
    VoiceNoisePreference.Off -> "Без очистки"
    VoiceNoisePreference.Light -> "Лёгкая (RNNoise)"
    VoiceNoisePreference.HighQuality -> "Высокое качество"
}

private fun voicePreferenceSubtitle(option: VoiceNoisePreference, dfn: Boolean): String = when (option) {
    VoiceNoisePreference.Auto,
    VoiceNoisePreference.HighQuality,
    -> if (dfn) "DeepFilterNet 3" else "DeepFilterNet не загружен — лёгкая очистка как запасной путь"
    VoiceNoisePreference.Off -> "Отправляется сырая запись"
    VoiceNoisePreference.Light -> "Быстрый спектральный RNNoise-класс"
}

private fun statusFooter(
    settings: AudioProcessingUserSettings,
    strongDevice: Boolean,
    dfn: Boolean,
): String {
    val arm = when (settings.experimentArm) {
        CallNsArm.Enhanced -> "A/B: усиленный"
        CallNsArm.Base -> "A/B: база"
        null -> "A/B: не назначен"
    }
    val extra = buildList {
        add(arm)
        if (settings.experimentDisabled) add("усиленный отключён после защиты голоса")
        add(if (strongDevice) "устройство тянет HQ" else "устройство слабое для HQ-звонка")
        add(if (dfn) "DeepFilterNet есть" else "DeepFilterNet нет")
    }
    return extra.joinToString(" · ")
}

internal suspend fun loadAudioProcessingSettings(
    repository: MessengerRepository,
): AudioProcessingUserSettings {
    val values = mapOf(
        AudioProcessingSettingsKeys.CALL_PREFERENCE to
            repository.loadProfileSetting(AudioProcessingSettingsKeys.CALL_PREFERENCE),
        AudioProcessingSettingsKeys.VOICE_PREFERENCE to
            repository.loadProfileSetting(AudioProcessingSettingsKeys.VOICE_PREFERENCE),
        AudioProcessingSettingsKeys.EXPERIMENT_ARM to
            repository.loadProfileSetting(AudioProcessingSettingsKeys.EXPERIMENT_ARM),
        AudioProcessingSettingsKeys.EXPERIMENT_DISABLED to
            repository.loadProfileSetting(AudioProcessingSettingsKeys.EXPERIMENT_DISABLED),
    )
    return com.glagolitsa.audio.audioProcessingSettingsFrom(values)
}
