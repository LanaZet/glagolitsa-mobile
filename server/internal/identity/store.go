// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"time"

	"glagolitsa/server/internal/model"
)

// Store — контракт Identity Service (аккаунты, сессии, recovery, abuse, audit).
type Store interface {
	CreateAccount(user model.User, passwordHash string) (model.AccountRecord, error)
	GetAccountByUsername(username string) (model.AccountRecord, model.User, error)
	GetAccountByID(userID string) (model.AccountRecord, error)
	ActivateAccount(userID string) error
	SetAccountRole(username, role string) error

	CreateSession(session model.SessionRecord) error
	FindSessionByID(sessionID string) (model.SessionRecord, error)
	FindSessionByRefreshHash(hash []byte) (model.SessionRecord, error)
	RenewSession(sessionID string, expiresAt time.Time, deviceID string) error
	RotateSession(oldSessionID, newSessionID string, newRefreshHash []byte, expiresAt time.Time, deviceID string) error
	RevokeSession(sessionID string) error
	RevokeUserSessions(userID string) error
	SetPasswordHash(userID, passwordHash string) error

	SetRecoveryKey(userID, hash, hint string) error
	ClearRecoveryKey(userID string) error
	VerifyRecoveryKeyHash(userID string, hash string) (bool, error)
	FindAccountIDByRecoveryHash(hash string) (string, error)
	CreateRecoveryTicket(ticket model.RecoveryTicketRecord) error
	ConsumeRecoveryTicket(tokenHash string) (model.RecoveryTicketRecord, error)
	CreateTrustedRecoveryChallenge(challenge model.TrustedRecoveryChallengeRecord) error
	GetTrustedRecoveryChallenge(id string) (model.TrustedRecoveryChallengeRecord, error)
	ListPendingTrustedRecoveryChallenges(userID string) ([]model.TrustedRecoveryChallengeRecord, error)
	ApproveTrustedRecoveryChallenge(id, userID, deviceID string) error
	ClaimTrustedRecoveryTicket(id string, ticket model.RecoveryTicketRecord) (model.TrustedRecoveryChallengeRecord, error)
	SaveWebAuthnSession(session model.WebAuthnSessionRecord) error
	TakeWebAuthnSession(id string) (model.WebAuthnSessionRecord, error)
	SaveWebAuthnCredential(credential model.WebAuthnCredentialRecord) error
	ListWebAuthnCredentials(userID string) ([]model.WebAuthnCredentialRecord, error)
	CountWebAuthnCredentials(userID string) (int, error)
	UpdateWebAuthnCredential(credential model.WebAuthnCredentialRecord) error

	CountActiveDevices(accountID string) (int, error)
	IsActiveDevice(deviceID, accountID string) (bool, error)
	SetDeviceStatus(deviceID, accountID, status string) error
	ConfirmDevice(deviceID, accountID, confirmingDeviceID string) error
	RevokeDevice(deviceID, accountID string) error
	GetPendingDeviceCode(deviceID, accountID string) (string, time.Time, error)

	CreatePowChallenge(challengeID, challenge, clientIPHash string, difficulty int, expiresAt time.Time) error
	ConsumePowChallenge(challengeID, solution string) (bool, error)

	RecordAudit(event model.AuditEventInput) error
	IncrementRateLimitHits(userID string) error
}
