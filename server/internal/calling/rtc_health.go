// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// RTCHealth is a privacy-safe snapshot of RTC edge readiness for diagnostics.
// It must never include tokens, TURN credentials, media keys, or peer IPs.
type RTCHealth struct {
	RegionID         string `json:"region_id"`
	Configured       bool   `json:"configured"`
	Available        bool   `json:"available"`
	LiveKitEnabled   bool   `json:"livekit_enabled"`
	LiveKitReachable bool   `json:"livekit_reachable"`
	TURNConfigured   bool   `json:"turn_configured"`
	Status           string `json:"status"` // ok | degraded | disabled
	Detail           string `json:"detail,omitempty"`
}

// HealthHTTPClient is a tiny interface for tests.
type HealthHTTPClient interface {
	Do(req *http.Request) (*http.Response, error)
}

// ProbeRTCHealth checks whether LiveKit HTTP health answers. TURN allocate is
// validated by ops smoke scripts (UDP/TLS); here we only report whether TURN
// endpoints are configured so diagnostics can distinguish SFU vs TURN setup.
func ProbeRTCHealth(ctx context.Context, cfg Config, client HealthHTTPClient) RTCHealth {
	h := RTCHealth{
		RegionID:       cfg.RegionID,
		Configured:     cfg.LiveKitAPIKey != "" && cfg.LiveKitSecret != "",
		Available:      cfg.Features.RTCAvailable,
		LiveKitEnabled: cfg.Features.RTCLiveKit,
		TURNConfigured: cfg.TURNDomain != "" || cfg.TURNURL != "" || cfg.TURNSharedSecret != "",
	}

	if !h.Configured {
		h.Status = "disabled"
		h.Detail = "livekit credentials not configured"
		return h
	}

	if client == nil {
		client = &http.Client{Timeout: 2 * time.Second}
	}

	probeURL := liveKitHTTPHealthURL(cfg.LiveKitURL)
	if probeURL == "" {
		h.Status = "degraded"
		h.Detail = "livekit url invalid"
		return h
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
	if err != nil {
		h.Status = "degraded"
		h.Detail = "health request build failed"
		return h
	}
	resp, err := client.Do(req)
	if err != nil {
		h.Status = "degraded"
		h.Detail = "livekit http unreachable"
		return h
	}
	defer resp.Body.Close()
	// Any HTTP response from the SFU process counts as reachable; do not
	// forward body (may vary by version).
	h.LiveKitReachable = resp.StatusCode > 0 && resp.StatusCode < 500

	switch {
	case !h.Available:
		h.Status = "degraded"
		h.Detail = "rtc kill switch active"
	case !h.LiveKitReachable:
		h.Status = "degraded"
		h.Detail = "livekit http unhealthy"
	case !h.TURNConfigured:
		h.Status = "degraded"
		h.Detail = "livekit up; turn not configured"
	default:
		h.Status = "ok"
		h.Detail = "livekit reachable; turn configured"
	}
	return h
}

// liveKitHTTPHealthURL maps ws(s)://host[:port]/path to http(s)://host[:port]/
// for process health. Never returns credentials.
func liveKitHTTPHealthURL(liveKitURL string) string {
	raw := strings.TrimSpace(liveKitURL)
	if raw == "" {
		return ""
	}
	// url.Parse requires a scheme clients understand.
	switch {
	case strings.HasPrefix(raw, "ws://"):
		raw = "http://" + strings.TrimPrefix(raw, "ws://")
	case strings.HasPrefix(raw, "wss://"):
		raw = "https://" + strings.TrimPrefix(raw, "wss://")
	case strings.HasPrefix(raw, "http://"), strings.HasPrefix(raw, "https://"):
		// already ok
	default:
		raw = "http://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	// Strip userinfo if someone mistakenly put secrets in the URL.
	u.User = nil
	u.Path = ""
	u.RawQuery = ""
	u.Fragment = ""
	// Default LiveKit HTTP port when only WSS reverse-proxy URL is configured
	// without an explicit health base — ops should set LIVEKIT_HEALTH_URL later
	// if needed. For wss://host we probe https://host/ which Caddy can map.
	return u.String()
}

// MetricLabels returns coarse, non-PII labels for RTC metrics exporters.
func MetricLabels(cfg Config) map[string]string {
	turnRegion := cfg.RegionID
	if cfg.TURNDomain != "" {
		// Domain only, never credentials.
		turnRegion = cfg.RegionID
	}
	return map[string]string{
		"rtc_region":  cfg.RegionID,
		"turn_region": turnRegion,
		"route_class": "single_region",
	}
}
