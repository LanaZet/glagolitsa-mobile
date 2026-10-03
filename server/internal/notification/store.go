// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "time"

// Store — push tokens, preferences, delivery log, retry queue.
type Store interface {
	UpsertPushToken(userID, deviceID, platform, token string) (PushToken, error)
	RevokePushToken(userID, tokenID string) error
	RevokePushTokensForDevice(userID, deviceID string) error
	ListActivePushTokens(userID string) ([]PushToken, error)
	MarkTokenSuccess(tokenID string, at time.Time) error
	MarkTokenFailure(tokenID string, at time.Time, invalidate bool) error

	GetPreferences(userID string) (Preferences, error)
	UpsertPreferences(userID string, update func(*Preferences) error) (Preferences, error)

	AppendDeliveryLog(userID, tokenID, notificationType, platform, status, errMsg string) error
	PurgeDeliveryLogsBefore(before time.Time) (int64, error)

	EnqueueRetry(job RetryJob) (string, error)
	ListDueRetries(limit int, now time.Time) ([]RetryJob, error)
	UpdateRetryAttempt(id string, attempts int, nextAttemptAt time.Time, lastError string) error
	CompleteRetry(id string) error
}
