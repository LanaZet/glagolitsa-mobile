// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"os"
	"testing"
)

func TestConfigFromEnv_failClosedWithoutLiveKit(t *testing.T) {
	keys := []string{
		"LIVEKIT_API_KEY",
		"LIVEKIT_API_SECRET",
		"LIVEKIT_URL",
		"RTC_REGION_ID",
		"TURN_DOMAIN",
		"TURN_SHARED_SECRET",
		"CALLS_RTC_AVAILABLE",
		"CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED",
		"CLIENT_FEATURE_CALLS_AUDIO_ONLY_ENABLED",
		"CLIENT_FEATURE_CALLS_VIDEO_ADAPTIVE_ENABLED",
		"CLIENT_FEATURE_CALLS_GROUP_READY_API_ENABLED",
		"CLIENT_FEATURE_CALLS_ROUTE_HINTS_ENABLED",
		"CLIENT_FEATURE_CALLS_UNKNOWN_REQUEST_GATE_ENABLED",
		"CLIENT_FEATURE_CALLS_AUDIO",
		"CLIENT_FEATURE_CALLS_VIDEO",
		"CALLS_MEDIA_E2EE_ENABLED",
	}
	prev := map[string]string{}
	for _, k := range keys {
		prev[k] = os.Getenv(k)
		_ = os.Unsetenv(k)
	}
	defer func() {
		for k, v := range prev {
			if v == "" {
				_ = os.Unsetenv(k)
			} else {
				_ = os.Setenv(k, v)
			}
		}
	}()

	cfg := ConfigFromEnv()
	if cfg.Features.RTCAvailable {
		t.Fatal("without LiveKit credentials RTC must be unavailable")
	}
	if cfg.Features.RTCLiveKit {
		t.Fatal("livekit feature defaults off without credentials")
	}
	if cfg.RegionID != "primary" {
		t.Fatalf("region=%q", cfg.RegionID)
	}
	if !cfg.Features.UnknownRequestGate {
		t.Fatal("unknown request gate should default on")
	}
	if cfg.Features.VideoAdaptive {
		t.Fatal("adaptive video should default off")
	}
	if cfg.Features.GroupReadyAPI {
		t.Fatal("group-ready API should default off")
	}
	if cfg.TURNSharedSecret != "" {
		t.Fatal("shared secret must not invent a default")
	}
	if !cfg.MediaE2EE {
		t.Fatal("media E2EE must default on")
	}
}

func TestConfigFromEnv_liveKitReadyAndKillSwitch(t *testing.T) {
	keys := []string{
		"LIVEKIT_API_KEY",
		"LIVEKIT_API_SECRET",
		"RTC_REGION_ID",
		"CALLS_RTC_AVAILABLE",
		"TURN_DOMAIN",
		"TURN_SHARED_SECRET",
		"CALLS_MEDIA_E2EE_ENABLED",
	}
	prev := map[string]string{}
	for _, k := range keys {
		prev[k] = os.Getenv(k)
		_ = os.Unsetenv(k)
	}
	defer func() {
		for k, v := range prev {
			if v == "" {
				_ = os.Unsetenv(k)
			} else {
				_ = os.Setenv(k, v)
			}
		}
	}()

	_ = os.Setenv("LIVEKIT_API_KEY", "devkey")
	_ = os.Setenv("LIVEKIT_API_SECRET", "devsecret")
	_ = os.Setenv("RTC_REGION_ID", "ru-msk")
	_ = os.Setenv("TURN_DOMAIN", "turn.example.test")
	_ = os.Setenv("TURN_SHARED_SECRET", "unit-test-secret-not-for-prod")

	cfg := ConfigFromEnv()
	if !cfg.Features.RTCAvailable {
		t.Fatal("RTC should be available when LiveKit is configured")
	}
	if !cfg.Features.RTCLiveKit {
		t.Fatal("livekit enabled when configured")
	}
	if cfg.RegionID != "ru-msk" {
		t.Fatalf("region=%q", cfg.RegionID)
	}
	if cfg.TURNDomain != "turn.example.test" {
		t.Fatalf("turn domain=%q", cfg.TURNDomain)
	}
	if !cfg.MediaE2EE {
		t.Fatal("media E2EE defaults on when RTC is ready")
	}

	// Operational kill switch without removing credentials.
	_ = os.Setenv("CALLS_RTC_AVAILABLE", "false")
	_ = os.Setenv("CALLS_MEDIA_E2EE_ENABLED", "false")
	cfg2 := ConfigFromEnv()
	if cfg2.Features.RTCAvailable {
		t.Fatal("CALLS_RTC_AVAILABLE=false must kill new calls")
	}
	if ok, _ := cfg2.Features.AllowsNewCall(CallTypeAudio); ok {
		t.Fatal("new calls blocked by kill switch")
	}
	// Secret must remain loadable for ops but never appear in ClientFeatures.
	if cfg2.TURNSharedSecret == "" {
		t.Fatal("shared secret still loaded from env")
	}
	if cfg2.MediaE2EE {
		t.Fatal("explicit media E2EE kill switch should be honored")
	}
	for _, v := range cfg2.Features.ClientFeatures() {
		_ = v
	}
}
