// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) CreateAccount(user model.User, passwordHash string) (model.AccountRecord, error) {
	username := strings.ToLower(strings.TrimSpace(user.Username))
	if username == "" {
		return model.AccountRecord{}, errors.New("username is required")
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return model.AccountRecord{}, err
	}
	defer tx.Rollback(context.Background())

	account := model.AccountRecord{
		ID:            user.ID,
		PasswordHash:  passwordHash,
		TrustTier:     model.TrustTierNew,
		AccountStatus: model.AccountStatusActive,
		AccountRole:   model.AccountRoleUser,
		CreatedAt:     user.CreatedAt,
	}
	if account.ID == "" {
		account.ID = uuid.NewString()
	}
	if account.CreatedAt.IsZero() {
		account.CreatedAt = NowUTC()
	}

	_, err = tx.Exec(context.Background(), `
		INSERT INTO users (
			id, username, password_hash, created_at, trust_tier, account_status,
			account_role, recovery_key_hash, recovery_key_hint
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, '', '')
	`, account.ID, username, passwordHash, account.CreatedAt, account.TrustTier, account.AccountStatus, account.AccountRole)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			return model.AccountRecord{}, ErrAlreadyExists
		}
		return model.AccountRecord{}, err
	}

	user.Username = username
	email := strings.ToLower(strings.TrimSpace(user.Email))
	_, err = tx.Exec(context.Background(), `
		INSERT INTO profiles (
			user_id, username, email, display_name, status, bio,
			avatar_url, presence, nickname, position
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
	`, account.ID, username, email, user.DisplayName, user.Status, user.Bio,
		user.AvatarURL, user.Presence, user.Nickname, user.Position)
	if err != nil {
		return model.AccountRecord{}, err
	}

	_, err = tx.Exec(context.Background(), `
		INSERT INTO account_reputation (user_id) VALUES ($1)
		ON CONFLICT (user_id) DO NOTHING
	`, account.ID)
	if err != nil {
		return model.AccountRecord{}, err
	}

	if err := tx.Commit(context.Background()); err != nil {
		return model.AccountRecord{}, err
	}
	return account, nil
}

func (s *PostgresStore) GetAccountByUsername(username string) (model.AccountRecord, model.User, error) {
	var account model.AccountRecord
	var user model.User
	err := s.pool.QueryRow(context.Background(), `
		SELECT u.id, u.password_hash, u.trust_tier, u.account_status,
		       u.account_role, u.recovery_key_hash, u.recovery_key_hint, u.created_at,
		       p.username, p.email, p.display_name, p.status, p.bio,
		       p.avatar_url, p.presence, p.nickname, p.position
		FROM users u
		JOIN profiles p ON p.user_id = u.id
		WHERE p.username = $1
	`, strings.ToLower(strings.TrimSpace(username))).Scan(
		&account.ID, &account.PasswordHash, &account.TrustTier, &account.AccountStatus,
		&account.AccountRole, &account.RecoveryKeyHash, &account.RecoveryKeyHint, &account.CreatedAt,
		&user.Username, &user.Email, &user.DisplayName, &user.Status, &user.Bio,
		&user.AvatarURL, &user.Presence, &user.Nickname, &user.Position,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.AccountRecord{}, model.User{}, ErrNotFound
	}
	user.ID = account.ID
	user.CreatedAt = account.CreatedAt
	return account, user, err
}

func (s *PostgresStore) GetAccountByID(userID string) (model.AccountRecord, error) {
	var account model.AccountRecord
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, password_hash, trust_tier, account_status,
		       account_role, recovery_key_hash, recovery_key_hint, created_at
		FROM users WHERE id = $1
	`, userID).Scan(
		&account.ID, &account.PasswordHash, &account.TrustTier, &account.AccountStatus,
		&account.AccountRole, &account.RecoveryKeyHash, &account.RecoveryKeyHint, &account.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.AccountRecord{}, ErrNotFound
	}
	return account, err
}

func (s *PostgresStore) ActivateAccount(userID string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE users SET account_status = $2 WHERE id = $1
	`, userID, model.AccountStatusActive)
	return err
}

