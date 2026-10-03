// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import "time"

const (
	SyncQueuePending    = "pending"
	SyncQueueProcessing = "processing"
	SyncQueueApplied    = "applied"
	SyncQueueFailed     = "failed"

	SyncProfileAggressive = "aggressive"
	SyncProfileBalanced   = "balanced"
	SyncProfileSaver      = "saver"
	SyncProfileMinimal    = "minimal"
)

type SyncEventRecord struct {
	EventID     int64
	ScopeUserID string
	ChatID      string
	MessageID   string
	Operation   string
	Version     int
	Ciphertext  []byte
	Metadata    map[string]any
	ActorID     string
	CreatedAt   time.Time
}

type SyncDeviceRecord struct {
	ID               string
	UserID           string
	DeviceID         string
	Label            string
	LastAckedEventID int64
	SnapshotEventID  *int64
	SyncProfile      string
	RegisteredAt     time.Time
	LastSeenAt       time.Time
}

type SyncSnapshotRecord struct {
	ID           string
	UserID       string
	EventID      int64
	SnapshotData []byte
	CreatedAt    time.Time
}

type SyncOfflineQueueRecord struct {
	ID            string
	UserID        string
	DeviceID      string
	ClientEventID string
	Operation     string
	ChatID        string
	MessageID     string
	Version       int
	Ciphertext    []byte
	Metadata      map[string]any
	Status        string
	Attempts      int
	NextRetryAt   time.Time
	LastError     string
	CreatedAt     time.Time
}

type AppendSyncEventInput struct {
	ScopeUserID string
	ChatID      string
	MessageID   string
	Operation   string
	Version     int
	Ciphertext  []byte
	Metadata    map[string]any
	ActorID     string
}

type RegisterSyncDeviceInput struct {
	DeviceID    string
	Label       string
	SyncProfile string
}

type EnqueueSyncEventInput struct {
	DeviceID      string
	ClientEventID string
	Operation     string
	ChatID        string
	MessageID     string
	Version       int
	Ciphertext    []byte
	Metadata      map[string]any
}
