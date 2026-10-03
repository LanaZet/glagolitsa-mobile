// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"crypto/hmac"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

const recoveryFailedMessage = "recovery failed"

func (h *Handler) SetupRecovery(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.RecoverySetupRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	key := auth.NormalizeRecoveryKey(req.RecoveryKey)
	if len(key) < auth.RecoveryKeyMinLength {
		httpx.WriteError(w, http.StatusBadRequest, "recovery_key is too short")
		return
	}

	hash := auth.HashRecoveryKey(key)
	if err := h.Store.SetRecoveryKey(claims.UserID, hash, auth.RecoveryKeyHint(key)); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "recovery.setup",
		UserID:    claims.UserID,
		RiskLevel: "medium",
	})

	httpx.WriteJSON(w, http.StatusOK, model.RecoveryStatusResponse{
		RecoveryKeySet:  true,
		RecoveryKeyHint: auth.RecoveryKeyHint(key),
	})
}

func (h *Handler) RecoveryStatus(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	account, err := h.Store.GetAccountByID(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	deviceCount, err := h.Store.CountActiveDevices(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	passkeyCount, err := h.Store.CountWebAuthnCredentials(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, model.RecoveryStatusResponse{
		RecoveryKeySet:     strings.TrimSpace(account.RecoveryKeyHash) != "",
		RecoveryKeyHint:    account.RecoveryKeyHint,
		TrustedDeviceCount: deviceCount,
		PasskeyReady:       passkeyCount > 0,
	})
}

func (h *Handler) VerifyRecovery(w http.ResponseWriter, r *http.Request) {
	var req model.RecoveryVerifyRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	username := strings.ToLower(strings.TrimSpace(req.Username))
	key := auth.NormalizeRecoveryKey(req.RecoveryKey)
	if username == "" || key == "" {
		httpx.WriteError(w, http.StatusBadRequest, "username and recovery_key are required")
		return
	}

	account, user, lookupErr := h.Store.GetAccountByUsername(username)
	computed := auth.HashRecoveryKey(key)
	stored := auth.DummyRecoveryKeyHash
	if lookupErr == nil && strings.TrimSpace(account.RecoveryKeyHash) != "" {
		stored = account.RecoveryKeyHash
	}
	matched := hmac.Equal([]byte(stored), []byte(computed)) && lookupErr == nil &&
		strings.TrimSpace(account.RecoveryKeyHash) != ""
	if !matched {
		_ = h.Store.RecordAudit(model.AuditEventInput{
			EventType:    "recovery.verify_failed",
			UserID:       account.ID,
			CoarseIPHash: clientIPHash(r),
			RiskLevel:    "medium",
		})
		httpx.WriteError(w, http.StatusUnauthorized, recoveryFailedMessage)
		return
	}

	ticket, err := h.issueRecoveryTicket(account.ID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "recovery.verify",
		UserID:       account.ID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "high",
	})

	httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{
		RecoveryToken: ticket,
		Username:      user.Username,
		ExpiresIn:     int(auth.RecoveryTicketTTL.Seconds()),
		Status:        "ready",
	})
}

func (h *Handler) CompleteRecovery(w http.ResponseWriter, r *http.Request) {
	var req model.RecoveryCompleteRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	rawToken := strings.TrimSpace(req.RecoveryToken)
	if rawToken == "" {
		httpx.WriteError(w, http.StatusBadRequest, "recovery_token is required")
		return
	}
	if err := auth.ValidatePassword(req.NewPassword, auth.DefaultPasswordSettings()); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	ticket, err := h.Store.ConsumeRecoveryTicket(auth.HashRecoveryToken(rawToken))
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, recoveryFailedMessage)
		return
	}

	passwordHash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "could not hash password")
		return
	}
	if err := h.Store.SetPasswordHash(ticket.UserID, passwordHash); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Store.ClearRecoveryKey(ticket.UserID)
	_ = h.Store.RevokeUserSessions(ticket.UserID)

	username, _ := h.Profiles.GetProfile(ticket.UserID)
	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "recovery.complete",
		UserID:       ticket.UserID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "high",
	})

	httpx.WriteJSON(w, http.StatusOK, model.RecoveryCompleteResponse{
		Status:   "ok",
		Username: username.Username,
	})
}

