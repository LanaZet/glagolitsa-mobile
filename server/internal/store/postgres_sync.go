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
)

func (s *PostgresStore) AppendSyncEvent(input AppendSyncEventInput) (SyncEventRecord, error) {
	if input.Version <= 0 {
		input.Version = 1
	}
	meta, _ := json.Marshal(input.Metadata)
	var eventID int64
	var createdAt time.Time
	err := s.pool.QueryRow(context.Background(), `
		INSERT INTO sync_events (
			scope_user_id, chat_id, message_id, operation, version,
			ciphertext, metadata, actor_id, created_at
		) VALUES (
			NULLIF($1, '')::uuid, NULLIF($2, '')::uuid, NULLIF($3, ''),
			$4, $5, $6, $7, NULLIF($8, '')::uuid, NOW()
		)
		RETURNING event_id, created_at
	`, input.ScopeUserID, input.ChatID, input.MessageID, input.Operation, input.Version,
		nullableBytes(input.Ciphertext), meta, input.ActorID).Scan(&eventID, &createdAt)
	if err != nil {
		return SyncEventRecord{}, err
	}
	return SyncEventRecord{
		EventID: eventID, ScopeUserID: input.ScopeUserID, ChatID: input.ChatID,
		MessageID: input.MessageID, Operation: input.Operation, Version: input.Version,
		Ciphertext: append([]byte(nil), input.Ciphertext...), Metadata: input.Metadata,
		ActorID: input.ActorID, CreatedAt: createdAt,
	}, nil
}

func (s *PostgresStore) appendSyncEventFromChat(event ChatEventInput) {
	operation := mapChatEventToSyncOperation(event.EventType)
	if operation == "" {
		return
	}
	version := 1
	if event.Metadata != nil {
		if v, ok := event.Metadata["version"].(float64); ok {
			version = int(v)
		}
	}
	messageID := event.EntityID
	if event.EventType == modelChatEventMessageSent || event.EventType == modelChatEventMessageDeleted {
		messageID = event.EntityID
	}
	_, _ = s.AppendSyncEvent(AppendSyncEventInput{
		ChatID: event.ChatID, MessageID: messageID, Operation: operation,
		Version: version, Metadata: event.Metadata, ActorID: event.ActorID,
	})
}

const (
	modelChatEventMessageSent    = "message.sent"
	modelChatEventMessageDeleted = "message.deleted"
)

func mapChatEventToSyncOperation(eventType string) string {
	switch eventType {
	case modelChatEventMessageSent:
		return "message.created"
	case modelChatEventMessageDeleted:
		return "message.deleted"
	case "member.joined":
		return "group.member_joined"
	case "member.removed", "member.left":
		return "group.member_removed"
	case "chat.created":
		return "group.created"
	case "envelope.relayed":
		return "message.created"
	default:
		return eventType
	}
}

func (s *PostgresStore) ListSyncEvents(userID string, afterEventID int64, limit int) ([]SyncEventRecord, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT event_id,
		       COALESCE(scope_user_id::text, ''),
		       COALESCE(chat_id::text, ''),
		       COALESCE(message_id, ''),
		       operation, version, ciphertext, metadata,
		       COALESCE(actor_id::text, ''), created_at
		FROM sync_events e
		WHERE e.event_id > $1
		  AND (
		    e.scope_user_id = $2::uuid
		    OR (
		      e.chat_id IS NOT NULL
		      AND EXISTS (
		        SELECT 1 FROM chat_members m
		        WHERE m.chat_id = e.chat_id AND m.user_id = $2::uuid
		      )
		    )
		  )
		ORDER BY e.event_id ASC
		LIMIT $3
	`, afterEventID, userID, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanSyncEventRows(rows)
}

func (s *PostgresStore) GetLatestSyncEventID(userID string) (int64, error) {
	var eventID int64
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(MAX(e.event_id), 0)
		FROM sync_events e
		WHERE e.scope_user_id = $1::uuid
		   OR (
		     e.chat_id IS NOT NULL
		     AND EXISTS (
		       SELECT 1 FROM chat_members m
		       WHERE m.chat_id = e.chat_id AND m.user_id = $1::uuid
		     )
		   )
	`, userID).Scan(&eventID)
	return eventID, err
}

