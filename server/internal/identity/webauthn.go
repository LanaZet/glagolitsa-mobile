// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/google/uuid"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
)

func (h *Handler) WebAuthnRegisterBegin(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	user, err := h.passkeyUserByID(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	h.beginCeremony(w, r, user, model.WebAuthnPurposeRegister, claims.UserID, user.name)
}

func (h *Handler) WebAuthnRegisterFinish(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	parsed, session, _, err := h.finishCeremony(r, model.WebAuthnPurposeRegister, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	creation, ok := parsed.(*protocol.ParsedCredentialCreationData)
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid credential")
		return
	}
	user, err := h.passkeyUserByID(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	cred, err := h.Passkeys.CreateCredential(user, session, creation)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "passkey verification failed")
		return
	}
	record, err := recordFromCredential(claims.UserID, claims.DeviceID, cred)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := h.Store.SaveWebAuthnCredential(record); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "passkey.register",
		UserID:    claims.UserID,
		DeviceID:  claims.DeviceID,
		RiskLevel: "medium",
	})
	httpx.WriteJSON(w, http.StatusOK, model.RecoveryStatusResponse{
		PasskeyReady: true,
	})
}

func (h *Handler) WebAuthnLoginBegin(w http.ResponseWriter, r *http.Request) {
	h.beginPublicAssertion(w, r, model.WebAuthnPurposeLogin)
}

func (h *Handler) WebAuthnLoginFinish(w http.ResponseWriter, r *http.Request) {
	user, cred, err := h.finishPublicAssertion(r, model.WebAuthnPurposeLogin)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	account, profile, err := h.Store.GetAccountByUsername(user.name)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if err := h.persistPasskeyUse(account.ID, cred); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	resp, err := h.issueAuthResponse(profile, account, "", r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "passkey.login",
		UserID:       account.ID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "low",
	})
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) WebAuthnRecoveryBegin(w http.ResponseWriter, r *http.Request) {
	h.beginPublicAssertion(w, r, model.WebAuthnPurposeRecovery)
}

func (h *Handler) WebAuthnRecoveryFinish(w http.ResponseWriter, r *http.Request) {
	user, cred, err := h.finishPublicAssertion(r, model.WebAuthnPurposeRecovery)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, recoveryFailedMessage)
		return
	}
	account, profile, err := h.Store.GetAccountByUsername(user.name)
	if err != nil {
		httpx.WriteError(w, http.StatusUnauthorized, recoveryFailedMessage)
		return
	}
	if err := h.persistPasskeyUse(account.ID, cred); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	ticket, err := h.issueRecoveryTicket(account.ID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "passkey.recovery",
		UserID:       account.ID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "high",
	})
	httpx.WriteJSON(w, http.StatusOK, model.RecoveryTicketResponse{
		RecoveryToken: ticket,
		Username:      profile.Username,
		ExpiresIn:     int(auth.RecoveryTicketTTL.Seconds()),
		Status:        "ready",
	})
}

func (h *Handler) beginPublicAssertion(w http.ResponseWriter, r *http.Request, purpose string) {
	var req model.WebAuthnBeginRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if username == "" {
		httpx.WriteError(w, http.StatusBadRequest, "username is required")
		return
	}
	user, userID, err := h.passkeyUserByUsername(username)
	if err != nil {
		// Anti-enumeration: still issue a dummy ceremony that cannot succeed.
		user = passkeyUser{
			id:   passkeyUserID("00000000-0000-0000-0000-000000000000"),
			name: username,
		}
		userID = ""
	}
	h.beginCeremony(w, r, user, purpose, userID, username)
}

func (h *Handler) beginCeremony(w http.ResponseWriter, r *http.Request, user passkeyUser, purpose, userID, username string) {
	if h.Passkeys == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "passkeys are not configured")
		return
	}
	var (
		session *webauthn.SessionData
		err     error
	)
	if purpose == model.WebAuthnPurposeRegister {
		_, session, err = h.Passkeys.BeginRegistration(
			user,
			webauthn.WithConveyancePreference(protocol.PreferNoAttestation),
			webauthn.WithResidentKeyRequirement(protocol.ResidentKeyRequirementPreferred),
		)
	} else {
		if len(user.creds) == 0 {
			user.creds = []webauthn.Credential{{ID: []byte("dummy-passkey-id-not-registered")}}
		}
		_, session, err = h.Passkeys.BeginLogin(user)
	}
	if err != nil {
		slog.Warn("webauthn begin failed", "purpose", purpose, "error", err.Error())
		httpx.WriteError(w, http.StatusInternalServerError, "could not start passkey")
		return
	}
	raw, err := json.Marshal(session)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	sessionID := uuid.NewString()
	if err := h.Store.SaveWebAuthnSession(model.WebAuthnSessionRecord{
		ID:        sessionID,
		UserID:    userID,
		Username:  username,
		Purpose:   purpose,
		Session:   raw,
		ExpiresAt: time.Now().UTC().Add(time.Duration(defaultPasskeyTimeoutMs) * time.Millisecond),
	}); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, model.WebAuthnBeginResponse{
		SessionID:            sessionID,
		RPID:                 session.RelyingPartyID,
		Origin:               h.PasskeyOrigin,
		Challenge:            session.Challenge,
		TimeoutMs:            defaultPasskeyTimeoutMs,
		UserID:               protocol.URLEncodedBase64(user.id).String(),
		UserName:             username,
		ExcludeCredentialIDs: encodedIDs(credentialIDs(user.creds)),
		AllowCredentialIDs:   encodedIDs(session.AllowedCredentialIDs),
	})
	_ = r
}

