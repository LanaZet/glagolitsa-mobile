// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) CreateAccount(user model.User, passwordHash string) (model.AccountRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	username := strings.ToLower(strings.TrimSpace(user.Username))
	if username == "" {
		return model.AccountRecord{}, errors.New("username is required")
	}
	if _, exists := s.usersByUsername[username]; exists {
		return model.AccountRecord{}, ErrAlreadyExists
	}
	email := strings.ToLower(strings.TrimSpace(user.Email))
	if email != "" {
		if _, exists := s.usersByEmail[email]; exists {
			return model.AccountRecord{}, ErrAlreadyExists
		}
	}

	if user.ID == "" {
		user.ID = uuid.NewString()
	}
	if user.CreatedAt.IsZero() {
		user.CreatedAt = NowUTC()
	}
	user.Username = username
	user.Email = email

	account := model.AccountRecord{
		ID:            user.ID,
		PasswordHash:  passwordHash,
		TrustTier:     model.TrustTierNew,
		AccountStatus: model.AccountStatusActive,
		AccountRole:   model.AccountRoleUser,
		CreatedAt:     user.CreatedAt,
	}
	s.users[user.ID] = &userRecord{
		User:          user,
		PasswordHash:  passwordHash,
		TrustTier:     model.TrustTierNew,
		AccountStatus: model.AccountStatusActive,
		AccountRole:   model.AccountRoleUser,
	}
	s.usersByUsername[username] = user.ID
	if email != "" {
		s.usersByEmail[email] = user.ID
	}
	return account, nil
}

func (s *MemoryStore) GetAccountByUsername(username string) (model.AccountRecord, model.User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	id, ok := s.usersByUsername[strings.ToLower(strings.TrimSpace(username))]
	if !ok {
		return model.AccountRecord{}, model.User{}, ErrNotFound
	}
	record := s.users[id]
	return memoryAccountFromRecord(record), record.User, nil
}

func (s *MemoryStore) GetAccountByID(userID string) (model.AccountRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	record, ok := s.users[userID]
	if !ok {
		return model.AccountRecord{}, ErrNotFound
	}
	return memoryAccountFromRecord(record), nil
}

func memoryAccountFromRecord(record *userRecord) model.AccountRecord {
	tier := record.TrustTier
	if tier == "" {
		tier = model.TrustTierNew
	}
	status := record.AccountStatus
	if status == "" {
		status = model.AccountStatusActive
	}
	role := record.AccountRole
	if role == "" {
		role = model.AccountRoleUser
	}
	return model.AccountRecord{
		ID:              record.User.ID,
		PasswordHash:    record.PasswordHash,
		TrustTier:       tier,
		AccountStatus:   status,
		AccountRole:     role,
		RecoveryKeyHash: record.RecoveryKeyHash,
		RecoveryKeyHint: record.RecoveryKeyHint,
		CreatedAt:       record.User.CreatedAt,
	}
}

func (s *MemoryStore) ActivateAccount(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return ErrNotFound
	}
	record.AccountStatus = model.AccountStatusActive
	return nil
}

func (s *MemoryStore) SetAccountStatus(userID, status string) error {
	switch status {
	case model.AccountStatusActive, model.AccountStatusInactive:
	default:
		return errors.New("invalid account status")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return ErrNotFound
	}
	record.AccountStatus = status
	return nil
}