func (s *PostgresStore) SetAccountRole(username, role string) error {
	role = strings.ToLower(strings.TrimSpace(role))
	switch role {
	case model.AccountRoleUser, model.AccountRoleAdmin, model.AccountRoleSuper:
	default:
		return fmt.Errorf("invalid account role: %s", role)
	}
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE users u
		SET account_role = $2
		FROM profiles p
		WHERE p.user_id = u.id AND p.username = $1
	`, strings.ToLower(strings.TrimSpace(username)), role)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) CreateSession(session model.SessionRecord) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO sessions (id, user_id, device_id, refresh_token_hash, expires_at)
		VALUES ($1, $2, $3, $4, $5)
	`, session.ID, session.UserID, session.DeviceID, session.RefreshTokenHash, session.ExpiresAt)
	return err
}

func (s *PostgresStore) FindSessionByID(sessionID string) (model.SessionRecord, error) {
	var session model.SessionRecord
	var revokedAt *time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, user_id, device_id, refresh_token_hash, expires_at, revoked_at
		FROM sessions
		WHERE id = $1 AND revoked_at IS NULL AND expires_at > NOW()
	`, sessionID).Scan(
		&session.ID, &session.UserID, &session.DeviceID, &session.RefreshTokenHash,
		&session.ExpiresAt, &revokedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.SessionRecord{}, ErrNotFound
	}
	session.RevokedAt = revokedAt
	return session, err
}

func (s *PostgresStore) FindSessionByRefreshHash(hash []byte) (model.SessionRecord, error) {
	var session model.SessionRecord
	var revokedAt *time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, user_id, device_id, refresh_token_hash, expires_at, revoked_at
		FROM sessions
		WHERE refresh_token_hash = $1 AND revoked_at IS NULL AND expires_at > NOW()
	`, hash).Scan(
		&session.ID, &session.UserID, &session.DeviceID, &session.RefreshTokenHash,
		&session.ExpiresAt, &revokedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.SessionRecord{}, ErrNotFound
	}
	session.RevokedAt = revokedAt
	return session, err
}

func (s *PostgresStore) RenewSession(sessionID string, expiresAt time.Time, resolvedDeviceID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE sessions
		SET expires_at = $2,
		    device_id = COALESCE(NULLIF($3, ''), device_id)
		WHERE id = $1 AND revoked_at IS NULL AND expires_at > NOW()
	`, sessionID, expiresAt, resolvedDeviceID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) RotateSession(oldSessionID, newSessionID string, newRefreshHash []byte, expiresAt time.Time, resolvedDeviceID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		WITH rotated AS (
			UPDATE sessions
			SET revoked_at = NOW(), replaced_by = $2
			WHERE id = $1 AND revoked_at IS NULL AND expires_at > NOW()
			RETURNING user_id, COALESCE(NULLIF($5, ''), device_id) AS device_id
		)
		INSERT INTO sessions (id, user_id, device_id, refresh_token_hash, expires_at)
		SELECT $2, user_id, device_id, $3, $4
		FROM rotated
	`, oldSessionID, newSessionID, newRefreshHash, expiresAt, resolvedDeviceID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) RevokeSession(sessionID string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE sessions SET revoked_at = NOW() WHERE id = $1 AND revoked_at IS NULL
	`, sessionID)
	return err
}

func (s *PostgresStore) RevokeUserSessions(userID string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE sessions SET revoked_at = NOW()
		WHERE user_id = $1 AND revoked_at IS NULL
	`, userID)
	return err
}

func (s *PostgresStore) SetPasswordHash(userID, passwordHash string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE users SET password_hash = $2 WHERE id = $1
	`, userID, passwordHash)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) SetRecoveryKey(userID, hash, hint string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE users SET recovery_key_hash = $2, recovery_key_hint = $3 WHERE id = $1
	`, userID, hash, hint)
	return err
}

func (s *PostgresStore) ClearRecoveryKey(userID string) error {
	return s.SetRecoveryKey(userID, "", "")
}

func (s *PostgresStore) VerifyRecoveryKeyHash(userID, hash string) (bool, error) {
	var stored string
	err := s.pool.QueryRow(context.Background(), `
		SELECT recovery_key_hash FROM users WHERE id = $1
	`, userID).Scan(&stored)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	return stored != "" && stored == hash, nil
}

