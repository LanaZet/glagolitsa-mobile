// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"bytes"
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) RegisterDevice(registration DeviceRegistration) (int, error) {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(context.Background())

	var existingAccountID string
	var existingIdentityKey []byte
	var existingDeviceStatus string
	var existingMailbox *string
	var existingMailboxPrev *string
	err = tx.QueryRow(context.Background(), `
		SELECT account_id, identity_public_key, device_status, mailbox_token, mailbox_token_previous
		FROM devices WHERE device_id = $1
	`, registration.DeviceID).Scan(
		&existingAccountID,
		&existingIdentityKey,
		&existingDeviceStatus,
		&existingMailbox,
		&existingMailboxPrev,
	)
	isUpdate := err == nil
	identityChanged := false
	switch {
	case isUpdate:
		if existingAccountID != registration.AccountID {
			return 0, ErrForbidden
		}
		identityChanged = !bytes.Equal(existingIdentityKey, registration.IdentityPublicKey)
		_, err = tx.Exec(context.Background(), `
			UPDATE devices
			SET registration_id = $2,
			    identity_public_key = $3,
			    signed_prekey_id = $4,
			    signed_prekey_public_key = $5,
			    signed_prekey_signature = $6,
			    signed_prekey_created_at = $7,
			    pq_prekey_id = $8,
			    pq_public_material = $9,
			    pq_prekey_signature = $10,
			    pq_prekey_created_at = $11,
			    last_seen_at = NOW(),
			    updated_at = NOW()
			WHERE device_id = $1
		`,
			registration.DeviceID,
			registration.RegistrationID,
			registration.IdentityPublicKey,
			registration.SignedPreKey.ID,
			registration.SignedPreKey.PublicKey,
			registration.SignedPreKey.Signature,
			registration.SignedPreKey.CreatedAt,
			registration.PqPreKey.ID,
			registration.PqPreKey.PublicMaterial,
			registration.PqPreKey.Signature,
			registration.PqPreKey.CreatedAt,
		)
		if err != nil {
			return 0, err
		}
		// Re-enroll revoked/pending rows instead of leaving them dead forever
		// (common after reinstall when the same stable device_id comes back).
		if existingDeviceStatus != model.DeviceStatusActive {
			var activeCount int
			if err := tx.QueryRow(context.Background(), `
				SELECT COUNT(*) FROM devices
				WHERE account_id = $1 AND device_status = 'active' AND device_id <> $2
			`, registration.AccountID, registration.DeviceID).Scan(&activeCount); err != nil {
				return 0, err
			}
			nextStatus := model.DeviceStatusPending
			if activeCount == 0 {
				nextStatus = model.DeviceStatusActive
			}
			if registration.Attestation != nil && registration.Attestation.ConfirmingDeviceID != "" {
				var confirmingStatus string
				confirmErr := tx.QueryRow(context.Background(), `
					SELECT device_status FROM devices
					WHERE device_id = $1 AND account_id = $2
				`, registration.Attestation.ConfirmingDeviceID, registration.AccountID).Scan(&confirmingStatus)
				if confirmErr == nil && confirmingStatus == model.DeviceStatusActive {
					nextStatus = model.DeviceStatusActive
				}
			}
			_, err = tx.Exec(context.Background(), `
				UPDATE devices
				SET device_status = $3, updated_at = NOW(), last_seen_at = NOW()
				WHERE device_id = $1 AND account_id = $2
			`, registration.DeviceID, registration.AccountID, nextStatus)
			if err != nil {
				return 0, err
			}
			if nextStatus == model.DeviceStatusActive {
				_, _ = tx.Exec(context.Background(), `
					UPDATE users SET account_status = $2 WHERE id = $1
				`, registration.AccountID, model.AccountStatusActive)
			}
		}
	case errors.Is(err, pgx.ErrNoRows):
		var activeCount int
		if err := tx.QueryRow(context.Background(), `
			SELECT COUNT(*) FROM devices WHERE account_id = $1 AND device_status = 'active'
		`, registration.AccountID).Scan(&activeCount); err != nil {
			return 0, err
		}
		deviceStatus := model.DeviceStatusActive
		if activeCount > 0 {
			deviceStatus = model.DeviceStatusPending
		}
		if registration.Attestation != nil && registration.Attestation.ConfirmingDeviceID != "" {
			var confirmingStatus string
			confirmErr := tx.QueryRow(context.Background(), `
				SELECT device_status FROM devices
				WHERE device_id = $1 AND account_id = $2
			`, registration.Attestation.ConfirmingDeviceID, registration.AccountID).Scan(&confirmingStatus)
			if confirmErr == nil && confirmingStatus == model.DeviceStatusActive {
				deviceStatus = model.DeviceStatusActive
			}
		}

		_, err = tx.Exec(context.Background(), `
			INSERT INTO devices (
				device_id, account_id, registration_id, identity_public_key,
				signed_prekey_id, signed_prekey_public_key, signed_prekey_signature, signed_prekey_created_at,
				pq_prekey_id, pq_public_material, pq_prekey_signature, pq_prekey_created_at,
				mailbox_token, device_status, last_seen_at
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, NOW())
		`,
			registration.DeviceID,
			registration.AccountID,
			registration.RegistrationID,
			registration.IdentityPublicKey,
			registration.SignedPreKey.ID,
			registration.SignedPreKey.PublicKey,
			registration.SignedPreKey.Signature,
			registration.SignedPreKey.CreatedAt,
			registration.PqPreKey.ID,
			registration.PqPreKey.PublicMaterial,
			registration.PqPreKey.Signature,
			registration.PqPreKey.CreatedAt,
			NewMailboxToken(),
			deviceStatus,
		)
		if err != nil {
			return 0, err
		}
		if activeCount == 0 {
			_, _ = tx.Exec(context.Background(), `
				UPDATE users SET account_status = $2 WHERE id = $1
			`, registration.AccountID, model.AccountStatusActive)
		}
	default:
		return 0, err
	}

	// Full re-registration always ships a fresh OTP batch that matches local private
	// keys. Keeping older server OTPs after reinstall/stable-device_id re-enroll makes
	// GetDeviceBundle hand out prekeys the install no longer holds →
	// InvalidKeyIdException: No such prekeyrecord on the recipient.
	if isUpdate {
		if _, err := tx.Exec(context.Background(), `
			DELETE FROM prekeys WHERE device_id = $1
		`, registration.DeviceID); err != nil {
			return 0, err
		}
		// Ciphertext encrypted to a previous identity/OTP set is permanently unreadable.
		if identityChanged {
			tokens := make([]string, 0, 3)
			if existingMailbox != nil && *existingMailbox != "" {
				tokens = append(tokens, *existingMailbox)
			}
			if existingMailboxPrev != nil && *existingMailboxPrev != "" {
				tokens = append(tokens, *existingMailboxPrev)
			}
			tokens = append(tokens, registration.DeviceID)
			if _, err := tx.Exec(context.Background(), `
				DELETE FROM messages_queue WHERE mailbox_token = ANY($1::text[])
			`, tokens); err != nil {
				return 0, err
			}
		}
	}

	stored := 0
	for _, prekey := range registration.OneTimePrekeys {
		tag, err := tx.Exec(context.Background(), `
			INSERT INTO prekeys (device_id, prekey_id, public_key)
			VALUES ($1, $2, $3)
			ON CONFLICT (device_id, prekey_id) DO NOTHING
		`, registration.DeviceID, prekey.ID, prekey.PublicKey)
		if err != nil {
			return 0, err
		}
		stored += int(tag.RowsAffected())
	}

	keyEventType := model.KeyEventRegistered
	if isUpdate {
		keyEventType = model.KeyEventIdentityUpdated
	}
	if err := s.appendKeyChangeEvent(
		tx,
		registration.AccountID,
		registration.DeviceID,
		keyEventType,
		registration.IdentityPublicKey,
		registration.SignedPreKey.ID,
	); err != nil {
		return 0, err
	}

	if registration.Attestation != nil && len(registration.Attestation.Signature) > 0 {
		_, err = tx.Exec(context.Background(), `
			INSERT INTO device_key_attestations (device_id, confirming_device_id, signature)
			VALUES ($1, $2, $3)
			ON CONFLICT (device_id) DO UPDATE
			SET confirming_device_id = EXCLUDED.confirming_device_id,
			    signature = EXCLUDED.signature,
			    created_at = NOW()
		`, registration.DeviceID, registration.Attestation.ConfirmingDeviceID, registration.Attestation.Signature)
		if err != nil {
			return 0, err
		}
	}

	if err := tx.Commit(context.Background()); err != nil {
		return 0, err
	}
	return stored, nil
}