func (s *MemoryStore) SetAccountRole(username, role string) error {
	role = strings.ToLower(strings.TrimSpace(role))
	switch role {
	case model.AccountRoleUser, model.AccountRoleAdmin, model.AccountRoleSuper:
	default:
		return errors.New("invalid account role")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	id, ok := s.usersByUsername[strings.ToLower(strings.TrimSpace(username))]
	if !ok {
		return ErrNotFound
	}
	s.users[id].AccountRole = role
	return nil
}

func (s *MemoryStore) CreateSession(session model.SessionRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.sessions == nil {
		s.sessions = make(map[string]*memSessionRecord)
		s.sessionsByRefresh = make(map[string]string)
	}
	s.sessions[session.ID] = &memSessionRecord{SessionRecord: session}
	s.sessionsByRefresh[hex.EncodeToString(session.RefreshTokenHash)] = session.ID
	return nil
}

func (s *MemoryStore) FindSessionByID(sessionID string) (model.SessionRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec := s.sessions[sessionID]
	if rec == nil || rec.revoked || rec.ExpiresAt.Before(NowUTC()) {
		return model.SessionRecord{}, ErrNotFound
	}
	return rec.SessionRecord, nil
}

func (s *MemoryStore) FindSessionByRefreshHash(hash []byte) (model.SessionRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	sessionID, ok := s.sessionsByRefresh[hex.EncodeToString(hash)]
	if !ok {
		return model.SessionRecord{}, ErrNotFound
	}
	rec := s.sessions[sessionID]
	if rec == nil || rec.revoked || rec.ExpiresAt.Before(NowUTC()) {
		return model.SessionRecord{}, ErrNotFound
	}
	return rec.SessionRecord, nil
}

func (s *MemoryStore) RenewSession(sessionID string, expiresAt time.Time, resolvedDeviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.sessions[sessionID]
	if !ok || rec.revoked || rec.ExpiresAt.Before(NowUTC()) {
		return ErrNotFound
	}
	if resolvedDeviceID != "" {
		rec.DeviceID = resolvedDeviceID
	}
	rec.ExpiresAt = expiresAt
	return nil
}

func (s *MemoryStore) RotateSession(oldSessionID, newSessionID string, newRefreshHash []byte, expiresAt time.Time, resolvedDeviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	old, ok := s.sessions[oldSessionID]
	if !ok || old.revoked || old.ExpiresAt.Before(NowUTC()) {
		return ErrNotFound
	}
	old.revoked = true
	old.replacedBy = newSessionID
	delete(s.sessionsByRefresh, hex.EncodeToString(old.RefreshTokenHash))

	if resolvedDeviceID == "" {
		resolvedDeviceID = old.DeviceID
	}
	newSession := model.SessionRecord{
		ID:               newSessionID,
		UserID:           old.UserID,
		DeviceID:         resolvedDeviceID,
		RefreshTokenHash: newRefreshHash,
		ExpiresAt:        expiresAt,
	}
	s.sessions[newSessionID] = &memSessionRecord{SessionRecord: newSession}
	s.sessionsByRefresh[hex.EncodeToString(newRefreshHash)] = newSessionID
	return nil
}

func (s *MemoryStore) RevokeSession(sessionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.sessions[sessionID]
	if !ok || rec.revoked {
		return ErrNotFound
	}
	rec.revoked = true
	delete(s.sessionsByRefresh, hex.EncodeToString(rec.RefreshTokenHash))
	return nil
}

func (s *MemoryStore) RevokeUserSessions(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, rec := range s.sessions {
		if rec == nil || rec.revoked || rec.UserID != userID {
			continue
		}
		rec.revoked = true
		delete(s.sessionsByRefresh, hex.EncodeToString(rec.RefreshTokenHash))
	}
	return nil
}

func (s *MemoryStore) SetPasswordHash(userID, passwordHash string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return ErrNotFound
	}
	record.PasswordHash = passwordHash
	return nil
}

func (s *MemoryStore) SetRecoveryKey(userID, hash, hint string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return ErrNotFound
	}
	record.RecoveryKeyHash = hash
	record.RecoveryKeyHint = hint
	return nil
}

func (s *MemoryStore) ClearRecoveryKey(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return ErrNotFound
	}
	record.RecoveryKeyHash = ""
	record.RecoveryKeyHint = ""
	return nil
}

func (s *MemoryStore) VerifyRecoveryKeyHash(userID, hash string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	record, ok := s.users[userID]
	if !ok {
		return false, ErrNotFound
	}
	return record.RecoveryKeyHash != "" && record.RecoveryKeyHash == hash, nil
}

func (s *MemoryStore) FindAccountIDByRecoveryHash(hash string) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for id, record := range s.users {
		if record.RecoveryKeyHash != "" && record.RecoveryKeyHash == hash {
			return id, nil
		}
	}
	return "", ErrNotFound
}

func (s *MemoryStore) CreateRecoveryTicket(ticket model.RecoveryTicketRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.recoveryTickets == nil {
		s.recoveryTickets = make(map[string]*model.RecoveryTicketRecord)
	}
	copy := ticket
	s.recoveryTickets[ticket.TokenHash] = &copy
	return nil
}

func (s *MemoryStore) ConsumeRecoveryTicket(tokenHash string) (model.RecoveryTicketRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	ticket, ok := s.recoveryTickets[tokenHash]
	if !ok || ticket.ExpiresAt.Before(NowUTC()) {
		return model.RecoveryTicketRecord{}, ErrNotFound
	}
	consumed := *ticket
	delete(s.recoveryTickets, tokenHash)
	return consumed, nil
}

func (s *MemoryStore) CreateTrustedRecoveryChallenge(challenge model.TrustedRecoveryChallengeRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.trustedRecoveryChallenges == nil {
		s.trustedRecoveryChallenges = make(map[string]*model.TrustedRecoveryChallengeRecord)
	}
	copy := challenge
	s.trustedRecoveryChallenges[challenge.ID] = &copy
	return nil
}