func (s *PostgresStore) FindAccountIDByRecoveryHash(hash string) (string, error) {
	var userID string
	err := s.pool.QueryRow(context.Background(), `
		SELECT id FROM users WHERE recovery_key_hash = $1
	`, hash).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return userID, err
}

func (s *PostgresStore) CreateRecoveryTicket(ticket model.RecoveryTicketRecord) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO recovery_tickets (id, user_id, token_hash, purpose, expires_at)
		VALUES ($1, $2, $3, $4, $5)
	`, ticket.ID, ticket.UserID, ticket.TokenHash, ticket.Purpose, ticket.ExpiresAt)
	return err
}

func (s *PostgresStore) ConsumeRecoveryTicket(tokenHash string) (model.RecoveryTicketRecord, error) {
	var ticket model.RecoveryTicketRecord
	err := s.pool.QueryRow(context.Background(), `
		UPDATE recovery_tickets
		SET consumed_at = NOW()
		WHERE token_hash = $1 AND consumed_at IS NULL AND expires_at > NOW()
		RETURNING id, user_id, token_hash, purpose, expires_at
	`, tokenHash).Scan(
		&ticket.ID, &ticket.UserID, &ticket.TokenHash, &ticket.Purpose, &ticket.ExpiresAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.RecoveryTicketRecord{}, ErrNotFound
	}
	return ticket, err
}

func (s *PostgresStore) CreateTrustedRecoveryChallenge(challenge model.TrustedRecoveryChallengeRecord) error {
	var userID any
	if challenge.UserID != "" {
		userID = challenge.UserID
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO trusted_recovery_challenges (id, user_id, expires_at)
		VALUES ($1, $2, $3)
	`, challenge.ID, userID, challenge.ExpiresAt)
	return err
}

func (s *PostgresStore) GetTrustedRecoveryChallenge(id string) (model.TrustedRecoveryChallengeRecord, error) {
	var challenge model.TrustedRecoveryChallengeRecord
	var userID *string
	var approvedAt *time.Time
	var approvedBy *string
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, user_id, expires_at, approved_at, approved_by_device_id, ticket_issued
		FROM trusted_recovery_challenges
		WHERE id = $1
	`, id).Scan(
		&challenge.ID, &userID, &challenge.ExpiresAt, &approvedAt,
		&approvedBy, &challenge.TicketIssued,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.TrustedRecoveryChallengeRecord{}, ErrNotFound
	}
	if err != nil {
		return model.TrustedRecoveryChallengeRecord{}, err
	}
	if userID != nil {
		challenge.UserID = *userID
	}
	challenge.ApprovedAt = approvedAt
	if approvedBy != nil {
		challenge.ApprovedByDeviceID = *approvedBy
	}
	return challenge, nil
}

func (s *PostgresStore) ListPendingTrustedRecoveryChallenges(userID string) ([]model.TrustedRecoveryChallengeRecord, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, user_id, expires_at, approved_at, approved_by_device_id, ticket_issued
		FROM trusted_recovery_challenges
		WHERE user_id = $1 AND approved_at IS NULL AND expires_at > NOW()
		ORDER BY created_at ASC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.TrustedRecoveryChallengeRecord, 0)
	for rows.Next() {
		var challenge model.TrustedRecoveryChallengeRecord
		var storedUserID *string
		var approvedAt *time.Time
		var approvedBy *string
		if err := rows.Scan(
			&challenge.ID, &storedUserID, &challenge.ExpiresAt, &approvedAt,
			&approvedBy, &challenge.TicketIssued,
		); err != nil {
			return nil, err
		}
		if storedUserID != nil {
			challenge.UserID = *storedUserID
		}
		challenge.ApprovedAt = approvedAt
		if approvedBy != nil {
			challenge.ApprovedByDeviceID = *approvedBy
		}
		out = append(out, challenge)
	}
	return out, rows.Err()
}

func (s *PostgresStore) ApproveTrustedRecoveryChallenge(id, userID, deviceID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE trusted_recovery_challenges
		SET approved_at = NOW(), approved_by_device_id = $3
		WHERE id = $1 AND user_id = $2 AND approved_at IS NULL AND expires_at > NOW()
	`, id, userID, deviceID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		// Already approved by this user is idempotent.
		var approvedUser string
		scanErr := s.pool.QueryRow(context.Background(), `
			SELECT user_id::text FROM trusted_recovery_challenges
			WHERE id = $1 AND user_id = $2 AND approved_at IS NOT NULL
		`, id, userID).Scan(&approvedUser)
		if scanErr == nil {
			return nil
		}
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) ClaimTrustedRecoveryTicket(id string, ticket model.RecoveryTicketRecord) (model.TrustedRecoveryChallengeRecord, error) {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return model.TrustedRecoveryChallengeRecord{}, err
	}
	defer tx.Rollback(context.Background())

	var challenge model.TrustedRecoveryChallengeRecord
	var approvedBy *string
	err = tx.QueryRow(context.Background(), `
		UPDATE trusted_recovery_challenges
		SET ticket_issued = TRUE
		WHERE id = $1
		  AND user_id IS NOT NULL
		  AND approved_at IS NOT NULL
		  AND expires_at > NOW()
		  AND ticket_issued = FALSE
		RETURNING id, user_id, expires_at, approved_at, approved_by_device_id, ticket_issued
	`, id).Scan(
		&challenge.ID, &challenge.UserID, &challenge.ExpiresAt, &challenge.ApprovedAt,
		&approvedBy, &challenge.TicketIssued,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.TrustedRecoveryChallengeRecord{}, ErrNotFound
	}
	if err != nil {
		return model.TrustedRecoveryChallengeRecord{}, err
	}
	if approvedBy != nil {
		challenge.ApprovedByDeviceID = *approvedBy
	}

	_, err = tx.Exec(context.Background(), `
		INSERT INTO recovery_tickets (id, user_id, token_hash, purpose, expires_at)
		VALUES ($1, $2, $3, $4, $5)
	`, ticket.ID, ticket.UserID, ticket.TokenHash, ticket.Purpose, ticket.ExpiresAt)
	if err != nil {
		return model.TrustedRecoveryChallengeRecord{}, err
	}
	if err := tx.Commit(context.Background()); err != nil {
		return model.TrustedRecoveryChallengeRecord{}, err
	}
	return challenge, nil
}

