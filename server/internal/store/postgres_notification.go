// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/notification"
)

func (s *PostgresStore) UpsertPushToken(userID, deviceID, platform, token string) (notification.PushToken, error) {
	id := uuid.NewString()
	now := NowUTC()
	var record notification.PushToken
	err := s.pool.QueryRow(context.Background(), `
		INSERT INTO push_tokens (id, user_id, device_id, platform, token, token_status, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, 'active', $6, $6)
		ON CONFLICT (user_id, device_id, platform) DO UPDATE SET
			token = EXCLUDED.token,
			token_status = 'active',
			revoked_at = NULL,
			updated_at = EXCLUDED.updated_at
		RETURNING id, user_id, device_id, platform, token, token_status,
		          last_success_at, last_failure_at, failure_count, created_at, updated_at, revoked_at
	`, id, userID, deviceID, platform, token, now).Scan(
		&record.ID, &record.UserID, &record.DeviceID, &record.Platform, &record.Token, &record.TokenStatus,
		&record.LastSuccessAt, &record.LastFailureAt, &record.FailureCount, &record.CreatedAt, &record.UpdatedAt, &record.RevokedAt,
	)
	return record, err
}

func (s *PostgresStore) RevokePushToken(userID, tokenID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE push_tokens
		SET token_status = 'revoked', revoked_at = NOW(), updated_at = NOW()
		WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
	`, tokenID, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) RevokePushTokensForDevice(userID, deviceID string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE push_tokens
		SET token_status = 'revoked', revoked_at = NOW(), updated_at = NOW()
		WHERE user_id = $1 AND device_id = $2 AND revoked_at IS NULL
	`, userID, deviceID)
	return err
}

func (s *PostgresStore) ListActivePushTokens(userID string) ([]notification.PushToken, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, user_id, device_id, platform, token, token_status,
		       last_success_at, last_failure_at, failure_count, created_at, updated_at, revoked_at
		FROM push_tokens
		WHERE user_id = $1 AND token_status = 'active' AND revoked_at IS NULL
		ORDER BY updated_at DESC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]notification.PushToken, 0)
	for rows.Next() {
		var record notification.PushToken
		if err := rows.Scan(
			&record.ID, &record.UserID, &record.DeviceID, &record.Platform, &record.Token, &record.TokenStatus,
			&record.LastSuccessAt, &record.LastFailureAt, &record.FailureCount, &record.CreatedAt, &record.UpdatedAt, &record.RevokedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, record)
	}
	return out, rows.Err()
}

