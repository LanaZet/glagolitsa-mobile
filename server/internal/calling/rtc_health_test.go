// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) Do(req *http.Request) (*http.Response, error) { return f(req) }

func TestLiveKitHTTPHealthURL_stripsUserinfoAndConvertsScheme(t *testing.T) {
	got := liveKitHTTPHealthURL("wss://user:secret@rtc.example:443/rtc")
	if strings.Contains(got, "secret") || strings.Contains(got, "user") {
		t.Fatalf("must strip userinfo, got %q", got)
	}
	if !strings.HasPrefix(got, "https://rtc.example") {
		t.Fatalf("got %q", got)
	}
	uPath := got
	if i := strings.Index(uPath, "://"); i >= 0 {
		uPath = uPath[i+3:]
	}
	if i := strings.Index(uPath, "/"); i >= 0 {
		t.Fatalf("path should be cleared for health root, got %q", got)
	}
}

func TestProbeRTCHealth_disabledWithoutCredentials(t *testing.T) {
	h := ProbeRTCHealth(context.Background(), Config{RegionID: "primary"}, nil)
	if h.Status != "disabled" {
		t.Fatalf("status=%q", h.Status)
	}
	if h.LiveKitReachable {
		t.Fatal("unreachable expected")
	}
}

func TestProbeRTCHealth_okWhenLiveKitAndTURN(t *testing.T) {
	client := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if strings.Contains(req.URL.String(), "secret") {
			t.Fatal("request must not embed secrets")
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("ok")),
			Header:     make(http.Header),
		}, nil
	})
	cfg := Config{
		RegionID:      "ru-msk",
		LiveKitURL:    "wss://rtc-ru-msk.example",
		LiveKitAPIKey: "k",
		LiveKitSecret: "s",
		TURNDomain:    "turn-ru-msk.example",
		Features: FeatureSet{
			RTCAvailable: true,
			RTCLiveKit:   true,
		},
	}
	h := ProbeRTCHealth(context.Background(), cfg, client)
	if h.Status != "ok" {
		t.Fatalf("status=%q detail=%q", h.Status, h.Detail)
	}
	if !h.LiveKitReachable || !h.TURNConfigured {
		t.Fatalf("health=%+v", h)
	}
	// Response must never echo secrets from config into Detail.
	if strings.Contains(h.Detail, "s") && strings.Contains(h.Detail, "secret") {
		t.Fatalf("detail leaked secret material: %q", h.Detail)
	}
}

func TestProbeRTCHealth_degradedWhenSFUDown(t *testing.T) {
	client := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return nil, io.EOF
	})
	cfg := Config{
		LiveKitURL:    "http://127.0.0.1:9",
		LiveKitAPIKey: "k",
		LiveKitSecret: "s",
		TURNDomain:    "turn.example",
		Features:      FeatureSet{RTCAvailable: true, RTCLiveKit: true},
	}
	h := ProbeRTCHealth(context.Background(), cfg, client)
	if h.Status != "degraded" {
		t.Fatalf("status=%q", h.Status)
	}
	if h.LiveKitReachable {
		t.Fatal("expected unreachable")
	}
}

func TestMetricLabels_noPII(t *testing.T) {
	labels := MetricLabels(Config{RegionID: "primary", TURNDomain: "turn.example"})
	for k, v := range labels {
		if strings.Contains(v, "@") || strings.Contains(k, "user") {
			t.Fatalf("unexpected PII-ish label %s=%s", k, v)
		}
	}
	if labels["route_class"] != "single_region" {
		t.Fatalf("route_class=%q", labels["route_class"])
	}
}
