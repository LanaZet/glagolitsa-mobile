// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package clientupdate

import (
	"os"
	"strconv"
	"strings"

	"glagolitsa/server/internal/calling"
)

// Policy is the Mattermost-style client update config served to apps.
// Installable updates: store_url (Play/App Store) and/or download_url (APK/DMG).
type Policy struct {
	Android    PlatformPolicy    `json:"android"`
	IOS        PlatformPolicy    `json:"ios"`
	Desktop    PlatformPolicy    `json:"desktop"`
	KillSwitch bool              `json:"kill_switch"`
	SoftTitle  string            `json:"soft_title"`
	SoftBody   string            `json:"soft_body"`
	HardTitle  string            `json:"hard_title"`
	HardBody   string            `json:"hard_body"`
	Features   map[string]bool   `json:"features"`
}

type PlatformPolicy struct {
	MinVersion     string `json:"min_version"`
	LatestVersion  string `json:"latest_version"`
	MinBuild       int    `json:"min_build"`
	LatestBuild    int    `json:"latest_build"`
	StoreURL       string `json:"store_url"`
	DownloadURL    string `json:"download_url"`
}

// FromEnv loads policy. Empty min/latest = no gate for that field.
//
//	CLIENT_UPDATE_KILL_SWITCH=true
//	CLIENT_ANDROID_MIN_VERSION=0.1.0
//	CLIENT_ANDROID_LATEST_VERSION=0.2.0
//	CLIENT_ANDROID_MIN_BUILD=1
//	CLIENT_ANDROID_LATEST_BUILD=20
//	CLIENT_ANDROID_STORE_URL=https://play.google.com/store/apps/details?id=com.glagolitsa.mobile
//	CLIENT_ANDROID_DOWNLOAD_URL=https://cdn.example/glagolitsa.apk
//	CLIENT_FEATURE_CHANNELS_PUBLIC_DISCOVER=true
func FromEnv() Policy {
	p := Policy{
		Android: PlatformPolicy{
			MinVersion:    env("CLIENT_ANDROID_MIN_VERSION", "0.1.0"),
			LatestVersion: env("CLIENT_ANDROID_LATEST_VERSION", "0.1.0"),
			MinBuild:      envInt("CLIENT_ANDROID_MIN_BUILD", 1),
			LatestBuild:   envInt("CLIENT_ANDROID_LATEST_BUILD", 1),
			StoreURL:      env("CLIENT_ANDROID_STORE_URL", "https://play.google.com/store/apps/details?id=com.glagolitsa.mobile"),
			DownloadURL:   env("CLIENT_ANDROID_DOWNLOAD_URL", ""),
		},
		IOS: PlatformPolicy{
			MinVersion:    env("CLIENT_IOS_MIN_VERSION", "0.1.0"),
			LatestVersion: env("CLIENT_IOS_LATEST_VERSION", "0.1.0"),
			MinBuild:      envInt("CLIENT_IOS_MIN_BUILD", 1),
			LatestBuild:   envInt("CLIENT_IOS_LATEST_BUILD", 1),
			StoreURL:      env("CLIENT_IOS_STORE_URL", ""),
			DownloadURL:   env("CLIENT_IOS_DOWNLOAD_URL", ""),
		},
		Desktop: PlatformPolicy{
			MinVersion:    env("CLIENT_DESKTOP_MIN_VERSION", "0.1.0"),
			LatestVersion: env("CLIENT_DESKTOP_LATEST_VERSION", "0.1.0"),
			MinBuild:      envInt("CLIENT_DESKTOP_MIN_BUILD", 1),
			LatestBuild:   envInt("CLIENT_DESKTOP_LATEST_BUILD", 1),
			StoreURL:      env("CLIENT_DESKTOP_STORE_URL", ""),
			DownloadURL:   env("CLIENT_DESKTOP_DOWNLOAD_URL", ""),
		},
		KillSwitch: envBool("CLIENT_UPDATE_KILL_SWITCH", false),
		SoftTitle:  env("CLIENT_UPDATE_SOFT_TITLE", "Доступна новая версия"),
		SoftBody:   env("CLIENT_UPDATE_SOFT_BODY", "Обновите приложение, чтобы получить исправления и новые возможности."),
		HardTitle:  env("CLIENT_UPDATE_HARD_TITLE", "Требуется обновление"),
		HardBody:   env("CLIENT_UPDATE_HARD_BODY", "Эта версия больше не поддерживается. Установите новую из магазина или по ссылке."),
		Features: mergeFeatures(
			map[string]bool{
				"channels_public_discover": envBool("CLIENT_FEATURE_CHANNELS_PUBLIC_DISCOVER", true),
			},
			// Call flags + RTC kill switch come from calling.Config so server
			// enforcement and client policy cannot drift.
			calling.ConfigFromEnv().Features.ClientFeatures(),
		),
	}
	return p
}

func mergeFeatures(parts ...map[string]bool) map[string]bool {
	out := make(map[string]bool)
	for _, part := range parts {
		for k, v := range part {
			out[k] = v
		}
	}
	return out
}

// IsClientTooOld reports whether versionName/build is below min for platform.
func IsClientTooOld(p Policy, platform, versionName string, build int) bool {
	if p.KillSwitch {
		return true
	}
	pp := platformPolicy(p, platform)
	if pp.MinBuild > 0 && build > 0 && build < pp.MinBuild {
		return true
	}
	if pp.MinVersion != "" && versionName != "" && CompareSemver(versionName, pp.MinVersion) < 0 {
		return true
	}
	return false
}

func platformPolicy(p Policy, platform string) PlatformPolicy {
	switch strings.ToLower(strings.TrimSpace(platform)) {
	case "ios", "iphone", "ipad":
		return p.IOS
	case "desktop", "jvm", "macos", "windows", "linux":
		return p.Desktop
	default:
		return p.Android
	}
}

// CompareSemver returns -1 if a<b, 0 if equal, 1 if a>b.
func CompareSemver(a, b string) int {
	pa := parseSemver(a)
	pb := parseSemver(b)
	n := len(pa)
	if len(pb) > n {
		n = len(pb)
	}
	for i := 0; i < n; i++ {
		var av, bv int
		if i < len(pa) {
			av = pa[i]
		}
		if i < len(pb) {
			bv = pb[i]
		}
		if av < bv {
			return -1
		}
		if av > bv {
			return 1
		}
	}
	return 0
}

func parseSemver(raw string) []int {
	s := strings.TrimSpace(raw)
	s = strings.TrimPrefix(s, "v")
	if i := strings.IndexAny(s, "-+"); i >= 0 {
		s = s[:i]
	}
	if s == "" {
		return []int{0}
	}
	parts := strings.Split(s, ".")
	out := make([]int, 0, len(parts))
	for _, p := range parts {
		num := 0
		for _, ch := range p {
			if ch < '0' || ch > '9' {
				break
			}
			num = num*10 + int(ch-'0')
		}
		out = append(out, num)
	}
	return out
}

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func envBool(key string, def bool) bool {
	v := strings.TrimSpace(strings.ToLower(os.Getenv(key)))
	if v == "" {
		return def
	}
	switch v {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return def
	}
}
