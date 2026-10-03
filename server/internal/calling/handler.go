// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"context"
	"time"

	"glagolitsa/server/internal/notification"
)

type Handler struct {
	Store    Store
	Rooms    *RoomManager
	Config   Config
	Features FeatureSet
	RegionID string
	Notifier notification.IncomingCallNotifier
	Presence PresenceHook
	Events   EventPublisher
}

func NewHandler(store Store, cfg Config, notifier notification.IncomingCallNotifier, presence PresenceHook) *Handler {
	if notifier == nil {
		notifier = notification.NoopIncomingCallNotifier{}
	}
	return &Handler{
		Store:    store,
		Rooms:    NewRoomManager(cfg),
		Config:   cfg,
		Features: cfg.Features,
		RegionID: cfg.RegionID,
		Notifier: notifier,
		Presence: presence,
	}
}

// SetEventPublisher wires WebSocket fanout for call control events.
func (h *Handler) SetEventPublisher(pub EventPublisher) {
	h.Events = pub
}

// RTCDiagnostics returns a privacy-safe RTC edge snapshot for /api/diagnostics.
// Never includes tokens, TURN credentials, media keys, or peer IPs.
func (h *Handler) RTCDiagnostics() (status, detail, regionID string, liveKitReachable, turnConfigured, available bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	health := ProbeRTCHealth(ctx, h.Config, nil)
	return health.Status, health.Detail, health.RegionID, health.LiveKitReachable, health.TURNConfigured, health.Available
}
