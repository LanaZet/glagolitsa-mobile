// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"context"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"glagolitsa/server/internal/calling"
	"glagolitsa/server/internal/clientupdate"
	"glagolitsa/server/internal/diagnostics"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/store"
)

// rtcDiagnosticsAdapter bridges calling.Handler into diagnostics.RTCReporter
// without importing diagnostics into the calling package.
type rtcDiagnosticsAdapter struct {
	h *calling.Handler
}

func (a rtcDiagnosticsAdapter) RTCDiagnostics() diagnostics.RTCInfo {
	if a.h == nil {
		return diagnostics.RTCInfo{Status: "disabled", Detail: "calling handler missing"}
	}
	status, detail, region, reachable, turn, available := a.h.RTCDiagnostics()
	return diagnostics.RTCInfo{
		Status:           status,
		Detail:           detail,
		RegionID:         region,
		LiveKitReachable: reachable,
		TURNConfigured:   turn,
		Available:        available,
	}
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	// Keep /api/health tiny for load balancers; full probe is /api/diagnostics.
	httpx.WriteJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
		"time":   store.NowUTC().Format("2006-01-02T15:04:05Z07:00"),
	})
}

// diagnostics — Mattermost support-packet lite.
// Public, no auth: only subsystem status + fixed security posture (no secrets/PII).
func (h *Handler) diagnostics(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	var pinger diagnostics.Pinger
	if p, ok := h.store.(diagnostics.Pinger); ok {
		pinger = p
	}

	report := diagnostics.Build(ctx, diagnostics.BuildOptions{
		Store:       pinger,
		Push:        h.notificationSvc,
		RTC:         rtcDiagnosticsAdapter{h.calling},
		RedisURLSet: h.redisConfigured,
		ClusterOn:   h.cluster != nil,
		MetricsOn:   h.metricsEnabled,
		NodeID:      os.Getenv("NODE_ID"),
	})
	status := http.StatusOK
	if report.Status == "degraded" {
		status = http.StatusServiceUnavailable
	}
	httpx.WriteJSON(w, status, report)
}

func (h *Handler) hello(w http.ResponseWriter, r *http.Request) {
	serverID, err := h.store.GetServerID()
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "server identity unavailable")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{
		"message":   "Привет от Glagolitsa!",
		"server_id": serverID,
	})
}

// clientUpdatePolicy — public (no auth) Mattermost-style min/latest + install URLs.
func (h *Handler) clientUpdatePolicy(w http.ResponseWriter, r *http.Request) {
	httpx.WriteJSON(w, http.StatusOK, h.clientUpdate)
}

// rejectIfClientTooOld blocks authenticated API when client sends version headers below min.
// Missing headers are allowed (backward compatible with older clients / tests).
func (h *Handler) rejectIfClientTooOld(w http.ResponseWriter, r *http.Request) bool {
	version := strings.TrimSpace(r.Header.Get("X-App-Version"))
	platform := strings.TrimSpace(r.Header.Get("X-App-Platform"))
	buildRaw := strings.TrimSpace(r.Header.Get("X-App-Build"))
	if version == "" && buildRaw == "" {
		return false
	}
	build := 0
	if buildRaw != "" {
		if n, err := strconv.Atoi(buildRaw); err == nil {
			build = n
		}
	}
	if platform == "" {
		platform = "android"
	}
	if !clientupdate.IsClientTooOld(h.clientUpdate, platform, version, build) {
		return false
	}
	httpx.WriteJSON(w, http.StatusUpgradeRequired, map[string]any{
		"error":  "client_update_required",
		"code":   "client_update_required",
		"policy": h.clientUpdate,
	})
	return true
}
