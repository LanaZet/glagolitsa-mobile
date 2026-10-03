// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.audio.VoiceEnhancerEngine
import com.glagolitsa.audio.VoiceRecordingDraft
import com.glagolitsa.audio.VoiceSendVariant
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.ui.components.GlagolitsaSwitch
import com.glagolitsa.ui.theme.GlagolitsaColors

internal fun PickedAttachment.toDraftPreview(id: String): AttachmentPreview = AttachmentPreview(
    messageId = id,
    bytes = bytes,
    fileName = fileName,
    mimeType = mimeType,
    kind = kind,
    durationMs = durationMs,
    waveform = waveform,
)

@Composable
internal fun VoiceDraftBar(
    draft: VoiceRecordingDraft,
    playing: Boolean,
    onToggleProcessed: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val processedOn = draft.selected == VoiceSendVariant.Processed && draft.canSendProcessed
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.92f))
            .border(0.8.dp, GlagolitsaColors.GlassBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Голосовое готово",
            style = MaterialTheme.typography.titleSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = draftStatusLine(draft),
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (processedOn) "Отправить без шума" else "Отправить оригинал",
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            GlagolitsaSwitch(
                checked = processedOn,
                enabled = draft.canSendProcessed,
                compactTouchTarget = true,
                onCheckedChange = onToggleProcessed,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playing) "Стоп" else "Слушать",
                color = GlagolitsaColors.OrnamentGold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = "Отмена",
                color = GlagolitsaColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDiscard)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = "Отправить",
                color = GlagolitsaColors.OrnamentGold,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSend)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

internal fun draftStatusLine(draft: VoiceRecordingDraft): String {
    val duration = draft.original.durationMs?.let(::formatMediaDuration).orEmpty()
    val engine = when (draft.appliedEngine) {
        VoiceEnhancerEngine.DeepFilterNet -> "DeepFilterNet"
        VoiceEnhancerEngine.Rnnoise -> "лёгкая очистка"
        else -> null
    }
    return listOfNotNull(
        duration.takeIf { it.isNotBlank() },
        if (draft.canSendProcessed) "очищено · $engine" else "оригинал без очистки",
    ).joinToString(" · ")
}
