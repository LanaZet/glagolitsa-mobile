// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package jobs

import "time"

// Status values mirror Mattermost model.Job.
const (
	StatusPending    = "pending"
	StatusInProgress = "in_progress"
	StatusSuccess    = "success"
	StatusError      = "error"
	StatusCanceled   = "canceled"
)

// Job types (register workers by these names).
const (
	TypePurgeRelayQueue           = "purge_relay_queue"
	TypePurgeStaleDevices         = "purge_stale_devices"
	TypeNotificationRetry         = "notification_retry"
	TypeNotificationLogRetention  = "notification_log_retention"
	TypeSyncOfflineRetry          = "sync_offline_retry"
	TypeMediaRetention            = "media_retention"
	TypeCallTimeoutSweep          = "call_timeout_sweep"
)

type Job struct {
	ID             string
	Type           string
	Status         string
	Progress       int64
	Data           map[string]any
	LastError      string
	CreateAt       time.Time
	StartAt        *time.Time
	LastActivityAt time.Time
}