func (s *PostgresStore) SaveWebAuthnSession(session model.WebAuthnSessionRecord) error {
	var userID any
	if session.UserID != "" {
		userID = session.UserID
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO webauthn_sessions (id, user_id, username, purpose, session_json, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6)
	`, session.ID, userID, session.Username, session.Purpose, session.Session, session.ExpiresAt)
	return err
}

func (s *PostgresStore) TakeWebAuthnSession(id string) (model.WebAuthnSessionRecord, error) {
	var session model.WebAuthnSessionRecord
	var userID *string
	err := s.pool.QueryRow(context.Background(), `
		DELETE FROM webauthn_sessions
		WHERE id = $1 AND expires_at > NOW()
		RETURNING id, user_id, username, purpose, session_json, expires_at
	`, id).Scan(&session.ID, &userID, &session.Username, &session.Purpose, &session.Session, &session.ExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.WebAuthnSessionRecord{}, ErrNotFound
	}
	if err != nil {
		return model.WebAuthnSessionRecord{}, err
	}
	if userID != nil {
		session.UserID = *userID
	}
	return session, nil
}

func (s *PostgresStore) SaveWebAuthnCredential(credential model.WebAuthnCredentialRecord) error {
	if credential.ID == "" {
		credential.ID = uuid.NewString()
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO webauthn_credentials (
			id, user_id, device_id, credential_id, public_key, sign_count,
			attestation_type, credential_json, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())
	`, credential.ID, credential.UserID, credential.DeviceID, credential.CredentialID,
		credential.PublicKey, credential.SignCount, credential.AttestationType, credential.CredentialJSON)
	return err
}

func (s *PostgresStore) ListWebAuthnCredentials(userID string) ([]model.WebAuthnCredentialRecord, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, user_id, device_id, credential_id, public_key, sign_count,
		       COALESCE(attestation_type, 'none'), COALESCE(credential_json, 'null'::jsonb),
		       created_at, last_used_at
		FROM webauthn_credentials
		WHERE user_id = $1
		ORDER BY created_at ASC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.WebAuthnCredentialRecord, 0)
	for rows.Next() {
		var cred model.WebAuthnCredentialRecord
		if err := rows.Scan(
			&cred.ID, &cred.UserID, &cred.DeviceID, &cred.CredentialID, &cred.PublicKey, &cred.SignCount,
			&cred.AttestationType, &cred.CredentialJSON, &cred.CreatedAt, &cred.LastUsedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, cred)
	}
	return out, rows.Err()
}

