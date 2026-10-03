// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/base64"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) ListUserDevices(accountID string, activeOnly bool) ([]model.UserDevice, error) {
	query := `
		SELECT device_id, COALESCE(mailbox_token, device_id), registration_id, identity_public_key, device_status
		FROM devices
		WHERE account_id = $1
	`
	if activeOnly {
		query += ` AND device_status = 'active' AND length(identity_public_key) > 0`
	}
	query += ` ORDER BY created_at ASC`

	rows, err := s.pool.Query(context.Background(), query, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	devices := make([]model.UserDevice, 0)
	for rows.Next() {
		var device model.UserDevice
		var identityPublicKey []byte
		if err := rows.Scan(
			&device.DeviceID, &device.MailboxToken, &device.RegistrationID, &identityPublicKey, &device.DeviceStatus,
		); err != nil {
			return nil, err
		}
		device.IdentityPublicKey = base64.StdEncoding.EncodeToString(identityPublicKey)
		devices = append(devices, device)
	}
	return devices, rows.Err()
}

func (s *PostgresStore) MailboxOwnerAccountID(mailboxToken string) (string, error) {
	var accountID string
	err := s.pool.QueryRow(context.Background(), `
		SELECT account_id
		FROM devices
		WHERE mailbox_token = $1
		   OR device_id = $1
		   OR (mailbox_token_previous = $1 AND mailbox_previous_expires_at > NOW())
		LIMIT 1
	`, mailboxToken).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return accountID, err
}

func (s *PostgresStore) GetDeviceMailboxToken(deviceID, accountID string) (string, error) {
	var mailboxToken string
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(mailbox_token, device_id)
		FROM devices
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&mailboxToken)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return mailboxToken, err
}

func (s *PostgresStore) ResolveMailboxTokens(deviceID, accountID string) ([]string, error) {
	var current string
	var previous *string
	var previousExpiresAt *time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(mailbox_token, device_id), mailbox_token_previous, mailbox_previous_expires_at
		FROM devices
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&current, &previous, &previousExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	tokens := []string{current}
	if previous != nil && *previous != "" && previousExpiresAt != nil && previousExpiresAt.After(NowUTC()) {
		tokens = append(tokens, *previous)
	}
	return tokens, nil
}

func (s *PostgresStore) RotateDeviceMailbox(deviceID, accountID string) (model.RotateMailboxResponse, error) {
	tokens, err := s.ResolveMailboxTokens(deviceID, accountID)
	if err != nil {
		return model.RotateMailboxResponse{}, err
	}
	current := tokens[0]
	newToken := NewMailboxToken()
	graceUntil := NowUTC().Add(24 * time.Hour)
	_, err = s.pool.Exec(context.Background(), `
		UPDATE devices
		SET mailbox_token = $3,
		    mailbox_token_previous = $4,
		    mailbox_previous_expires_at = $5,
		    mailbox_rotated_at = NOW(),
		    updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID, newToken, current, graceUntil)
	if err != nil {
		return model.RotateMailboxResponse{}, err
	}
	return model.RotateMailboxResponse{
		DeviceID:             deviceID,
		MailboxToken:         newToken,
		PreviousMailboxToken: current,
		PreviousExpiresAt:    graceUntil.Format(time.RFC3339),
	}, nil
}

func (s *PostgresStore) EnqueueRelayEnvelopes(envelopes []RelayEnvelopeInput) ([]string, error) {
	if s.relayQueue != nil {
		return s.relayQueue.EnqueueRelayEnvelopes(envelopes)
	}
	if len(envelopes) == 0 {
		return nil, errors.New("envelopes are required")
	}

	ctx := context.Background()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	ids := make([]string, 0, len(envelopes))
	for _, envelope := range envelopes {
		var accountID string
		err := tx.QueryRow(ctx, `
			SELECT account_id
			FROM devices
			WHERE mailbox_token = $1
			   OR device_id = $1
			   OR (mailbox_token_previous = $1 AND mailbox_previous_expires_at > NOW())
			LIMIT 1
		`, envelope.MailboxToken).Scan(&accountID)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		envelopeID := uuid.NewString()
		expiresAt := envelope.ExpiresAt
		if expiresAt.IsZero() {
			expiresAt = NowUTC().Add(30 * 24 * time.Hour)
		}
		_, err = tx.Exec(ctx, `
			INSERT INTO messages_queue (
				envelope_id, mailbox_token, envelope_type, ciphertext, size_bucket, expires_at
			) VALUES ($1, $2, $3, $4, $5, $6)
		`,
			envelopeID,
			envelope.MailboxToken,
			envelope.EnvelopeType,
			envelope.Ciphertext,
			envelope.SizeBucket,
			expiresAt,
		)
		if err != nil {
			return nil, err
		}
		ids = append(ids, envelopeID)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return ids, nil
}

func (s *PostgresStore) ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]QueuedEnvelopeRecord, error) {
	if s.relayQueue != nil {
		return s.relayQueue.ListQueuedEnvelopes(mailboxTokens, limit)
	}
	if len(mailboxTokens) == 0 {
		return []QueuedEnvelopeRecord{}, nil
	}
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT envelope_id, mailbox_token, envelope_type, ciphertext, size_bucket, created_at, expires_at
		FROM messages_queue
		WHERE mailbox_token = ANY($1) AND expires_at > NOW()
		ORDER BY created_at ASC
		LIMIT $2
	`, mailboxTokens, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	envelopes := make([]QueuedEnvelopeRecord, 0)
	for rows.Next() {
		var envelope QueuedEnvelopeRecord
		if err := rows.Scan(
			&envelope.EnvelopeID,
			&envelope.MailboxToken,
			&envelope.EnvelopeType,
			&envelope.Ciphertext,
			&envelope.SizeBucket,
			&envelope.CreatedAt,
			&envelope.ExpiresAt,
		); err != nil {
			return nil, err
		}
		envelopes = append(envelopes, envelope)
	}
	return envelopes, rows.Err()
}

func (s *PostgresStore) AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error) {
	if s.relayQueue != nil {
		return s.relayQueue.AckQueuedEnvelopes(mailboxTokens, envelopeIDs)
	}
	if len(envelopeIDs) == 0 || len(mailboxTokens) == 0 {
		return 0, nil
	}
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM messages_queue
		WHERE mailbox_token = ANY($1) AND envelope_id = ANY($2::uuid[])
	`, mailboxTokens, envelopeIDs)
	if err != nil {
		return 0, err
	}
	return int(tag.RowsAffected()), nil
}
