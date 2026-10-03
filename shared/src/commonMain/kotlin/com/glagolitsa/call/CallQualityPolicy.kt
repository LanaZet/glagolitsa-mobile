// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/**
 * Audio-first quality FSM. Downgrade is fast; upgrade is slow (hysteresis).
 * Inputs must be redacted stats — no peer IPs or full ICE candidates.
 */
enum class CallNetworkMode {
    NO_PATH,
    EMERGENCY_VOICE,
    VOICE_ONLY,
    VIDEO_PROBE,
    VIDEO_LOW,
    VIDEO_NORMAL,
}

data class CallQualitySample(
    val connectionQuality: String? = null, // Lost, Poor, Good, Excellent, Unknown
    val rttMs: Int? = null,
    val packetLossPercent: Float? = null,
    val jitterMs: Float? = null,
    val availableOutgoingKbps: Int? = null,
    val noRtpProgress: Boolean = false,
    val candidateType: String? = null, // host, srflx, relay
    val candidateProtocol: String? = null, // udp, tcp, tls
    val routeClass: String? = null,
    val vpnDetected: Boolean = false,
)

data class CallQualityDecision(
    val mode: CallNetworkMode,
    val notice: String? = null,
    val allowVideoOffer: Boolean = false,
    val forceVideoOff: Boolean = false,
)

class CallQualityPolicy(
    private var mode: CallNetworkMode = CallNetworkMode.VOICE_ONLY,
    private var goodStreak: Int = 0,
    private var badStreak: Int = 0,
    private var autoVideoOffCount: Int = 0,
    private var userDisabledVideo: Boolean = false,
) {
    fun currentMode(): CallNetworkMode = mode

    fun onUserDisabledVideo() {
        userDisabledVideo = true
    }

    fun evaluate(sample: CallQualitySample): CallQualityDecision {
        val critical = isCritical(sample)
        val poor = isPoor(sample)
        val good = isGood(sample)

        if (critical) {
            badStreak++
            goodStreak = 0
            return enter(CallNetworkMode.EMERGENCY_VOICE, notice = "Сеть очень слабая. Сохраняем только голос.", forceVideoOff = true)
        }
        if (poor) {
            badStreak++
            goodStreak = 0
            if (badStreak >= 2 || mode.ordinal > CallNetworkMode.VOICE_ONLY.ordinal) {
                if (mode.ordinal > CallNetworkMode.VOICE_ONLY.ordinal) {
                    autoVideoOffCount++
                }
                return enter(
                    CallNetworkMode.VOICE_ONLY,
                    notice = if (mode.ordinal > CallNetworkMode.VOICE_ONLY.ordinal) {
                        "Сеть слабая. Отключаем видео, чтобы сохранить звонок."
                    } else {
                        null
                    },
                    forceVideoOff = true,
                )
            }
            return CallQualityDecision(mode = mode, forceVideoOff = mode <= CallNetworkMode.VOICE_ONLY)
        }

        badStreak = 0
        if (good) {
            goodStreak++
        } else {
            goodStreak = 0
        }

        // Upgrade only after sustained good samples.
        if (goodStreak >= 10 && mode == CallNetworkMode.EMERGENCY_VOICE) {
            return enter(CallNetworkMode.VOICE_ONLY)
        }
        val suppressVideo = userDisabledVideo || autoVideoOffCount >= 2
        val relayTls = sample.candidateType.equals("relay", true) &&
            (sample.candidateProtocol.equals("tls", true) || sample.candidateProtocol.equals("tcp", true))
        val allowOffer = goodStreak >= 15 && !suppressVideo && !relayTls &&
            sample.routeClass != "international" && sample.routeClass != "degraded"

        if (allowOffer && mode == CallNetworkMode.VOICE_ONLY) {
            return CallQualityDecision(
                mode = mode,
                notice = "Интернет стабильный. Можно включить видео.",
                allowVideoOffer = true,
            )
        }
        return CallQualityDecision(mode = mode, allowVideoOffer = allowOffer)
    }

    private fun enter(
        next: CallNetworkMode,
        notice: String? = null,
        forceVideoOff: Boolean = false,
    ): CallQualityDecision {
        mode = next
        return CallQualityDecision(
            mode = next,
            notice = notice,
            forceVideoOff = forceVideoOff || next <= CallNetworkMode.VOICE_ONLY,
        )
    }

    private fun isCritical(s: CallQualitySample): Boolean {
        if (s.noRtpProgress) return true
        if (s.connectionQuality.equals("Lost", true)) return true
        if ((s.rttMs ?: 0) > 1500) return true
        if ((s.packetLossPercent ?: 0f) > 20f) return true
        if ((s.availableOutgoingKbps ?: 1000) < 30) return true
        return false
    }

    private fun isPoor(s: CallQualitySample): Boolean {
        if (s.connectionQuality.equals("Poor", true)) return true
        val rtt = s.rttMs ?: 0
        val loss = s.packetLossPercent ?: 0f
        val jitter = s.jitterMs ?: 0f
        if (rtt in 600..1500) return true
        if (loss in 8f..20f) return true
        if (jitter > 100f) return true
        if ((s.availableOutgoingKbps ?: 1000) < 120) return true
        return false
    }

    private fun isGood(s: CallQualitySample): Boolean {
        val q = s.connectionQuality
        if (q.equals("Lost", true) || q.equals("Poor", true)) return false
        if ((s.rttMs ?: 9999) >= 250) return false
        if ((s.packetLossPercent ?: 100f) >= 3f) return false
        if ((s.availableOutgoingKbps ?: 0) < 500 && s.availableOutgoingKbps != null) return false
        return true
    }
}

/** Local-only redacted diagnostics (no peer IP, tokens, keys, names). */
data class CallDiagnosticsSnapshot(
    val callId: String,
    val mode: String,
    val rttBucket: String? = null,
    val lossBucket: String? = null,
    val candidateType: String? = null,
    val candidateProtocol: String? = null,
    val routeClass: String? = null,
    val vpnDetected: Boolean = false,
    val quality: String? = null,
)

fun CallQualitySample.toDiagnostics(callId: String, mode: CallNetworkMode): CallDiagnosticsSnapshot =
    CallDiagnosticsSnapshot(
        callId = callId,
        mode = mode.name,
        rttBucket = sRttBucket(rttMs),
        lossBucket = sLossBucket(packetLossPercent),
        candidateType = candidateType,
        candidateProtocol = candidateProtocol,
        routeClass = routeClass,
        vpnDetected = vpnDetected,
        quality = connectionQuality,
    )

private fun sRttBucket(rtt: Int?): String? = when {
    rtt == null -> null
    rtt < 50 -> "0-50"
    rtt < 100 -> "50-100"
    rtt < 200 -> "100-200"
    rtt < 400 -> "200-400"
    rtt < 800 -> "400-800"
    else -> "800+"
}

private fun sLossBucket(loss: Float?): String? = when {
    loss == null -> null
    loss < 1f -> "0-1"
    loss < 3f -> "1-3"
    loss < 8f -> "3-8"
    loss < 20f -> "8-20"
    else -> "20+"
}
