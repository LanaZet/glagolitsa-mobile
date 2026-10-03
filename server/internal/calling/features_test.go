// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import "testing"

func TestFeatureSet_AllowsNewCall(t *testing.T) {
	base := FeatureSet{
		Audio:        true,
		Video:        true,
		RTCLiveKit:   true,
		AudioOnly:    true,
		RTCAvailable: true,
	}

	if ok, _ := base.AllowsNewCall(CallTypeAudio); !ok {
		t.Fatal("audio should be allowed")
	}
	if ok, _ := base.AllowsNewCall(CallTypeVideo); !ok {
		t.Fatal("video should be allowed")
	}

	degraded := base
	degraded.RTCAvailable = false
	if ok, reason := degraded.AllowsNewCall(CallTypeAudio); ok || reason == "" {
		t.Fatalf("degraded RTC must fail closed, ok=%v reason=%q", ok, reason)
	}

	noAudio := base
	noAudio.Audio = false
	if ok, _ := noAudio.AllowsNewCall(CallTypeAudio); ok {
		t.Fatal("audio flag off")
	}

	noVideo := base
	noVideo.Video = false
	if ok, _ := noVideo.AllowsNewCall(CallTypeVideo); ok {
		t.Fatal("video flag off")
	}
	if ok, _ := noVideo.AllowsNewCall(CallTypeAudio); !ok {
		t.Fatal("audio still ok when video off")
	}
}

func TestFeatureSet_AllowsLiveKitToken(t *testing.T) {
	f := FeatureSet{RTCLiveKit: true, RTCAvailable: true}
	if ok, _ := f.AllowsLiveKitToken(); !ok {
		t.Fatal("token should be allowed")
	}
	f.RTCLiveKit = false
	if ok, _ := f.AllowsLiveKitToken(); ok {
		t.Fatal("livekit flag off")
	}
	f.RTCLiveKit = true
	f.RTCAvailable = false
	if ok, _ := f.AllowsLiveKitToken(); ok {
		t.Fatal("rtc kill switch")
	}
}

func TestFeatureSet_ClientFeatures_redactsAvailability(t *testing.T) {
	f := FeatureSet{
		Audio:              true,
		Video:              true,
		RTCLiveKit:         true,
		AudioOnly:          true,
		VideoAdaptive:      true,
		GroupReadyAPI:      true,
		RouteHints:         true,
		UnknownRequestGate: true,
		RTCAvailable:       false,
	}
	m := f.ClientFeatures()
	// Kill switch must hide LiveKit/audio-only/adaptive from clients so UI
	// does not offer start-call when RTC edge is down.
	if m[FeatureCallsRTCLiveKit] {
		t.Fatal("livekit must appear disabled when RTC unavailable")
	}
	if m[FeatureCallsAudioOnly] {
		t.Fatal("audio-only must appear disabled when RTC unavailable")
	}
	if m[FeatureCallsVideoAdaptive] {
		t.Fatal("adaptive video must appear disabled when RTC unavailable")
	}
	// Base capability flags still report product intent; UI uses audio-only /
	// livekit composite for start-call gates.
	if !m[FeatureCallsAudio] {
		t.Fatal("calls_audio product flag should remain")
	}
	if !m[FeatureCallsUnknownRequestGate] {
		t.Fatal("unknown-request gate should default visible true")
	}
}