func (s *PostgresStore) RegisterSyncDevice(userID string, input RegisterSyncDeviceInput) (SyncDeviceRecord, error) {
	profile := input.SyncProfile
	if profile == "" {
		profile = SyncProfileBalanced
	}
	id := uuid.NewString()
	now := NowUTC()
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO sync_devices (id, user_id, device_id, label, sync_profile, registered_at, last_seen_at)
		VALUES ($1, $2, $3, $4, $5, $6, $6)
		ON CONFLICT (user_id, device_id) DO UPDATE SET
			label = EXCLUDED.label,
			sync_profile = EXCLUDED.sync_profile,
			last_seen_at = EXCLUDED.last_seen_at
	`, id, userID, input.DeviceID, input.Label, profile, now)
	if err != nil {
		return SyncDeviceRecord{}, err
	}
	return s.GetSyncDevice(userID, input.DeviceID)
}

func (s *PostgresStore) GetSyncDevice(userID, deviceID string) (SyncDeviceRecord, error) {
	var record SyncDeviceRecord
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, user_id, device_id, COALESCE(label, ''),
		       last_acked_event_id, snapshot_event_id, sync_profile,
		       registered_at, last_seen_at
		FROM sync_devices
		WHERE user_id = $1 AND device_id = $2
	`, userID, deviceID).Scan(
		&record.ID, &record.UserID, &record.DeviceID, &record.Label,
		&record.LastAckedEventID, &record.SnapshotEventID, &record.SyncProfile,
		&record.RegisteredAt, &record.LastSeenAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return SyncDeviceRecord{}, ErrNotFound
	}
	return record, err
}

func (s *PostgresStore) AckSyncEvents(userID, deviceID string, lastEventID int64) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE sync_devices
		SET last_acked_event_id = GREATEST(last_acked_event_id, $3),
		    last_seen_at = NOW()
		WHERE user_id = $1 AND device_id = $2
	`, userID, deviceID, lastEventID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) SaveSyncSnapshot(userID string, eventID int64, data []byte) (SyncSnapshotRecord, error) {
	record := SyncSnapshotRecord{
		ID: uuid.NewString(), UserID: userID, EventID: eventID,
		SnapshotData: append([]byte(nil), data...), CreatedAt: NowUTC(),
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO sync_snapshots (id, user_id, event_id, snapshot_data, created_at)
		VALUES ($1, $2, $3, $4, $5)
	`, record.ID, record.UserID, record.EventID, record.SnapshotData, record.CreatedAt)
	if err != nil {
		return SyncSnapshotRecord{}, err
	}
	_, _ = s.pool.Exec(context.Background(), `
		UPDATE sync_devices
		SET snapshot_event_id = $2
		WHERE user_id = $1
	`, userID, eventID)
	return record, nil
}

func (s *PostgresStore) GetLatestSyncSnapshot(userID string) (SyncSnapshotRecord, error) {
	var record SyncSnapshotRecord
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, user_id, event_id, snapshot_data, created_at
		FROM sync_snapshots
		WHERE user_id = $1
		ORDER BY event_id DESC
		LIMIT 1
	`, userID).Scan(&record.ID, &record.UserID, &record.EventID, &record.SnapshotData, &record.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return SyncSnapshotRecord{}, ErrNotFound
	}
	return record, err
}

func (s *PostgresStore) EnqueueSyncOffline(userID string, input EnqueueSyncEventInput) (SyncOfflineQueueRecord, error) {
	meta, _ := json.Marshal(input.Metadata)
	id := uuid.NewString()
	now := NowUTC()
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO sync_offline_queue (
			id, user_id, device_id, client_event_id, operation,
			chat_id, message_id, version, ciphertext, metadata, created_at
		) VALUES (
			$1, $2, $3, $4, $5,
			NULLIF($6, '')::uuid, NULLIF($7, ''), $8, $9, $10, $11
		)
		ON CONFLICT (user_id, device_id, client_event_id) DO NOTHING
	`, id, userID, input.DeviceID, input.ClientEventID, input.Operation,
		input.ChatID, input.MessageID, input.Version, nullableBytes(input.Ciphertext), meta, now)
	if err != nil {
		return SyncOfflineQueueRecord{}, err
	}
	return SyncOfflineQueueRecord{
		ID: id, UserID: userID, DeviceID: input.DeviceID, ClientEventID: input.ClientEventID,
		Operation: input.Operation, ChatID: input.ChatID, MessageID: input.MessageID,
		Version: input.Version, Ciphertext: input.Ciphertext, Metadata: input.Metadata,
		Status: SyncQueuePending, NextRetryAt: now, CreatedAt: now,
	}, nil
}