func (s *PostgresStore) MarkTokenSuccess(tokenID string, at time.Time) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE push_tokens
		SET last_success_at = $2, failure_count = 0, updated_at = $2
		WHERE id = $1
	`, tokenID, at)
	return err
}

func (s *PostgresStore) MarkTokenFailure(tokenID string, at time.Time, invalidate bool) error {
	status := "active"
	if invalidate {
		status = "invalid"
	}
	_, err := s.pool.Exec(context.Background(), `
		UPDATE push_tokens
		SET last_failure_at = $2,
		    failure_count = failure_count + 1,
		    token_status = $3,
		    updated_at = $2
		WHERE id = $1
	`, tokenID, at, status)
	return err
}

func (s *PostgresStore) GetPreferences(userID string) (notification.Preferences, error) {
	var prefs notification.Preferences
	err := s.pool.QueryRow(context.Background(), `
		SELECT user_id, messages_enabled, calls_enabled, new_device_enabled,
		       show_sender_name, show_message_preview, badge_enabled, updated_at
		FROM notification_preferences
		WHERE user_id = $1
	`, userID).Scan(
		&prefs.UserID, &prefs.MessagesEnabled, &prefs.CallsEnabled, &prefs.NewDeviceEnabled,
		&prefs.ShowSenderName, &prefs.ShowMessagePreview, &prefs.BadgeEnabled, &prefs.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		prefs = notification.DefaultPreferences(userID)
		_, insertErr := s.pool.Exec(context.Background(), `
			INSERT INTO notification_preferences (
				user_id, messages_enabled, calls_enabled, new_device_enabled,
				show_sender_name, show_message_preview, badge_enabled, updated_at
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
			ON CONFLICT (user_id) DO NOTHING
		`, prefs.UserID, prefs.MessagesEnabled, prefs.CallsEnabled, prefs.NewDeviceEnabled,
			prefs.ShowSenderName, prefs.ShowMessagePreview, prefs.BadgeEnabled, prefs.UpdatedAt)
		if insertErr != nil {
			return prefs, insertErr
		}
		return prefs, nil
	}
	return prefs, err
}

func (s *PostgresStore) UpsertPreferences(userID string, update func(*notification.Preferences) error) (notification.Preferences, error) {
	prefs, err := s.GetPreferences(userID)
	if err != nil {
		return prefs, err
	}
	if err := update(&prefs); err != nil {
		return prefs, err
	}
	prefs.UpdatedAt = NowUTC()
	_, err = s.pool.Exec(context.Background(), `
		INSERT INTO notification_preferences (
			user_id, messages_enabled, calls_enabled, new_device_enabled,
			show_sender_name, show_message_preview, badge_enabled, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (user_id) DO UPDATE SET
			messages_enabled = EXCLUDED.messages_enabled,
			calls_enabled = EXCLUDED.calls_enabled,
			new_device_enabled = EXCLUDED.new_device_enabled,
			show_sender_name = EXCLUDED.show_sender_name,
			show_message_preview = EXCLUDED.show_message_preview,
			badge_enabled = EXCLUDED.badge_enabled,
			updated_at = EXCLUDED.updated_at
	`, prefs.UserID, prefs.MessagesEnabled, prefs.CallsEnabled, prefs.NewDeviceEnabled,
		prefs.ShowSenderName, prefs.ShowMessagePreview, prefs.BadgeEnabled, prefs.UpdatedAt)
	return prefs, err
}

func (s *PostgresStore) AppendDeliveryLog(userID, tokenID, notificationType, platform, status, errMsg string) error {
	// platform is NOT NULL — never insert empty string as NULL.
	if platform == "" {
		platform = "unknown"
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO notification_delivery_log (id, user_id, push_token_id, notification_type, platform, status, error_message)
		VALUES ($1, $2, NULLIF($3, '')::uuid, $4, $5, $6, NULLIF($7, ''))
	`, uuid.NewString(), userID, tokenID, notificationType, platform, status, errMsg)
	return err
}

func (s *PostgresStore) PurgeDeliveryLogsBefore(before time.Time) (int64, error) {
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM notification_delivery_log WHERE created_at < $1
	`, before)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

func (s *PostgresStore) EnqueueRetry(job notification.RetryJob) (string, error) {
	id := job.ID
	if id == "" {
		id = uuid.NewString()
	}
	payload, err := json.Marshal(job.Payload)
	if err != nil {
		return "", err
	}
	_, err = s.pool.Exec(context.Background(), `
		INSERT INTO notification_retry_queue (
			id, user_id, notification_type, payload, priority, attempts, max_attempts, next_attempt_at, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`, id, job.UserID, job.NotificationType, payload, job.Priority, job.Attempts, job.MaxAttempts, job.NextAttemptAt, job.CreatedAt)
	return id, err
}

func (s *PostgresStore) ListDueRetries(limit int, now time.Time) ([]notification.RetryJob, error) {
	if limit <= 0 {
		limit = 20
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, user_id, notification_type, payload, priority, attempts, max_attempts, next_attempt_at,
		       COALESCE(last_error, ''), created_at
		FROM notification_retry_queue
		WHERE completed_at IS NULL AND attempts < max_attempts AND next_attempt_at <= $1
		ORDER BY next_attempt_at ASC
		LIMIT $2
	`, now, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]notification.RetryJob, 0)
	for rows.Next() {
		var job notification.RetryJob
		var payloadRaw []byte
		if err := rows.Scan(
			&job.ID, &job.UserID, &job.NotificationType, &payloadRaw, &job.Priority,
			&job.Attempts, &job.MaxAttempts, &job.NextAttemptAt, &job.LastError, &job.CreatedAt,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(payloadRaw, &job.Payload)
		out = append(out, job)
	}
	return out, rows.Err()
}

func (s *PostgresStore) UpdateRetryAttempt(id string, attempts int, nextAttemptAt time.Time, lastError string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE notification_retry_queue
		SET attempts = $2, next_attempt_at = $3, last_error = $4
		WHERE id = $1
	`, id, attempts, nextAttemptAt, lastError)
	return err
}

func (s *PostgresStore) CompleteRetry(id string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE notification_retry_queue SET completed_at = NOW() WHERE id = $1
	`, id)
	return err
}
