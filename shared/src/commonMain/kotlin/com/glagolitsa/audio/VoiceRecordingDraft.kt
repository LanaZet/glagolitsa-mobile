// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import com.glagolitsa.ui.chat.PickedAttachment

data class VoiceRecordingDraft(
    val original: PickedAttachment,
    val processed: PickedAttachment? = null,
    val requestedEngine: VoiceEnhancerEngine = VoiceEnhancerEngine.None,
    val appliedEngine: VoiceEnhancerEngine = VoiceEnhancerEngine.None,
    val selected: VoiceSendVariant = if (processed != null) {
        VoiceSendVariant.Processed
    } else {
        VoiceSendVariant.Original
    },
    val reason: String = "",
) {
    val canSendProcessed: Boolean
        get() = processed != null && processed.bytes.isNotEmpty()

    fun withSelection(variant: VoiceSendVariant): VoiceRecordingDraft {
        val next = when {
            variant == VoiceSendVariant.Processed && canSendProcessed -> VoiceSendVariant.Processed
            else -> VoiceSendVariant.Original
        }
        return copy(selected = next)
    }

    fun chosen(): PickedAttachment =
        if (selected == VoiceSendVariant.Processed && canSendProcessed) {
            processed!!
        } else {
            original
        }
}
