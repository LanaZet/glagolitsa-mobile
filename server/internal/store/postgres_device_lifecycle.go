// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) TouchDeviceLastSeen(deviceID, accountID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE devices
		SET last_seen_at = NOW(), updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2 AND device_status IN ('active', 'pending')
	`, deviceID, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) PurgeDeviceMailbox(deviceID, accountID string) error {
	var current *string
	var previous *string
	err := s.pool.QueryRow(context.Background(), `
		SELECT mailbox_token, mailbox_token_previous
		FROM devices
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&current, &previous)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}

	tokens := make([]string, 0, 2)
	if current != nil && *current != "" {
		tokens = append(tokens, *current)
	}
	if previous != nil && *previous != "" {
		tokens = append(tokens, *previous)
	}
	// Legacy rows used device_id as mailbox before opaque tokens.
	tokens = append(tokens, deviceID)
	if len(tokens) == 0 {
		return nil
	}

	// Close delivery accounting for purged ciphertext (avoid permanent "queued" ghosts).
	_, _ = s.pool.Exec(context.Background(), `
		UPDATE envelope_delivery
		SET state = $2, acked_at = COALESCE(acked_at, NOW())
		WHERE mailbox_token = ANY($1::text[])
		  AND state IN ($3, $4)
	`, tokens, model.DeliveryAcked, model.DeliveryQueued, model.DeliveryFetched)

	_, err = s.pool.Exec(context.Background(), `
		DELETE FROM messages_queue
		WHERE mailbox_token = ANY($1::text[])
	`, tokens)
	return err
}

func (s *PostgresStore) PurgeStaleDevices() (int, error) {
	now := NowUTC()
	rows, err := s.pool.Query(context.Background(), `
		SELECT device_id, account_id, device_status,
		       COALESCE(last_seen_at, created_at, updated_at),
		       length(identity_public_key) > 0
		FROM devices
		WHERE device_status IN ($1, $2)
	`, model.DeviceStatusActive, model.DeviceStatusPending)
	if err != nil {
		return 0, err
	}
	defer rows.Close()

	var candidates []stalePurgeCandidate
	for rows.Next() {
		var c stalePurgeCandidate
		if err := rows.Scan(&c.DeviceID, &c.AccountID, &c.Status, &c.SeenAt, &c.HasIdentityKey); err != nil {
			return 0, err
		}
		candidates = append(candidates, c)
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}

	purged := 0
	for _, t := range filterStalePurgeTargets(candidates, now) {
		if err := s.RevokeDevice(t.DeviceID, t.AccountID); err != nil {
			if errors.Is(err, ErrNotFound) {
				continue
			}
			return purged, err
		}
		purged++
	}

	// Safety net: envelopes for already-revoked mailboxes.
	_, _ = s.pool.Exec(context.Background(), `
		DELETE FROM messages_queue mq
		USING devices d
		WHERE d.device_status = $1
		  AND (
		    mq.mailbox_token = d.mailbox_token
		    OR mq.mailbox_token = d.mailbox_token_previous
		    OR mq.mailbox_token = d.device_id
		  )
	`, model.DeviceStatusRevoked)

	// Orphan mailboxes (device row gone / never linked).
	_, _ = s.pool.Exec(context.Background(), `
		DELETE FROM messages_queue mq
		WHERE NOT EXISTS (
			SELECT 1 FROM devices d
			WHERE d.device_status IN ('active', 'pending')
			  AND (
			    mq.mailbox_token = d.mailbox_token
			    OR mq.mailbox_token = d.mailbox_token_previous
			    OR mq.mailbox_token = d.device_id
			  )
		)
	`)

	return purged, nil
}
