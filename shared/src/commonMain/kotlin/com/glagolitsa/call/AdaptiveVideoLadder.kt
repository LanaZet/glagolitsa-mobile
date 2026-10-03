// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/**
 * Video is an upgrade over stable audio. Ladder profiles for probe/low/normal.
 */
data class VideoProfile(
    val mode: CallNetworkMode,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
)

object AdaptiveVideoLadder {
    val PROBE = VideoProfile(CallNetworkMode.VIDEO_PROBE, 160, 90, 7, 80)
    val LOW = VideoProfile(CallNetworkMode.VIDEO_LOW, 320, 180, 12, 180)
    val NORMAL = VideoProfile(CallNetworkMode.VIDEO_NORMAL, 640, 360, 20, 500)

    fun profileFor(mode: CallNetworkMode): VideoProfile? = when (mode) {
        CallNetworkMode.VIDEO_PROBE -> PROBE
        CallNetworkMode.VIDEO_LOW -> LOW
        CallNetworkMode.VIDEO_NORMAL -> NORMAL
        else -> null
    }

    /** Video must not start until audio is stable in VOICE_ONLY or better. */
    fun canStartVideoProbe(mode: CallNetworkMode, audioStableSeconds: Int): Boolean =
        mode >= CallNetworkMode.VOICE_ONLY &&
            mode != CallNetworkMode.NO_PATH &&
            mode != CallNetworkMode.EMERGENCY_VOICE &&
            audioStableSeconds >= 5

    fun shouldAutoDisableVideo(decision: CallQualityDecision): Boolean =
        decision.forceVideoOff || decision.mode <= CallNetworkMode.VOICE_ONLY
}
