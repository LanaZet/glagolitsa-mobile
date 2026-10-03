// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type EventResponse struct {
	EventID     int64          `json:"event_id"`
	ChatID      string         `json:"chat_id,omitempty"`
	MessageID   string         `json:"message_id,omitempty"`
	Operation   string         `json:"operation"`
	Version     int            `json:"version"`
	Ciphertext  string         `json:"ciphertext,omitempty"`
	Metadata    map[string]any `json:"metadata,omitempty"`
	ActorID     string         `json:"actor_id,omitempty"`
	CreatedAt   time.Time      `json:"created_at"`
}

type EventsResponse struct {
	Events     []EventResponse `json:"events"`
	HasMore    bool            `json:"has_more"`
	LatestID   int64           `json:"latest_event_id"`
	ServerTime time.Time       `json:"server_time"`
}

type DeltaResponse = EventsResponse

type LegacySyncResponse struct {
	ServerTime time.Time         `json:"server_time"`
	Chats      []model.Chat      `json:"chats"`
	Events     []model.ChatEvent `json:"events"`
	HasMore    bool              `json:"has_more"`
	LatestID   int64             `json:"latest_event_id,omitempty"`
}

type RegisterDeviceRequest struct {
	DeviceID    string `json:"device_id"`
	Label       string `json:"label,omitempty"`
	SyncProfile string `json:"sync_profile,omitempty"`
	NetworkMbps *float64 `json:"network_mbps,omitempty"`
	BatteryPct  *int     `json:"battery_pct,omitempty"`
	PowerSaver  *bool    `json:"power_saver,omitempty"`
}

type DeviceResponse struct {
	DeviceID           string `json:"device_id"`
	Label              string `json:"label,omitempty"`
	LastAckedEventID   int64  `json:"last_acked_event_id"`
	SnapshotEventID    *int64 `json:"snapshot_event_id,omitempty"`
	SyncProfile        string `json:"sync_profile"`
	RecommendedProfile string `json:"recommended_profile"`
}

type AckRequest struct {
	DeviceID    string `json:"device_id"`
	LastEventID int64  `json:"last_event_id"`
}

type SnapshotResponse struct {
	EventID      int64     `json:"event_id"`
	SnapshotData string    `json:"snapshot_data"`
	CreatedAt    time.Time `json:"created_at"`
}

type PushEventRequest struct {
	DeviceID      string         `json:"device_id"`
	ClientEventID string         `json:"client_event_id"`
	Operation     string         `json:"operation"`
	ChatID        string         `json:"chat_id,omitempty"`
	MessageID     string         `json:"message_id,omitempty"`
	Version       int            `json:"version"`
	Ciphertext    string         `json:"ciphertext,omitempty"`
	Metadata      map[string]any `json:"metadata,omitempty"`
}

type PushEventResponse struct {
	Queued          bool  `json:"queued"`
	EventID         int64 `json:"event_id,omitempty"`
	AcceptedVersion int   `json:"accepted_version,omitempty"`
	Rejected        bool  `json:"rejected,omitempty"`
	Reason          string `json:"reason,omitempty"`
}

func eventFromRecord(record store.SyncEventRecord) EventResponse {
	resp := EventResponse{
		EventID: record.EventID, ChatID: record.ChatID, MessageID: record.MessageID,
		Operation: record.Operation, Version: record.Version, Metadata: record.Metadata,
		ActorID: record.ActorID, CreatedAt: record.CreatedAt,
	}
	if len(record.Ciphertext) > 0 {
		resp.Ciphertext = encodeB64(record.Ciphertext)
	}
	return resp
}