func (s *MemoryStore) GetTrustedRecoveryChallenge(id string) (model.TrustedRecoveryChallengeRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	challenge, ok := s.trustedRecoveryChallenges[id]
	if !ok {
		return model.TrustedRecoveryChallengeRecord{}, ErrNotFound
	}
	return *challenge, nil
}

func (s *MemoryStore) ListPendingTrustedRecoveryChallenges(userID string) ([]model.TrustedRecoveryChallengeRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := NowUTC()
	out := make([]model.TrustedRecoveryChallengeRecord, 0)
	for _, challenge := range s.trustedRecoveryChallenges {
		if challenge.UserID != userID || challenge.ApprovedAt != nil || !challenge.ExpiresAt.After(now) {
			continue
		}
		out = append(out, *challenge)
	}
	return out, nil
}

func (s *MemoryStore) ApproveTrustedRecoveryChallenge(id, userID, deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	challenge, ok := s.trustedRecoveryChallenges[id]
	if !ok || challenge.UserID != userID {
		return ErrNotFound
	}
	if challenge.ApprovedAt != nil {
		return nil
	}
	if !challenge.ExpiresAt.After(NowUTC()) {
		return ErrNotFound
	}
	now := NowUTC()
	challenge.ApprovedAt = &now
	challenge.ApprovedByDeviceID = deviceID
	return nil
}

func (s *MemoryStore) ClaimTrustedRecoveryTicket(id string, ticket model.RecoveryTicketRecord) (model.TrustedRecoveryChallengeRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	challenge, ok := s.trustedRecoveryChallenges[id]
	if !ok || challenge.UserID == "" || challenge.ApprovedAt == nil || challenge.TicketIssued {
		return model.TrustedRecoveryChallengeRecord{}, ErrNotFound
	}
	if !challenge.ExpiresAt.After(NowUTC()) {
		return model.TrustedRecoveryChallengeRecord{}, ErrNotFound
	}
	if s.recoveryTickets == nil {
		s.recoveryTickets = make(map[string]*model.RecoveryTicketRecord)
	}
	copy := ticket
	s.recoveryTickets[ticket.TokenHash] = &copy
	challenge.TicketIssued = true
	return *challenge, nil
}

func (s *MemoryStore) SaveWebAuthnSession(session model.WebAuthnSessionRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.webauthnSessions == nil {
		s.webauthnSessions = make(map[string]*model.WebAuthnSessionRecord)
	}
	copy := session
	s.webauthnSessions[session.ID] = &copy
	return nil
}

func (s *MemoryStore) TakeWebAuthnSession(id string) (model.WebAuthnSessionRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	session, ok := s.webauthnSessions[id]
	if !ok || !session.ExpiresAt.After(NowUTC()) {
		return model.WebAuthnSessionRecord{}, ErrNotFound
	}
	taken := *session
	delete(s.webauthnSessions, id)
	return taken, nil
}

func (s *MemoryStore) SaveWebAuthnCredential(credential model.WebAuthnCredentialRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.webauthnCredentials == nil {
		s.webauthnCredentials = make(map[string][]model.WebAuthnCredentialRecord)
	}
	s.webauthnCredentials[credential.UserID] = append(s.webauthnCredentials[credential.UserID], credential)
	return nil
}

func (s *MemoryStore) ListWebAuthnCredentials(userID string) ([]model.WebAuthnCredentialRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := append([]model.WebAuthnCredentialRecord(nil), s.webauthnCredentials[userID]...)
	return out, nil
}

func (s *MemoryStore) CountWebAuthnCredentials(userID string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.webauthnCredentials[userID]), nil
}

func (s *MemoryStore) UpdateWebAuthnCredential(credential model.WebAuthnCredentialRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	creds := s.webauthnCredentials[credential.UserID]
	for i, existing := range creds {
		if bytesEqual(existing.CredentialID, credential.CredentialID) {
			creds[i] = credential
			s.webauthnCredentials[credential.UserID] = creds
			return nil
		}
	}
	return ErrNotFound
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func (s *MemoryStore) CountActiveDevices(accountID string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	count := 0
	for _, device := range s.devices {
		if device.AccountID != accountID {
			continue
		}
		status := device.DeviceStatus
		if status == "" {
			status = model.DeviceStatusActive
		}
		if status == model.DeviceStatusActive {
			count++
		}
	}
	return count, nil
}

func (s *MemoryStore) IsActiveDevice(deviceID, accountID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if strings.TrimSpace(deviceID) == "" {
		return false, nil
	}
	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return false, nil
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	return status == model.DeviceStatusActive, nil
}

func (s *MemoryStore) SetDeviceStatus(deviceID, accountID, status string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return ErrNotFound
	}
	device.DeviceStatus = status
	return nil
}