func (s *PostgresStore) ListSyncOfflineDue(limit int, before time.Time) ([]SyncOfflineQueueRecord, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, user_id, device_id, client_event_id, operation,
		       COALESCE(chat_id::text, ''), COALESCE(message_id, ''),
		       version, ciphertext, metadata, status, attempts,
		       next_retry_at, COALESCE(last_error, ''), created_at
		FROM sync_offline_queue
		WHERE status IN ('pending', 'failed')
		  AND next_retry_at <= $1
		ORDER BY next_retry_at ASC
		LIMIT $2
	`, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanSyncOfflineRows(rows)
}

func (s *PostgresStore) MarkSyncOfflineApplied(id string, eventID int64) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE sync_offline_queue
		SET status = $2, last_error = NULL
		WHERE id = $1
	`, id, SyncQueueApplied)
	return err
}

func (s *PostgresStore) MarkSyncOfflineRetry(id, lastError string, attempts int, nextRetry time.Time) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE sync_offline_queue
		SET status = $2, attempts = $3, next_retry_at = $4, last_error = $5
		WHERE id = $1
	`, id, SyncQueueFailed, attempts, nextRetry, lastError)
	return err
}

func (s *PostgresStore) GetSyncEventVersion(chatID, messageID, operation string) (int, error) {
	var version int
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(MAX(version), 0)
		FROM sync_events
		WHERE chat_id = $1::uuid AND message_id = $2 AND operation = $3
	`, chatID, messageID, operation).Scan(&version)
	return version, err
}

func scanSyncEventRows(rows pgx.Rows) ([]SyncEventRecord, error) {
	var out []SyncEventRecord
	for rows.Next() {
		var record SyncEventRecord
		var meta []byte
		var ciphertext []byte
		if err := rows.Scan(
			&record.EventID, &record.ScopeUserID, &record.ChatID, &record.MessageID,
			&record.Operation, &record.Version, &ciphertext, &meta,
			&record.ActorID, &record.CreatedAt,
		); err != nil {
			return nil, err
		}
		record.Ciphertext = ciphertext
		_ = json.Unmarshal(meta, &record.Metadata)
		out = append(out, record)
	}
	return out, rows.Err()
}

func scanSyncOfflineRows(rows pgx.Rows) ([]SyncOfflineQueueRecord, error) {
	var out []SyncOfflineQueueRecord
	for rows.Next() {
		var record SyncOfflineQueueRecord
		var meta []byte
		var ciphertext []byte
		if err := rows.Scan(
			&record.ID, &record.UserID, &record.DeviceID, &record.ClientEventID, &record.Operation,
			&record.ChatID, &record.MessageID, &record.Version, &ciphertext, &meta,
			&record.Status, &record.Attempts, &record.NextRetryAt, &record.LastError, &record.CreatedAt,
		); err != nil {
			return nil, err
		}
		record.Ciphertext = ciphertext
		_ = json.Unmarshal(meta, &record.Metadata)
		out = append(out, record)
	}
	return out, rows.Err()
}

func nullableBytes(data []byte) any {
	if len(data) == 0 {
		return nil
	}
	return data
}
