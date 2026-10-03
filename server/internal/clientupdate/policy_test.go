// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package clientupdate

import (
	"os"
	"testing"
)

func TestCompareSemver(t *testing.T) {
	cases := []struct {
		a, b string
		want int // -1, 0, 1 relative
	}{
		{"1.9.0", "1.10.0", -1},
		{"1.10.0", "1.9.0", 1},
		{"0.1.0", "0.1.0", 0},
		{"1.2.3", "1.2", 1},
		{"1.2", "1.2.1", -1},
		{"v1.2.3", "1.2.3", 0},
		{"1.2.3-beta", "1.2.3", 0},
		{"1.2.3+meta", "1.2.3", 0},
		{"", "0.0.1", -1},
		{"1", "1.0.0", 0},
	}
	for _, tc := range cases {
		got := CompareSemver(tc.a, tc.b)
		if sign(got) != tc.want {
			t.Fatalf("CompareSemver(%q,%q)=%d want sign %d", tc.a, tc.b, got, tc.want)
		}
	}
}

func sign(n int) int {
	if n < 0 {
		return -1
	}
	if n > 0 {
		return 1
	}
	return 0
}

func TestIsClientTooOld_versionAndBuild(t *testing.T) {
	p := Policy{
		Android: PlatformPolicy{MinVersion: "1.0.0", MinBuild: 10},
	}
	if !IsClientTooOld(p, "android", "0.9.0", 1) {
		t.Fatal("expected too old by version")
	}
	if !IsClientTooOld(p, "android", "1.0.0", 5) {
		t.Fatal("expected too old by build")
	}
	if IsClientTooOld(p, "android", "1.0.0", 10) {
		t.Fatal("should be ok at min build")
	}
	if IsClientTooOld(p, "android", "1.1.0", 1) {
		// build 1 < min 10 → too old by build even if version ok
		// Wait: IsClientTooOld checks build first when MinBuild > 0 && build > 0
		// build=1 < 10 → true too old. So this is too old.
	}
	if !IsClientTooOld(p, "android", "1.1.0", 1) {
		t.Fatal("expected too old by build even when version above min")
	}
	if IsClientTooOld(p, "android", "1.1.0", 10) {
		t.Fatal("version and build ok")
	}
}

func TestIsClientTooOld_killSwitch(t *testing.T) {
	p := Policy{
		Android:    PlatformPolicy{MinVersion: "0.0.1"},
		KillSwitch: true,
	}
	if !IsClientTooOld(p, "android", "9.9.9", 999) {
		t.Fatal("kill switch")
	}
}

func TestIsClientTooOld_platformIsolation(t *testing.T) {
	p := Policy{
		Android: PlatformPolicy{MinVersion: "9.0.0"},
		IOS:     PlatformPolicy{MinVersion: "1.0.0"},
		Desktop: PlatformPolicy{MinVersion: "2.0.0"},
	}
	if !IsClientTooOld(p, "android", "1.0.0", 1) {
		t.Fatal("android should use android min")
	}
	if IsClientTooOld(p, "ios", "1.0.0", 1) {
		t.Fatal("ios at min should be ok")
	}
	if !IsClientTooOld(p, "ios", "0.9.0", 1) {
		t.Fatal("ios below min")
	}
	if !IsClientTooOld(p, "desktop", "1.0.0", 1) {
		t.Fatal("desktop below min")
	}
	// aliases
	if IsClientTooOld(p, "iphone", "1.5.0", 1) {
		t.Fatal("iphone alias → ios")
	}
	if IsClientTooOld(p, "macos", "2.0.0", 1) {
		t.Fatal("macos alias → desktop")
	}
}

func TestIsClientTooOld_emptyMin_noGate(t *testing.T) {
	p := Policy{Android: PlatformPolicy{}}
	if IsClientTooOld(p, "android", "0.0.1", 0) {
		t.Fatal("empty min should not block")
	}
}

