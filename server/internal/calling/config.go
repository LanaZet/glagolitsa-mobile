// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is RTC control-plane configuration. Media never flows through Go API;
// this only describes LiveKit/TURN endpoints and feature kill switches.
type Config struct {
	// Region is the logical RTC region id (e.g. primary, ru-msk). Used for
	// metrics and future multi-region routing; not user location.
	RegionID string

	LiveKitURL    string
	LiveKitAPIKey string
	LiveKitSecret string
	TokenTTL      time.Duration

	STUNServers []string
	// TURNURL is a full turn: or turns: URL. Prefer TURNDomain + short-lived
	// credentials (stage 4); static username/password remain for local dev only.
	TURNURL      string
	TURNDomain   string
	TURNUsername string
	TURNPassword string
	// TURNSharedSecret is used for short-lived TURN credentials (HMAC). Never
	// log or return this value to clients.
	TURNSharedSecret string
	ICETTL           time.Duration

	// MediaE2EE controls client-side frame encryption. Default is true; only an
	// explicit operator kill switch may disable it for incident response/dev.
	MediaE2EE bool
	Features  FeatureSet
}

func ConfigFromEnv() Config {
	ttl := 15 * time.Minute
	if raw := os.Getenv("LIVEKIT_TOKEN_TTL_SEC"); raw != "" {
		if sec, err := strconv.Atoi(raw); err == nil && sec > 0 {
			ttl = time.Duration(sec) * time.Second
		}
	}
	// Prefer short-lived ICE credentials (1h default). Override with ICE_TTL_SEC.
	iceTTL := DefaultShortLivedICETTL
	if raw := os.Getenv("ICE_TTL_SEC"); raw != "" {
		if sec, err := strconv.Atoi(raw); err == nil && sec > 0 {
			iceTTL = time.Duration(sec) * time.Second
		}
	}

	// Prefer private STUN when configured. Public Google STUN is a local-dev
	// fallback only — production should set STUN_SERVERS explicitly.
	stun := []string{"stun:stun.l.google.com:19302"}
	if raw := os.Getenv("STUN_SERVERS"); raw != "" {
		parts := strings.Split(raw, ",")
		stun = make([]string, 0, len(parts))
		for _, part := range parts {
			if trimmed := strings.TrimSpace(part); trimmed != "" {
				stun = append(stun, trimmed)
			}
		}
	}

	apiKey := strings.TrimSpace(os.Getenv("LIVEKIT_API_KEY"))
	apiSecret := strings.TrimSpace(os.Getenv("LIVEKIT_API_SECRET"))
	livekitConfigured := apiKey != "" && apiSecret != ""

	// Fail closed: RTC is available only when LiveKit credentials exist and the
	// operator has not flipped the kill switch off. CALLS_RTC_AVAILABLE=false
	// hides new calls without rolling back DB schema.
	rtcAvailable := envBool("CALLS_RTC_AVAILABLE", livekitConfigured)

	features := FeatureSet{
		Audio:              envBool("CLIENT_FEATURE_CALLS_AUDIO", true),
		Video:              envBool("CLIENT_FEATURE_CALLS_VIDEO", true),
		RTCLiveKit:         envBool("CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED", livekitConfigured),
		AudioOnly:          envBool("CLIENT_FEATURE_CALLS_AUDIO_ONLY_ENABLED", true),
		VideoAdaptive:      envBool("CLIENT_FEATURE_CALLS_VIDEO_ADAPTIVE_ENABLED", false),
		GroupReadyAPI:      envBool("CLIENT_FEATURE_CALLS_GROUP_READY_API_ENABLED", false),
		RouteHints:         envBool("CLIENT_FEATURE_CALLS_ROUTE_HINTS_ENABLED", false),
		UnknownRequestGate: envBool("CLIENT_FEATURE_CALLS_UNKNOWN_REQUEST_GATE_ENABLED", true),
		RTCAvailable:       rtcAvailable,
	}

	return Config{
		RegionID:         envOr("RTC_REGION_ID", "primary"),
		LiveKitURL:       envOr("LIVEKIT_URL", "ws://localhost:7880"),
		LiveKitAPIKey:    apiKey,
		LiveKitSecret:    apiSecret,
		TokenTTL:         ttl,
		STUNServers:      stun,
		TURNURL:          strings.TrimSpace(os.Getenv("TURN_URL")),
		TURNDomain:       sanitizeTURNDomain(os.Getenv("TURN_DOMAIN")),
		TURNUsername:     os.Getenv("TURN_USERNAME"),
		TURNPassword:     os.Getenv("TURN_PASSWORD"),
		TURNSharedSecret: strings.TrimSpace(os.Getenv("TURN_SHARED_SECRET")),
		ICETTL:           iceTTL,
		MediaE2EE:        envBool("CALLS_MEDIA_E2EE_ENABLED", true),
		Features:         features,
	}
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
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