func (s *PostgresStore) CountWebAuthnCredentials(userID string) (int, error) {
	var count int
	err := s.pool.QueryRow(context.Background(), `
		SELECT COUNT(*) FROM webauthn_credentials WHERE user_id = $1
	`, userID).Scan(&count)
	return count, err
}

func (s *PostgresStore) UpdateWebAuthnCredential(credential model.WebAuthnCredentialRecord) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE webauthn_credentials
		SET sign_count = $3, credential_json = $4, last_used_at = NOW()
		WHERE user_id = $1 AND credential_id = $2
	`, credential.UserID, credential.CredentialID, credential.SignCount, credential.CredentialJSON)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) CountActiveDevices(accountID string) (int, error) {
	var count int
	err := s.pool.QueryRow(context.Background(), `
		SELECT COUNT(*) FROM devices
		WHERE account_id = $1 AND device_status = 'active'
	`, accountID).Scan(&count)
	return count, err
}

func (s *PostgresStore) IsActiveDevice(deviceID, accountID string) (bool, error) {
	if strings.TrimSpace(deviceID) == "" {
		return false, nil
	}
	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM devices
			WHERE device_id = $1 AND account_id = $2 AND device_status = 'active'
		)
	`, deviceID, accountID).Scan(&exists)
	return exists, err
}

func (s *PostgresStore) SetDeviceStatus(deviceID, accountID, status string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE devices SET device_status = $3, updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID, status)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) ConfirmDevice(deviceID, accountID, confirmingDeviceID string) error {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())

	var confirmingStatus string
	err = tx.QueryRow(context.Background(), `
		SELECT device_status FROM devices
		WHERE device_id = $1 AND account_id = $2
	`, confirmingDeviceID, accountID).Scan(&confirmingStatus)
	if err != nil {
		return ErrForbidden
	}
	if confirmingStatus != model.DeviceStatusActive {
		return ErrForbidden
	}

	tag, err := tx.Exec(context.Background(), `
		UPDATE devices
		SET device_status = $4, confirmed_at = NOW(), confirmed_by_device_id = $5, updated_at = NOW()
		WHERE device_id = $1 AND account_id = $2 AND device_status = $3
	`, deviceID, accountID, model.DeviceStatusPending, model.DeviceStatusActive, confirmingDeviceID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(context.Background())
}

func (s *PostgresStore) RevokeDevice(deviceID, accountID string) error {
	if err := s.PurgeDeviceKeys(deviceID, accountID); err != nil && !errors.Is(err, ErrNotFound) {
		return err
	}
	// Drop undelivered mail so fan-out no longer targets a dead mailbox.
	if err := s.PurgeDeviceMailbox(deviceID, accountID); err != nil && !errors.Is(err, ErrNotFound) {
		return err
	}
	// Zombie push tokens die with the device (WA/Signal lifecycle).
	_ = s.RevokePushTokensForDevice(accountID, deviceID)
	if err := s.SetDeviceStatus(deviceID, accountID, model.DeviceStatusRevoked); err != nil {
		return err
	}
	// Never leave the account with only pending devices: promote the oldest pending
	// when the last active was revoked (fixes multi-device ghost-revoke deadlocks).
	return s.promoteOldestPendingIfNoActive(accountID)
}