func TestFromEnv_defaultsAndOverrides(t *testing.T) {
	// Isolate env for this process.
	keys := []string{
		"CLIENT_ANDROID_MIN_VERSION",
		"CLIENT_ANDROID_LATEST_VERSION",
		"CLIENT_ANDROID_MIN_BUILD",
		"CLIENT_ANDROID_LATEST_BUILD",
		"CLIENT_ANDROID_STORE_URL",
		"CLIENT_UPDATE_KILL_SWITCH",
		"CLIENT_FEATURE_CALLS_VIDEO",
		"CLIENT_FEATURE_CALLS_AUDIO",
		"CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED",
		"CLIENT_FEATURE_CALLS_AUDIO_ONLY_ENABLED",
		"CLIENT_FEATURE_CALLS_VIDEO_ADAPTIVE_ENABLED",
		"CLIENT_FEATURE_CALLS_GROUP_READY_API_ENABLED",
		"CLIENT_FEATURE_CALLS_ROUTE_HINTS_ENABLED",
		"CLIENT_FEATURE_CALLS_UNKNOWN_REQUEST_GATE_ENABLED",
		"LIVEKIT_API_KEY",
		"LIVEKIT_API_SECRET",
		"CALLS_RTC_AVAILABLE",
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

	def := FromEnv()
	if def.Android.MinVersion != "0.1.0" {
		t.Fatalf("default min=%q", def.Android.MinVersion)
	}
	if def.KillSwitch {
		t.Fatal("default kill switch off")
	}
	if def.Android.StoreURL == "" {
		t.Fatal("default android store url should be set")
	}
	// Without LiveKit credentials, media path flags stay off (fail closed).
	if def.Features["calls_rtc_livekit_enabled"] {
		t.Fatal("default livekit feature must be off without credentials")
	}
	if def.Features["calls_audio_only_enabled"] {
		t.Fatal("audio-only must be off when RTC unavailable")
	}
	if !def.Features["calls_audio"] {
		t.Fatal("default calls_audio product flag true")
	}
	if !def.Features["calls_video"] {
		t.Fatal("default calls_video product flag true")
	}
	if !def.Features["calls_unknown_request_gate_enabled"] {
		t.Fatal("unknown request gate defaults on")
	}
	if def.Features["calls_video_adaptive_enabled"] {
		t.Fatal("adaptive video defaults off")
	}

	_ = os.Setenv("CLIENT_ANDROID_MIN_VERSION", "0.3.0")
	_ = os.Setenv("CLIENT_ANDROID_LATEST_VERSION", "0.4.0")
	_ = os.Setenv("CLIENT_ANDROID_MIN_BUILD", "30")
	_ = os.Setenv("CLIENT_UPDATE_KILL_SWITCH", "true")
	_ = os.Setenv("CLIENT_FEATURE_CALLS_VIDEO", "false")
	_ = os.Setenv("LIVEKIT_API_KEY", "k")
	_ = os.Setenv("LIVEKIT_API_SECRET", "s")
	_ = os.Setenv("CLIENT_ANDROID_STORE_URL", "https://example.com/store")

	p := FromEnv()
	if p.Android.MinVersion != "0.3.0" || p.Android.LatestVersion != "0.4.0" {
		t.Fatalf("override versions: %+v", p.Android)
	}
	if p.Android.MinBuild != 30 {
		t.Fatalf("min build=%d", p.Android.MinBuild)
	}
	if !p.KillSwitch {
		t.Fatal("kill switch override")
	}
	if p.Features["calls_video"] {
		t.Fatal("feature override false")
	}
	if !p.Features["calls_rtc_livekit_enabled"] {
		t.Fatal("livekit enabled when credentials present")
	}
	if p.Android.StoreURL != "https://example.com/store" {
		t.Fatalf("store url=%q", p.Android.StoreURL)
	}
	if !IsClientTooOld(p, "android", "0.2.0", 1) {
		t.Fatal("0.2.0 should be too old under override min 0.3.0")
	}
}