func (s *PostgresStore) ReplenishPrekeys(accountID, deviceID string, prekeys []OneTimePreKeyRecord) (int, error) {
	if err := s.requireActiveDevice(deviceID, accountID); err != nil {
		return 0, err
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(context.Background())

	stored := 0
	for _, prekey := range prekeys {
		tag, err := tx.Exec(context.Background(), `
			INSERT INTO prekeys (device_id, prekey_id, public_key)
			VALUES ($1, $2, $3)
			ON CONFLICT (device_id, prekey_id) DO NOTHING
		`, deviceID, prekey.ID, prekey.PublicKey)
		if err != nil {
			return 0, err
		}
		stored += int(tag.RowsAffected())
	}

	if err := tx.Commit(context.Background()); err != nil {
		return 0, err
	}
	return stored, nil
}

func (s *PostgresStore) GetDeviceBundle(deviceID string) (DeviceKeyBundleRecord, error) {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return DeviceKeyBundleRecord{}, err
	}
	defer tx.Rollback(context.Background())

	var bundle DeviceKeyBundleRecord
	var deviceStatus string
	err = tx.QueryRow(context.Background(), `
		SELECT account_id, registration_id, identity_public_key,
		       signed_prekey_id, signed_prekey_public_key, signed_prekey_signature, signed_prekey_created_at,
		       pq_prekey_id, pq_public_material, pq_prekey_signature, pq_prekey_created_at,
		       device_status
		FROM devices
		WHERE device_id = $1
	`, deviceID).Scan(
		&bundle.AccountID,
		&bundle.RegistrationID,
		&bundle.IdentityPublicKey,
		&bundle.SignedPreKey.ID,
		&bundle.SignedPreKey.PublicKey,
		&bundle.SignedPreKey.Signature,
		&bundle.SignedPreKey.CreatedAt,
		&bundle.PqPreKey.ID,
		&bundle.PqPreKey.PublicMaterial,
		&bundle.PqPreKey.Signature,
		&bundle.PqPreKey.CreatedAt,
		&deviceStatus,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return DeviceKeyBundleRecord{}, ErrNotFound
	}
	if err != nil {
		return DeviceKeyBundleRecord{}, err
	}
	if deviceStatus != model.DeviceStatusActive {
		return DeviceKeyBundleRecord{}, ErrForbidden
	}
	if len(bundle.IdentityPublicKey) == 0 {
		return DeviceKeyBundleRecord{}, ErrNotFound
	}
	bundle.DeviceID = deviceID

	var oneTime OneTimePreKeyRecord
	err = tx.QueryRow(context.Background(), `
		DELETE FROM prekeys
		WHERE device_id = $1
		  AND prekey_id = (
		    SELECT prekey_id
		    FROM prekeys
		    WHERE device_id = $1
		    ORDER BY created_at ASC, prekey_id ASC
		    LIMIT 1
		  )
		RETURNING prekey_id, public_key
	`, deviceID).Scan(&oneTime.ID, &oneTime.PublicKey)
	if err == nil {
		bundle.OneTimePreKey = &oneTime
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return DeviceKeyBundleRecord{}, fmt.Errorf("consume prekey: %w", err)
	}

	if err := tx.Commit(context.Background()); err != nil {
		return DeviceKeyBundleRecord{}, err
	}
	return bundle, nil
}

func (s *PostgresStore) DeviceOwnerAccountID(deviceID string) (string, error) {
	return s.deviceOwnerID(deviceID)
}

func (s *PostgresStore) deviceOwnerID(deviceID string) (string, error) {
	var accountID string
	err := s.pool.QueryRow(context.Background(), `
		SELECT account_id FROM devices WHERE device_id = $1
	`, deviceID).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return accountID, err
}
