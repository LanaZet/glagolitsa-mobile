// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Call feature keys from server update policy.
 *
 * Defaults are fail-closed for media paths that are not yet production-ready.
 * Product intent flags ([AUDIO], [VIDEO]) may still be true while LiveKit is off.
 */
object CallFeatureKeys {
    const val AUDIO = "calls_audio"
    const val VIDEO = "calls_video"
    const val RTC_LIVEKIT = "calls_rtc_livekit_enabled"
    const val AUDIO_ONLY = "calls_audio_only_enabled"
    const val VIDEO_ADAPTIVE = "calls_video_adaptive_enabled"
    const val GROUP_READY_API = "calls_group_ready_api_enabled"
    const val ROUTE_HINTS = "calls_route_hints_enabled"
    const val UNKNOWN_REQUEST_GATE = "calls_unknown_request_gate_enabled"
}

/**
 * Evaluates call UI/control gates from a feature map (usually
 * [ClientFeatureFlags.snapshot] or update-policy features).
 *
 * Does not log or store credentials, tokens, or peer identifiers.
 */
object CallFeaturePolicy {

    fun isEnabled(features: Map<String, Boolean>, key: String, default: Boolean = false): Boolean =
        features[key] ?: default

    /** Hide start-call affordances when RTC edge is killed or audio is off. */
    fun canStartAudioCall(features: Map<String, Boolean>): Boolean {
        if (!isEnabled(features, CallFeatureKeys.AUDIO, default = true)) return false
        val livekit = isEnabled(features, CallFeatureKeys.RTC_LIVEKIT, default = false)
        val audioOnly = isEnabled(features, CallFeatureKeys.AUDIO_ONLY, default = false)
        return livekit || audioOnly
    }

    fun canStartVideoCall(features: Map<String, Boolean>): Boolean {
        if (!canStartAudioCall(features)) return false
        return isEnabled(features, CallFeatureKeys.VIDEO, default = false)
    }

    fun isLiveKitEnabled(features: Map<String, Boolean>): Boolean =
        isEnabled(features, CallFeatureKeys.RTC_LIVEKIT, default = false)

    fun isAdaptiveVideoEnabled(features: Map<String, Boolean>): Boolean =
        canStartVideoCall(features) &&
            isEnabled(features, CallFeatureKeys.VIDEO_ADAPTIVE, default = false)

    fun isGroupReadyApiEnabled(features: Map<String, Boolean>): Boolean =
        isEnabled(features, CallFeatureKeys.GROUP_READY_API, default = false)

    fun isRouteHintsEnabled(features: Map<String, Boolean>): Boolean =
        isEnabled(features, CallFeatureKeys.ROUTE_HINTS, default = false)

    /** Security default: unknown callers go through request gate. */
    fun isUnknownRequestGateEnabled(features: Map<String, Boolean>): Boolean =
        isEnabled(features, CallFeatureKeys.UNKNOWN_REQUEST_GATE, default = true)

    fun startCallBlockedReason(features: Map<String, Boolean>, callType: String): String? {
        if (!canStartAudioCall(features)) {
            return "Звонки временно недоступны"
        }
        if (callType == CallType.VIDEO && !canStartVideoCall(features)) {
            return "Видеозвонки отключены"
        }
        return null
    }
}