func (s *MemoryStore) ConfirmDevice(deviceID, accountID, confirmingDeviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	confirming, ok := s.devices[confirmingDeviceID]
	if !ok || confirming.AccountID != accountID {
		return ErrForbidden
	}
	confirmingStatus := confirming.DeviceStatus
	if confirmingStatus == "" {
		confirmingStatus = model.DeviceStatusActive
	}
	if confirmingStatus != model.DeviceStatusActive {
		return ErrForbidden
	}

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return ErrNotFound
	}
	pendingStatus := device.DeviceStatus
	if pendingStatus == "" {
		pendingStatus = model.DeviceStatusPending
	}
	if pendingStatus != model.DeviceStatusPending {
		return ErrNotFound
	}
	device.DeviceStatus = model.DeviceStatusActive
	return nil
}

func (s *MemoryStore) RevokeDevice(deviceID, accountID string) error {
	_ = s.PurgeDeviceKeys(deviceID, accountID)
	_ = s.PurgeDeviceMailbox(deviceID, accountID)
	_ = s.RevokePushTokensForDevice(accountID, deviceID)
	if err := s.SetDeviceStatus(deviceID, accountID, model.DeviceStatusRevoked); err != nil {
		return err
	}
	return s.promoteOldestPendingIfNoActive(accountID)
}

func (s *MemoryStore) promoteOldestPendingIfNoActive(accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	activeCount := 0
	var oldestPendingID string
	for id, device := range s.devices {
		if device.AccountID != accountID {
			continue
		}
		status := device.DeviceStatus
		if status == "" {
			status = model.DeviceStatusActive
		}
		if status == model.DeviceStatusActive {
			activeCount++
		}
		if status == model.DeviceStatusPending {
			if oldestPendingID == "" || id < oldestPendingID {
				oldestPendingID = id
			}
		}
	}
	if activeCount > 0 || oldestPendingID == "" {
		return nil
	}
	s.devices[oldestPendingID].DeviceStatus = model.DeviceStatusActive
	return nil
}

func (s *MemoryStore) GetPendingDeviceCode(deviceID, accountID string) (string, time.Time, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return "", time.Time{}, ErrNotFound
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	if status != model.DeviceStatusPending {
		return "", time.Time{}, ErrForbidden
	}
	expires := NowUTC().Add(15 * time.Minute)
	return memoryDeviceConfirmCode(deviceID, accountID), expires, nil
}

func memoryDeviceConfirmCode(deviceID, accountID string) string {
	mac := hmac.New(sha256.New, []byte(memoryConfirmCodeSecret()))
	mac.Write([]byte(deviceID + ":" + accountID))
	sum := mac.Sum(nil)
	return strings.ToUpper(hex.EncodeToString(sum[:4]))
}

func memoryConfirmCodeSecret() string {
	if v := os.Getenv("DEVICE_CONFIRM_SECRET"); v != "" {
		return v
	}
	return "glagolitsa-device-confirm-dev"
}

func (s *MemoryStore) CreatePowChallenge(challengeID, challenge, clientIPHash string, difficulty int, expiresAt time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.powChallenges == nil {
		s.powChallenges = make(map[string]*powChallengeRecord)
	}
	s.powChallenges[challengeID] = &powChallengeRecord{
		challengeID: challengeID,
		challenge:   challenge,
		difficulty:  difficulty,
		expiresAt:   expiresAt,
	}
	_ = clientIPHash
	return nil
}

func (s *MemoryStore) ConsumePowChallenge(challengeID, solution string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.powChallenges[challengeID]
	if !ok || rec.solution != "" || rec.expiresAt.Before(NowUTC()) {
		return false, ErrNotFound
	}
	if !memoryVerifyPowSolution(rec.challenge, solution, rec.difficulty) {
		return false, nil
	}
	rec.solution = solution
	return true, nil
}

func memoryVerifyPowSolution(challenge, solution string, difficulty int) bool {
	if solution == "" {
		return false
	}
	sum := sha256.Sum256([]byte(challenge + ":" + solution))
	return memoryLeadingZeroBits(sum[:]) >= difficulty
}

func memoryLeadingZeroBits(data []byte) int {
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

func (s *MemoryStore) RecordAudit(event model.AuditEventInput) error {
	_ = event
	return nil
}

func (s *MemoryStore) IncrementRateLimitHits(userID string) error {
	_ = userID
	return nil
}
