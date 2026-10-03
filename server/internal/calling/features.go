// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

// Feature flag keys served to clients via client update policy and enforced
// server-side for call control-plane endpoints.
const (
	FeatureCallsAudio               = "calls_audio"
	FeatureCallsVideo               = "calls_video"
	FeatureCallsRTCLiveKit          = "calls_rtc_livekit_enabled"
	FeatureCallsAudioOnly           = "calls_audio_only_enabled"
	FeatureCallsVideoAdaptive       = "calls_video_adaptive_enabled"
	FeatureCallsGroupReadyAPI       = "calls_group_ready_api_enabled"
	FeatureCallsRouteHints          = "calls_route_hints_enabled"
	FeatureCallsUnknownRequestGate  = "calls_unknown_request_gate_enabled"
)

// FeatureSet is the server-side call feature matrix. Values come from env and
// can be killed without a DB migration.
type FeatureSet struct {
	Audio              bool
	Video              bool
	RTCLiveKit         bool
	AudioOnly          bool
	VideoAdaptive      bool
	GroupReadyAPI      bool
	RouteHints         bool
	UnknownRequestGate bool
	// RTCAvailable is the operational kill switch: when false, new calls and
	// media tokens are refused. History/read endpoints stay available.
	RTCAvailable bool
}

// AllowsNewCall reports whether the control plane may create a new call of the
// given type (audio|video). Fail closed when RTC is degraded or audio is off.
func (f FeatureSet) AllowsNewCall(callType string) (ok bool, reason string) {
	if !f.RTCAvailable {
		return false, "calls temporarily unavailable"
	}
	if !f.Audio {
		return false, "audio calls are disabled"
	}
	switch callType {
	case CallTypeAudio:
		return true, ""
	case CallTypeVideo:
		if !f.Video {
			return false, "video calls are disabled"
		}
		return true, ""
	default:
		return false, "unsupported call type"
	}
}

// AllowsStartCallUI is the client-facing composite: product audio flag plus an
// active media path (LiveKit) or explicit audio-only rollout flag.
func (f FeatureSet) AllowsStartCallUI() bool {
	return f.Audio && f.RTCAvailable && (f.RTCLiveKit || f.AudioOnly)
}

// AllowsLiveKitToken reports whether short-lived LiveKit tokens may be issued.
func (f FeatureSet) AllowsLiveKitToken() (ok bool, reason string) {
	if !f.RTCAvailable {
		return false, "calls temporarily unavailable"
	}
	if !f.RTCLiveKit {
		return false, "livekit calls are disabled"
	}
	return true, ""
}

// ClientFeatures maps flags for the update-policy payload. Secrets and host
// credentials never appear here.
func (f FeatureSet) ClientFeatures() map[string]bool {
	return map[string]bool{
		FeatureCallsAudio:              f.Audio,
		FeatureCallsVideo:              f.Video,
		FeatureCallsRTCLiveKit:         f.RTCLiveKit && f.RTCAvailable,
		FeatureCallsAudioOnly:          f.AudioOnly && f.Audio && f.RTCAvailable,
		FeatureCallsVideoAdaptive:      f.VideoAdaptive && f.Video && f.RTCAvailable,
		FeatureCallsGroupReadyAPI:      f.GroupReadyAPI,
		FeatureCallsRouteHints:         f.RouteHints,
		FeatureCallsUnknownRequestGate: f.UnknownRequestGate,
	}
}