func (h *Handler) finishCeremony(r *http.Request, purpose, expectedUserID string) (any, webauthn.SessionData, string, error) {
	var req model.WebAuthnFinishRequest
	r.Body = http.MaxBytesReader(nil, r.Body, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return nil, webauthn.SessionData{}, "", err
	}
	stored, err := h.Store.TakeWebAuthnSession(strings.TrimSpace(req.SessionID))
	if err != nil {
		return nil, webauthn.SessionData{}, "", err
	}
	if stored.Purpose != purpose {
		return nil, webauthn.SessionData{}, "", errInvalidPasskeySession
	}
	if expectedUserID != "" && stored.UserID != "" && stored.UserID != expectedUserID {
		return nil, webauthn.SessionData{}, "", errInvalidPasskeySession
	}
	var session webauthn.SessionData
	if err := json.Unmarshal(stored.Session, &session); err != nil {
		return nil, webauthn.SessionData{}, "", err
	}
	username := strings.ToLower(strings.TrimSpace(firstNonEmpty(req.Username, stored.Username)))
	if purpose == model.WebAuthnPurposeRegister {
		parsed, err := protocol.ParseCredentialCreationResponseBody(bytes.NewReader(req.Credential))
		return parsed, session, username, err
	}
	parsed, err := protocol.ParseCredentialRequestResponseBody(bytes.NewReader(req.Credential))
	return parsed, session, username, err
}

func (h *Handler) finishPublicAssertion(r *http.Request, purpose string) (passkeyUser, *webauthn.Credential, error) {
	parsedAny, session, username, err := h.finishCeremony(r, purpose, "")
	if err != nil {
		return passkeyUser{}, nil, err
	}
	parsed, ok := parsedAny.(*protocol.ParsedCredentialAssertionData)
	if !ok {
		return passkeyUser{}, nil, errInvalidPasskeyCredential
	}
	storedUser, _, err := h.passkeyUserByUsername(username)
	if err != nil {
		return passkeyUser{}, nil, err
	}
	cred, err := h.Passkeys.ValidateLogin(storedUser, session, parsed)
	if err != nil {
		return passkeyUser{}, nil, err
	}
	return storedUser, cred, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func (h *Handler) persistPasskeyUse(userID string, cred *webauthn.Credential) error {
	record, err := recordFromCredential(userID, "", cred)
	if err != nil {
		return err
	}
	return h.Store.UpdateWebAuthnCredential(record)
}

func (h *Handler) passkeyUserByID(userID string) (passkeyUser, error) {
	account, err := h.Store.GetAccountByID(userID)
	if err != nil {
		return passkeyUser{}, err
	}
	profile, err := h.Profiles.GetProfile(userID)
	if err != nil {
		return passkeyUser{}, err
	}
	return h.passkeyUser(account.ID, profile.Username)
}

func (h *Handler) passkeyUserByUsername(username string) (passkeyUser, string, error) {
	account, user, err := h.Store.GetAccountByUsername(username)
	if err != nil {
		return passkeyUser{}, "", err
	}
	loaded, err := h.passkeyUser(account.ID, user.Username)
	return loaded, account.ID, err
}

func (h *Handler) passkeyUser(userID, username string) (passkeyUser, error) {
	records, err := h.Store.ListWebAuthnCredentials(userID)
	if err != nil {
		return passkeyUser{}, err
	}
	creds := make([]webauthn.Credential, 0, len(records))
	for _, record := range records {
		creds = append(creds, credentialFromRecord(record))
	}
	return passkeyUser{
		id:    passkeyUserID(userID),
		name:  username,
		creds: creds,
	}, nil
}

func credentialIDs(creds []webauthn.Credential) [][]byte {
	out := make([][]byte, 0, len(creds))
	for _, cred := range creds {
		out = append(out, cred.ID)
	}
	return out
}
