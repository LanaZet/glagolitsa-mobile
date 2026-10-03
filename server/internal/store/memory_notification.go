// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/notification"
)

type memPushToken struct {
	record notification.PushToken
}

type memRetryJob struct {
	job notification.RetryJob
}

func (s *MemoryStore) UpsertPushToken(userID, deviceID, platform, token string) (notification.PushToken, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pushTokens == nil {
		s.pushTokens = map[string]*memPushToken{}
	}
	key := userID + ":" + deviceID + ":" + platform
	now := NowUTC()
	if existing, ok := s.pushTokens[key]; ok {
		existing.record.Token = token
		existing.record.TokenStatus = notification.TokenStatusActive
		existing.record.RevokedAt = nil
		existing.record.UpdatedAt = now
		return existing.record, nil
	}
	record := notification.PushToken{
		ID:          uuid.NewString(),
		UserID:      userID,
		DeviceID:    deviceID,
		Platform:    platform,
		Token:       token,
		TokenStatus: notification.TokenStatusActive,
		CreatedAt:   now,
		UpdatedAt:   now,
	}
	s.pushTokens[key] = &memPushToken{record: record}
	return record, nil
}

func (s *MemoryStore) RevokePushToken(userID, tokenID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, item := range s.pushTokens {
		if item.record.ID == tokenID && item.record.UserID == userID {
			now := NowUTC()
			item.record.TokenStatus = notification.TokenStatusRevoked
			item.record.RevokedAt = &now
			item.record.UpdatedAt = now
			return nil
		}
	}
	return ErrNotFound
}

func (s *MemoryStore) RevokePushTokensForDevice(userID, deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := NowUTC()
	for _, item := range s.pushTokens {
		if item.record.UserID == userID && item.record.DeviceID == deviceID && item.record.RevokedAt == nil {
			item.record.TokenStatus = notification.TokenStatusRevoked
			item.record.RevokedAt = &now
			item.record.UpdatedAt = now
		}
	}
	return nil
}

func (s *MemoryStore) ListActivePushTokens(userID string) ([]notification.PushToken, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]notification.PushToken, 0)
	for _, item := range s.pushTokens {
		if item.record.UserID == userID && item.record.TokenStatus == notification.TokenStatusActive && item.record.RevokedAt == nil {
			out = append(out, item.record)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out, nil
}

func (s *MemoryStore) MarkTokenSuccess(tokenID string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, item := range s.pushTokens {
		if item.record.ID == tokenID {
			item.record.LastSuccessAt = &at
			item.record.FailureCount = 0
			item.record.UpdatedAt = at
			return nil
		}
	}
	return nil
}

func (s *MemoryStore) MarkTokenFailure(tokenID string, at time.Time, invalidate bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, item := range s.pushTokens {
		if item.record.ID == tokenID {
			item.record.LastFailureAt = &at
			item.record.FailureCount++
			if invalidate {
				item.record.TokenStatus = notification.TokenStatusInvalid
			}
			item.record.UpdatedAt = at
			return nil
		}
	}
	return nil
}

func (s *MemoryStore) GetPreferences(userID string) (notification.Preferences, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.notificationPrefs == nil {
		s.notificationPrefs = map[string]notification.Preferences{}
	}
	if prefs, ok := s.notificationPrefs[userID]; ok {
		return prefs, nil
	}
	prefs := notification.DefaultPreferences(userID)
	s.notificationPrefs[userID] = prefs
	return prefs, nil
}

func (s *MemoryStore) UpsertPreferences(userID string, update func(*notification.Preferences) error) (notification.Preferences, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.notificationPrefs == nil {
		s.notificationPrefs = map[string]notification.Preferences{}
	}
	prefs, ok := s.notificationPrefs[userID]
	if !ok {
		prefs = notification.DefaultPreferences(userID)
	}
	if err := update(&prefs); err != nil {
		return prefs, err
	}
	prefs.UpdatedAt = NowUTC()
	s.notificationPrefs[userID] = prefs
	return prefs, nil
}

func (s *MemoryStore) AppendDeliveryLog(userID, tokenID, notificationType, platform, status, errMsg string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.notificationDelivery == nil {
		s.notificationDelivery = []memNotificationDelivery{}
	}
	s.notificationDelivery = append(s.notificationDelivery, memNotificationDelivery{
		userID: userID, tokenID: tokenID, notificationType: notificationType,
		platform: platform, status: status, errMsg: errMsg, createdAt: NowUTC(),
	})
	return nil
}

func (s *MemoryStore) PurgeDeliveryLogsBefore(before time.Time) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.notificationDelivery == nil {
		return 0, nil
	}
	kept := s.notificationDelivery[:0]
	var purged int64
	for _, row := range s.notificationDelivery {
		if row.createdAt.Before(before) {
			purged++
			continue
		}
		kept = append(kept, row)
	}
	s.notificationDelivery = kept
	return purged, nil
}

func (s *MemoryStore) EnqueueRetry(job notification.RetryJob) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.notificationRetries == nil {
		s.notificationRetries = map[string]*memRetryJob{}
	}
	if job.ID == "" {
		job.ID = uuid.NewString()
	}
	s.notificationRetries[job.ID] = &memRetryJob{job: job}
	return job.ID, nil
}

func (s *MemoryStore) ListDueRetries(limit int, now time.Time) ([]notification.RetryJob, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]notification.RetryJob, 0)
	for _, item := range s.notificationRetries {
		job := item.job
		if job.Attempts < job.MaxAttempts && !job.NextAttemptAt.After(now) {
			out = append(out, job)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].NextAttemptAt.Before(out[j].NextAttemptAt) })
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (s *MemoryStore) UpdateRetryAttempt(id string, attempts int, nextAttemptAt time.Time, lastError string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	item, ok := s.notificationRetries[id]
	if !ok {
		return errors.New("retry job not found")
	}
	item.job.Attempts = attempts
	item.job.NextAttemptAt = nextAttemptAt
	item.job.LastError = lastError
	return nil
}

func (s *MemoryStore) CompleteRetry(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.notificationRetries, id)
	return nil
}

type memNotificationDelivery struct {
	userID, tokenID, notificationType, platform, status, errMsg string
	createdAt                                                   time.Time
}