func (s *PostgresStore) promoteOldestPendingIfNoActive(accountID string) error {
	var activeCount int
	if err := s.pool.QueryRow(context.Background(), `
		SELECT COUNT(*) FROM devices
		WHERE account_id = $1 AND device_status = $2
	`, accountID, model.DeviceStatusActive).Scan(&activeCount); err != nil {
		return err
	}
	if activeCount > 0 {
		return nil
	}
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE devices
		SET device_status = $2, updated_at = NOW()
		WHERE device_id = (
			SELECT device_id FROM devices
			WHERE account_id = $1 AND device_status = $3
			ORDER BY created_at ASC NULLS LAST, device_id ASC
			LIMIT 1
		)
	`, accountID, model.DeviceStatusActive, model.DeviceStatusPending)
	if err != nil {
		return err
	}
	_ = tag
	return nil
}

func (s *PostgresStore) GetPendingDeviceCode(deviceID, accountID string) (string, time.Time, error) {
	var status string
	err := s.pool.QueryRow(context.Background(), `
		SELECT device_status FROM devices WHERE device_id = $1 AND account_id = $2
	`, deviceID, accountID).Scan(&status)
	if err != nil {
		return "", time.Time{}, ErrNotFound
	}
	if status != model.DeviceStatusPending {
		return "", time.Time{}, ErrForbidden
	}
	expires := NowUTC().Add(15 * time.Minute)
	return deviceConfirmCode(deviceID, accountID), expires, nil
}

func deviceConfirmCode(deviceID, accountID string) string {
	mac := hmac.New(sha256.New, []byte(confirmCodeSecret()))
	mac.Write([]byte(deviceID + ":" + accountID))
	sum := mac.Sum(nil)
	return strings.ToUpper(hex.EncodeToString(sum[:4]))
}

func confirmCodeSecret() string {
	if v := os.Getenv("DEVICE_CONFIRM_SECRET"); v != "" {
		return v
	}
	return "glagolitsa-device-confirm-dev"
}

func (s *PostgresStore) CreatePowChallenge(challengeID, challenge, clientIPHash string, difficulty int, expiresAt time.Time) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO registration_challenges (id, challenge, difficulty, client_ip_hash, expires_at)
		VALUES ($1, $2, $3, $4, $5)
	`, challengeID, challenge, difficulty, clientIPHash, expiresAt)
	return err
}

func (s *PostgresStore) ConsumePowChallenge(challengeID, solution string) (bool, error) {
	var challenge string
	var difficulty int
	err := s.pool.QueryRow(context.Background(), `
		SELECT challenge, difficulty FROM registration_challenges
		WHERE id = $1 AND solution IS NULL AND expires_at > NOW()
	`, challengeID).Scan(&challenge, &difficulty)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	if !verifyPowSolution(challenge, solution, difficulty) {
		return false, nil
	}
	_, err = s.pool.Exec(context.Background(), `
		UPDATE registration_challenges SET solution = $2 WHERE id = $1
	`, challengeID, solution)
	return true, err
}

func verifyPowSolution(challenge, solution string, difficulty int) bool {
	if solution == "" {
		return false
	}
	sum := sha256.Sum256([]byte(challenge + ":" + solution))
	return leadingZeroBits(sum[:]) >= difficulty
}

func leadingZeroBits(data []byte) int {
	bits := 0
	for _, b := range data {
		if b == 0 {
			bits += 8
			continue
		}
		for i := 7; i >= 0; i-- {
			if b&(1<<i) == 0 {
				bits++
			} else {
				return bits
			}
		}
	}
	return bits
}

func (s *PostgresStore) RecordAudit(event model.AuditEventInput) error {
	meta, _ := json.Marshal(event.Metadata)
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO audit_events (id, event_type, user_id, device_id, coarse_ip_hash, risk_level, metadata)
		VALUES ($1, $2, NULLIF($3, '')::uuid, NULLIF($4, ''), $5, $6, $7)
	`, uuid.NewString(), event.EventType, nullUUID(event.UserID), event.DeviceID,
		event.CoarseIPHash, event.RiskLevel, meta)
	return err
}

func nullUUID(id string) any {
	if id == "" {
		return nil
	}
	return id
}

func (s *PostgresStore) IncrementRateLimitHits(userID string) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO account_reputation (user_id, rate_limit_hits_7d, updated_at)
		VALUES ($1, 1, NOW())
		ON CONFLICT (user_id) DO UPDATE
		SET rate_limit_hits_7d = account_reputation.rate_limit_hits_7d + 1,
		    updated_at = NOW()
	`, userID)
	return err
}

func coarseIPHash(ip string) string {
	if ip == "" {
		return ""
	}
	sum := sha256.Sum256([]byte("glagolitsa-ip:" + ip))
	return base64.RawURLEncoding.EncodeToString(sum[:8])
}
