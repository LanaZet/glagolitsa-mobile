// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/base64"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) appendKeyChangeEvent(
	tx pgx.Tx,
	accountID, deviceID, eventType string,
	identityPublicKey []byte,
	signedPrekeyID int,
) error {
	var prevHash []byte
	_ = tx.QueryRow(context.Background(), `
		SELECT event_hash FROM key_change_events
		WHERE account_id = $1
		ORDER BY created_at DESC, id DESC
		LIMIT 1
	`, accountID).Scan(&prevHash)

	idHash := IdentityKeyHash(identityPublicKey)
	prevHex := ""
	if len(prevHash) > 0 {
		prevHex = HashHex(prevHash)
	}
	eventHash := KeyChangeEventHash(prevHex, accountID, deviceID, eventType, idHash, signedPrekeyID)

	_, err := tx.Exec(context.Background(), `
		INSERT INTO key_change_events (
			id, account_id, device_id, event_type,
			identity_key_hash, signed_prekey_id, prev_event_hash, event_hash
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`, uuid.NewString(), accountID, deviceID, eventType, idHash, signedPrekeyID, prevHash, eventHash)
	return err
}

func (s *PostgresStore) RotateSignedPreKey(accountID, deviceID string, rotation SignedPreKeyRotation) error {
	if err := s.requireActiveDevice(deviceID, accountID); err != nil {
		return err
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())

	var identityKey []byte
	err = tx.QueryRow(context.Background(), `
		SELECT identity_public_key FROM devices
		WHERE device_id = $1 AND account_id = $2 AND device_status = $3
	`, deviceID, accountID, model.DeviceStatusActive).Scan(&identityKey)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}

	_, err = tx.Exec(context.Background(), `
		UPDATE devices
		SET signed_prekey_id = $3,
		    signed_prekey_public_key = $4,
		    signed_prekey_signature = $5,
		    signed_prekey_created_at = $6,
		    pq_prekey_id = $7,
		    pq_public_material = $8,
		    pq_prekey_signature = $9,
		    pq_prekey_created_at = $10,
		    updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2
	`,
		deviceID, accountID,
		rotation.SignedPreKey.ID,
		rotation.SignedPreKey.PublicKey,
		rotation.SignedPreKey.Signature,
		rotation.SignedPreKey.CreatedAt,
		rotation.PqPreKey.ID,
		rotation.PqPreKey.PublicMaterial,
		rotation.PqPreKey.Signature,
		rotation.PqPreKey.CreatedAt,
	)
	if err != nil {
		return err
	}

	if err := s.appendKeyChangeEvent(tx, accountID, deviceID, model.KeyEventSignedPrekeyRot, identityKey, rotation.SignedPreKey.ID); err != nil {
		return err
	}
	return tx.Commit(context.Background())
}

func (s *PostgresStore) ListKeyChangeEvents(accountID string, limit int) ([]KeyChangeEventRecord, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, account_id, device_id, event_type,
		       identity_key_hash, signed_prekey_id, prev_event_hash, event_hash, created_at
		FROM key_change_events
		WHERE account_id = $1
		ORDER BY created_at ASC
		LIMIT $2
	`, accountID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	events := make([]KeyChangeEventRecord, 0)
	for rows.Next() {
		var event KeyChangeEventRecord
		if err := rows.Scan(
			&event.ID, &event.AccountID, &event.DeviceID, &event.EventType,
			&event.IdentityKeyHash, &event.SignedPrekeyID, &event.PrevEventHash, &event.EventHash, &event.CreatedAt,
		); err != nil {
			return nil, err
		}
		events = append(events, event)
	}
	return events, rows.Err()
}

func (s *PostgresStore) GetSafetyNumberMaterial(deviceID string) (model.SafetyNumberResponse, error) {
	var resp model.SafetyNumberResponse
	var identityKey []byte
	var status string
	err := s.pool.QueryRow(context.Background(), `
		SELECT account_id, registration_id, identity_public_key, device_status
		FROM devices WHERE device_id = $1
	`, deviceID).Scan(&resp.AccountID, &resp.RegistrationID, &identityKey, &status)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.SafetyNumberResponse{}, ErrNotFound
	}
	if err != nil {
		return model.SafetyNumberResponse{}, err
	}
	if status != model.DeviceStatusActive {
		return model.SafetyNumberResponse{}, ErrForbidden
	}
	resp.DeviceID = deviceID
	resp.IdentityPublicKey = base64.StdEncoding.EncodeToString(identityKey)
	return resp, nil
}

func (s *PostgresStore) CountRemainingPrekeys(accountID, deviceID string) (int, error) {
	if err := s.requireOwnedDevice(deviceID, accountID); err != nil {
		return 0, err
	}
	var count int
	err := s.pool.QueryRow(context.Background(), `
		SELECT COUNT(*) FROM prekeys WHERE device_id = $1
	`, deviceID).Scan(&count)
	return count, err
}

func (s *PostgresStore) PurgeDeviceKeys(deviceID, accountID string) error {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())

	var identityKey []byte
	err = tx.QueryRow(context.Background(), `
		SELECT identity_public_key FROM devices
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&identityKey)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}

	_, err = tx.Exec(context.Background(), `DELETE FROM prekeys WHERE device_id = $1`, deviceID)
	if err != nil {
		return err
	}

	empty := []byte{}
	_, err = tx.Exec(context.Background(), `
		UPDATE devices
		SET identity_public_key = $3,
		    signed_prekey_public_key = $4,
		    signed_prekey_signature = $5,
		    pq_public_material = $6,
		    pq_prekey_signature = $7,
		    device_status = $8,
		    updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID, empty, empty, empty, empty, empty, model.DeviceStatusRevoked)
	if err != nil {
		return err
	}

	if err := s.appendKeyChangeEvent(tx, accountID, deviceID, model.KeyEventDeviceRevoked, identityKey, 0); err != nil {
		return err
	}
	return tx.Commit(context.Background())
}

func (s *PostgresStore) requireActiveDevice(deviceID, accountID string) error {
	var status string
	err := s.pool.QueryRow(context.Background(), `
		SELECT device_status FROM devices WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&status)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if status != model.DeviceStatusActive {
		return ErrForbidden
	}
	return nil
}

func (s *PostgresStore) requireOwnedDevice(deviceID, accountID string) error {
	var owner string
	err := s.pool.QueryRow(context.Background(), `
		SELECT account_id FROM devices WHERE device_id = $1
	`, deviceID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if owner != accountID {
		return ErrForbidden
	}
	return nil
}
