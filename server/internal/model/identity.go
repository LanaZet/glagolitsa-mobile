// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import (
	"encoding/json"
	"time"
)

const (
	TrustTierNew     = "new"
	TrustTierTrusted = "trusted"

	AccountStatusInactive = "inactive"
	AccountStatusActive   = "active"

	AccountRoleUser  = "user"
	AccountRoleAdmin = "admin"
	AccountRoleSuper = "super"

	DeviceStatusPending = "pending"
	DeviceStatusActive  = "active"
	DeviceStatusRevoked = "revoked"
)

type AuthResponse struct {
	Token        string `json:"token"`
	AccessToken  string `json:"access_token,omitempty"`
	RefreshToken string `json:"refresh_token,omitempty"`
	TokenID      string `json:"token_id,omitempty"`
	SessionID    string `json:"session_id,omitempty"`
	ExpiresIn    int    `json:"expires_in,omitempty"`
	AccountRole  string `json:"account_role,omitempty"`
	User         User   `json:"user"`
}

type RegisterRequest struct {
	Username       string `json:"username"`
	Email          string `json:"email,omitempty"`
	Password       string `json:"password"`
	PowChallengeID string `json:"pow_challenge_id,omitempty"`
	PowSolution    string `json:"pow_solution,omitempty"`
	DeviceID       string `json:"device_id,omitempty"`
}

type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
	DeviceID string `json:"device_id,omitempty"`
}

type RefreshRequest struct {
	RefreshToken string `json:"refresh_token"`
	DeviceID     string `json:"device_id,omitempty"`
}

type RecoverySetupRequest struct {
	RecoveryKey string `json:"recovery_key"`
}

type RecoveryVerifyRequest struct {
	Username    string `json:"username"`
	RecoveryKey string `json:"recovery_key"`
}

type RecoveryCompleteRequest struct {
	RecoveryToken string `json:"recovery_token"`
	NewPassword   string `json:"new_password"`
}

type RecoveryTicketResponse struct {
	RecoveryToken string `json:"recovery_token,omitempty"`
	Username      string `json:"username,omitempty"`
	ExpiresIn     int    `json:"expires_in,omitempty"`
	Status        string `json:"status,omitempty"`
}

type RecoveryCompleteResponse struct {
	Status   string `json:"status"`
	Username string `json:"username"`
}

type RecoveryStatusResponse struct {
	RecoveryKeySet     bool   `json:"recovery_key_set"`
	RecoveryKeyHint    string `json:"recovery_key_hint,omitempty"`
	TrustedDeviceCount int    `json:"trusted_device_count"`
	PasskeyReady       bool   `json:"passkey_ready"`
}

type TrustedRecoveryStartRequest struct {
	Username string `json:"username"`
}

type TrustedRecoveryStartResponse struct {
	ChallengeID string `json:"challenge_id"`
	ExpiresIn   int    `json:"expires_in"`
	Status      string `json:"status"`
}

type TrustedRecoveryApproveRequest struct {
	ChallengeID string `json:"challenge_id"`
}

type TrustedRecoveryPollRequest struct {
	ChallengeID string `json:"challenge_id"`
}

type TrustedRecoveryPendingItem struct {
	ChallengeID string `json:"challenge_id"`
	ExpiresAt   string `json:"expires_at"`
}

type TrustedRecoveryPendingResponse struct {
	Challenges []TrustedRecoveryPendingItem `json:"challenges"`
}

type WebAuthnBeginRequest struct {
	Username string `json:"username,omitempty"`
}

type WebAuthnBeginResponse struct {
	SessionID            string   `json:"session_id"`
	RPID                 string   `json:"rp_id"`
	Origin               string   `json:"origin"`
	Challenge            string   `json:"challenge"`
	TimeoutMs            int      `json:"timeout_ms"`
	UserID               string   `json:"user_id,omitempty"`
	UserName             string   `json:"user_name,omitempty"`
	ExcludeCredentialIDs []string `json:"exclude_credential_ids,omitempty"`
	AllowCredentialIDs   []string `json:"allow_credential_ids,omitempty"`
}

type WebAuthnFinishRequest struct {
	SessionID  string          `json:"session_id"`
	Username   string          `json:"username,omitempty"`
	Credential json.RawMessage `json:"credential"`
}

type ConfirmDeviceRequest struct {
	ConfirmingDeviceID string `json:"confirming_device_id"`
}

type PowChallengeResponse struct {
	ChallengeID string `json:"challenge_id"`
	Challenge   string `json:"challenge"`
	Difficulty  int    `json:"difficulty"`
	ExpiresAt   string `json:"expires_at"`
}

type DeviceConfirmCodeResponse struct {
	DeviceID         string `json:"device_id"`
	ConfirmationCode string `json:"confirmation_code"`
	ExpiresAt        string `json:"expires_at"`
}

type AccountRecord struct {
	ID              string
	PasswordHash    string
	TrustTier       string
	AccountStatus   string
	AccountRole     string
	RecoveryKeyHash string
	RecoveryKeyHint string
	CreatedAt       time.Time
}

type SessionRecord struct {
	ID               string
	UserID           string
	DeviceID         string
	RefreshTokenHash []byte
	ExpiresAt        time.Time
	RevokedAt        *time.Time
}

type RecoveryTicketRecord struct {
	ID        string
	UserID    string
	TokenHash string
	Purpose   string
	ExpiresAt time.Time
}

const RecoveryPurposePassword = "password_reset"

type TrustedRecoveryChallengeRecord struct {
	ID                 string
	UserID             string
	ExpiresAt          time.Time
	ApprovedAt         *time.Time
	ApprovedByDeviceID string
	TicketIssued       bool
}

const (
	WebAuthnPurposeRegister = "register"
	WebAuthnPurposeLogin    = "login"
	WebAuthnPurposeRecovery = "recovery"
)

type WebAuthnSessionRecord struct {
	ID        string
	UserID    string
	Username  string
	Purpose   string
	Session   []byte
	ExpiresAt time.Time
}

type WebAuthnCredentialRecord struct {
	ID              string
	UserID          string
	DeviceID        string
	CredentialID    []byte
	PublicKey       []byte
	SignCount       uint32
	AttestationType string
	CredentialJSON  []byte
	CreatedAt       time.Time
	LastUsedAt      *time.Time
}

type AuditEventInput struct {
	EventType    string
	UserID       string
	DeviceID     string
	CoarseIPHash string
	RiskLevel    string
	Metadata     map[string]any
}