func (h *Handler) StartTrustedRecovery(w http.ResponseWriter, r *http.Request) {
	var req model.TrustedRecoveryStartRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if username == "" {
		httpx.WriteError(w, http.StatusBadRequest, "username is required")
		return
	}

	challengeID := uuid.NewString()
	expiresAt := time.Now().UTC().Add(auth.TrustedRecoveryTTL)
	userID := ""
	if account, _, err := h.Store.GetAccountByUsername(username); err == nil {
		if count, countErr := h.Store.CountActiveDevices(account.ID); countErr == nil && count > 0 {
			userID = account.ID
		}
	}
	if err := h.Store.CreateTrustedRecoveryChallenge(model.TrustedRecoveryChallengeRecord{
		ID:        challengeID,
		UserID:    userID,
		ExpiresAt: expiresAt,
	}); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "recovery.trusted_start",
		UserID:       userID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "medium",
	})

	httpx.WriteJSON(w, http.StatusOK, model.TrustedRecoveryStartResponse{
		ChallengeID: challengeID,
		ExpiresIn:   int(auth.TrustedRecoveryTTL.Seconds()),
		Status:      "pending",
	})
}

func (h *Handler) PendingTrustedRecovery(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	challenges, err := h.Store.ListPendingTrustedRecoveryChallenges(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	items := make([]model.TrustedRecoveryPendingItem, 0, len(challenges))
	for _, challenge := range challenges {
		items = append(items, model.TrustedRecoveryPendingItem{
			ChallengeID: challenge.ID,
			ExpiresAt:   challenge.ExpiresAt.UTC().Format(time.RFC3339),
		})
	}
	httpx.WriteJSON(w, http.StatusOK, model.TrustedRecoveryPendingResponse{Challenges: items})
}

func (h *Handler) ApproveTrustedRecovery(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.TrustedRecoveryApproveRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	challengeID := strings.TrimSpace(req.ChallengeID)
	if challengeID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "challenge_id is required")
		return
	}

	deviceID := strings.TrimSpace(claims.DeviceID)
	activeDevice, err := h.Store.IsActiveDevice(deviceID, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !activeDevice {
		httpx.WriteError(w, http.StatusForbidden, "no trusted device")
		return
	}

	if err := h.Store.ApproveTrustedRecoveryChallenge(challengeID, claims.UserID, deviceID); err != nil {
		status := http.StatusNotFound
		if err != store.ErrNotFound {
			status = http.StatusInternalServerError
		}
		httpx.WriteError(w, status, "challenge not found")
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "recovery.trusted_approve",
		UserID:    claims.UserID,
		DeviceID:  deviceID,
		RiskLevel: "high",
		Metadata:  map[string]any{"challenge_id": challengeID},
	})

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "approved"})
}

func (h *Handler) PollTrustedRecovery(w http.ResponseWriter, r *http.Request) {
	var req model.TrustedRecoveryPollRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	challengeID := strings.TrimSpace(req.ChallengeID)
	if challengeID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "challenge_id is required")
		return
	}

	challenge, err := h.Store.GetTrustedRecoveryChallenge(challengeID)
	if err != nil || challenge.UserID == "" {
		httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{Status: "pending"})
		return
	}
	if !challenge.ExpiresAt.After(time.Now().UTC()) && challenge.ApprovedAt == nil {
		httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{Status: "expired"})
		return
	}
	if challenge.ApprovedAt == nil {
		httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{Status: "pending"})
		return
	}
	if challenge.TicketIssued {
		httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{Status: "used"})
		return
	}

	ticket, err := h.issueTrustedRecoveryTicket(challenge.ID, challenge.UserID)
	if err != nil {
		if err == store.ErrNotFound {
			httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{Status: "used"})
			return
		}
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	user, _ := h.Profiles.GetProfile(challenge.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{
		RecoveryToken: ticket,
		Username:      user.Username,
		ExpiresIn:     int(auth.RecoveryTicketTTL.Seconds()),
		Status:        "approved",
	})
}

func (h *Handler) issueTrustedRecoveryTicket(challengeID, userID string) (string, error) {
	raw, hash, err := auth.NewRecoveryToken()
	if err != nil {
		return "", err
	}
	_, err = h.Store.ClaimTrustedRecoveryTicket(challengeID, model.RecoveryTicketRecord{
		ID:        uuid.NewString(),
		UserID:    userID,
		TokenHash: hash,
		Purpose:   model.RecoveryPurposePassword,
		ExpiresAt: time.Now().UTC().Add(auth.RecoveryTicketTTL),
	})
	if err != nil {
		return "", err
	}
	return raw, nil
}

func (h *Handler) issueRecoveryTicket(userID string) (string, error) {
	raw, hash, err := auth.NewRecoveryToken()
	if err != nil {
		return "", err
	}
	if err := h.Store.CreateRecoveryTicket(model.RecoveryTicketRecord{
		ID:        uuid.NewString(),
		UserID:    userID,
		TokenHash: hash,
		Purpose:   model.RecoveryPurposePassword,
		ExpiresAt: time.Now().UTC().Add(auth.RecoveryTicketTTL),
	}); err != nil {
		return "", err
	}
	return raw, nil
}
