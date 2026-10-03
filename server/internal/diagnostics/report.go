// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

// Package diagnostics — Mattermost-style support/diagnostics packet (lite).
//
// Security policy (non-negotiable):
//   - Never include credentials, JWT secrets, FCM service-account JSON, push tokens,
//     message bodies, ciphertext, or user PII.
//   - Push subsystem reports only adapter mode + privacy posture (opaque wake).
package diagnostics

import (
	"context"
	"os"
	"time"
)

// Report — safe-to-export ops snapshot (Mattermost support packet analogue).
type Report struct {
	Status    string               `json:"status"` // ok | degraded
	Time      string               `json:"time"`
	NodeID    string               `json:"node_id"`
	Version   string               `json:"version,omitempty"`
	Subsystems map[string]Subsystem `json:"subsystems"`
	Security  SecurityPosture      `json:"security"`
}

// Subsystem — one dependency health cell.
type Subsystem struct {
	Status  string            `json:"status"` // ok | disabled | degraded | error
	Detail  string            `json:"detail,omitempty"`
	Extra   map[string]string `json:"extra,omitempty"`
}

// SecurityPosture — fixed product policy (not runtime secrets).
type SecurityPosture struct {
	PushPayloadPolicy string `json:"push_payload_policy"`
	E2EEMailbox       bool   `json:"e2ee_mailbox"`
	PushPreviewInOSPNS bool  `json:"push_preview_in_ospns"` // always false for Glagolitsa
	AuthDeviceSeparation bool `json:"auth_device_separation"`
}

// Pinger — optional DB ping (Postgres).
type Pinger interface {
	Ping(ctx context.Context) error
}

// PushReporter — adapter modes without secrets (notification.Service).
type PushReporter interface {
	PushDiagnostics() PushInfo
}

// PushInfo — which push backends are wired (log vs live).
type PushInfo struct {
	Android     string `json:"android"`     // fcm | log
	IOS         string `json:"ios"`         // apns | log
	Web         string `json:"web"`         // log | webpush
	UnifiedPush string `json:"unifiedpush"` // simple_push | log
	Privacy     string `json:"privacy"`     // opaque_wake_only
}

// RTCReporter — privacy-safe RTC edge status (calling package).
type RTCReporter interface {
	RTCDiagnostics() RTCInfo
}

// RTCInfo — SFU/TURN readiness without credentials or peer IPs.
type RTCInfo struct {
	Status           string `json:"status"` // ok | degraded | disabled
	Detail           string `json:"detail,omitempty"`
	RegionID         string `json:"region_id,omitempty"`
	LiveKitReachable bool   `json:"livekit_reachable"`
	TURNConfigured   bool   `json:"turn_configured"`
	Available        bool   `json:"available"`
}

// BuildOptions — inputs for a report (no secrets).
type BuildOptions struct {
	Store        Pinger
	Push         PushReporter
	RTC          RTCReporter
	RedisURLSet  bool
	ClusterOn    bool
	MetricsOn    bool
	NodeID       string
	Version      string
}

// Build constructs a privacy-safe diagnostics report.
func Build(ctx context.Context, opts BuildOptions) Report {
	now := time.Now().UTC().Format(time.RFC3339)
	nodeID := opts.NodeID
	if nodeID == "" {
		nodeID = os.Getenv("NODE_ID")
	}
	if nodeID == "" {
		nodeID = "local"
	}

	subs := map[string]Subsystem{}

	// Database
	db := Subsystem{Status: "ok", Detail: "reachable"}
	if opts.Store != nil {
		if err := opts.Store.Ping(ctx); err != nil {
			db = Subsystem{Status: "error", Detail: "ping failed"}
		}
	} else {
		db = Subsystem{Status: "disabled", Detail: "no pinger"}
	}
	subs["database"] = db

	// Redis / cluster (Mattermost HA signal)
	if opts.RedisURLSet {
		status := "ok"
		detail := "configured"
		if opts.ClusterOn {
			detail = "configured; cluster fan-out on"
		}
		subs["redis"] = Subsystem{Status: status, Detail: detail}
	} else {
		subs["redis"] = Subsystem{Status: "disabled", Detail: "single-node mode"}
	}

	// Push (Mattermost push-proxy readiness analogue — no credentials)
	pushExtra := map[string]string{}
	pushStatus := "ok"
	if opts.Push != nil {
		info := opts.Push.PushDiagnostics()
		pushExtra["android"] = info.Android
		pushExtra["ios"] = info.IOS
		pushExtra["web"] = info.Web
		pushExtra["unifiedpush"] = info.UnifiedPush
		pushExtra["privacy"] = info.Privacy
		if info.Android == "log" && info.IOS == "log" {
			pushStatus = "degraded"
		}
	} else {
		pushStatus = "disabled"
	}
	subs["push"] = Subsystem{
		Status: pushStatus,
		Detail: "opaque wake only; no message content in OSPNS",
		Extra:  pushExtra,
	}

	// Metrics
	if opts.MetricsOn {
		subs["metrics"] = Subsystem{Status: "ok", Detail: "prometheus enabled"}
	} else {
		subs["metrics"] = Subsystem{Status: "disabled"}
	}

	// Jobs — always scheduled in-process; DB claim is SKIP LOCKED (MM-like)
	subs["jobs"] = Subsystem{
		Status: "ok",
		Detail: "scheduler+watcher; claim uses SKIP LOCKED when postgres",
	}

	// RTC edge (LiveKit/TURN) — status only; never tokens/credentials
	if opts.RTC != nil {
		info := opts.RTC.RTCDiagnostics()
		extra := map[string]string{
			"region_id": info.RegionID,
		}
		if info.LiveKitReachable {
			extra["livekit"] = "reachable"
		} else {
			extra["livekit"] = "unreachable"
		}
		if info.TURNConfigured {
			extra["turn"] = "configured"
		} else {
			extra["turn"] = "not_configured"
		}
		if info.Available {
			extra["kill_switch"] = "open"
		} else {
			extra["kill_switch"] = "closed"
		}
		subs["rtc"] = Subsystem{
			Status: info.Status,
			Detail: info.Detail,
			Extra:  extra,
		}
	} else {
		subs["rtc"] = Subsystem{Status: "disabled", Detail: "no rtc reporter"}
	}

	// Overall stays "ok" unless a critical subsystem errors. RTC "degraded"
	// is visible under subsystems.rtc for ops but must not take down chat API.
	overall := "ok"
	for name, s := range subs {
		if s.Status == "error" && name != "rtc" {
			overall = "degraded"
			break
		}
	}

	return Report{
		Status:     overall,
		Time:       now,
		NodeID:     nodeID,
		Version:    opts.Version,
		Subsystems: subs,
		Security: SecurityPosture{
			PushPayloadPolicy:    "opaque_wake_only",
			E2EEMailbox:          true,
			PushPreviewInOSPNS:   false, // never MM-style server preview
			AuthDeviceSeparation: true,
		},
	}
}
