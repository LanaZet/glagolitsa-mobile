// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req model.RegisterRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	prepared, field, err := prepareRegister(req, auth.DefaultPasswordSettings())
	if err != nil {
		switch {
		case err == errRequiredFields:
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		case err == errInvalidUsername:
			httpx.WriteError(w, http.StatusBadRequest, "invalid username")
		case err == errReservedDevUsername:
			httpx.WriteError(w, http.StatusBadRequest, "username reserved for development")
		case err == errInvalidEmail:
			httpx.WriteError(w, http.StatusBadRequest, "invalid email")
		case field == "password":
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		default:
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		}
		return
	}

	if PowRequired() {
		if prepared.PowChallengeID == "" || prepared.PowSolution == "" {
			httpx.WriteError(w, http.StatusBadRequest, "proof of work is required")
			return
		}
		ok, err := h.Store.ConsumePowChallenge(prepared.PowChallengeID, prepared.PowSolution)
		if err != nil || !ok {
			httpx.WriteError(w, http.StatusBadRequest, "invalid proof of work")
			_ = h.Store.RecordAudit(model.AuditEventInput{
				EventType: "register.pow_failed",
				RiskLevel: "medium",
			})
			return
		}
	}

	passwordHash, err := auth.HashPassword(prepared.Password)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "could not hash password")
		return
	}

	user := model.User{
		ID:        uuid.NewString(),
		Username:  prepared.Username,
		Email:     prepared.Email,
		CreatedAt: store.NowUTC(),
	}

	account, err := h.Store.CreateAccount(user, passwordHash)
	if err != nil {
		status := http.StatusInternalServerError
		if err == store.ErrAlreadyExists {
			// anti-enumeration: одинаковый ответ (Mattermost: username/email exists)
			httpx.WriteJSON(w, http.StatusAccepted, map[string]string{
				"message": "Если аккаунт не существует, регистрация будет завершена после привязки устройства",
			})
			return
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	user.ID = account.ID

	profileUser, err := h.Profiles.GetProfile(user.ID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	resp, err := h.issueAuthResponse(profileUser, account, prepared.DeviceID, r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "register.success",
		UserID:    user.ID,
		DeviceID:  prepared.DeviceID,
		RiskLevel: "low",
	})

	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req model.LoginRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	prepared, err := prepareLogin(req)
	if err != nil {
		switch err {
		case errRequiredFields, errInvalidUsername, errPasswordTooLong:
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		default:
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		}
		return
	}

	account, user, lookupErr := h.Store.GetAccountByUsername(prepared.Username)
	passwordHash := ""
	if lookupErr == nil {
		passwordHash = account.PasswordHash
	}
	if !auth.CheckPasswordOrDummy(passwordHash, prepared.Password) || lookupErr != nil {
		httpx.WriteError(w, http.StatusUnauthorized, "invalid credentials")
		_ = h.Store.RecordAudit(model.AuditEventInput{
			EventType:    "login.failed",
			UserID:       account.ID,
			CoarseIPHash: clientIPHash(r),
			RiskLevel:    "medium",
		})
		return
	}

	resp, err := h.issueAuthResponse(user, account, prepared.DeviceID, r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType:    "login.success",
		UserID:       account.ID,
		DeviceID:     prepared.DeviceID,
		CoarseIPHash: clientIPHash(r),
		RiskLevel:    "low",
	})

	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) Refresh(w http.ResponseWriter, r *http.Request) {
	var req model.RefreshRequest
	if err := decodeJSON(w, r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	refreshToken := strings.TrimSpace(req.RefreshToken)
	requestDeviceID := strings.TrimSpace(req.DeviceID)
	if refreshToken == "" {
		httpx.WriteError(w, http.StatusBadRequest, "refresh_token is required")
		return
	}

	oldSession, err := h.Store.FindSessionByRefreshHash(auth.HashRefreshToken(refreshToken))
	if err != nil {
		logRefreshFailure("lookup_miss", "", "", "", requestDeviceID, err)
		httpx.WriteError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}
	sessionDeviceID := strings.TrimSpace(oldSession.DeviceID)
	if requestDeviceID != "" && sessionDeviceID != "" && requestDeviceID != sessionDeviceID {
		logRefreshFailure("device_mismatch", oldSession.ID, oldSession.UserID, sessionDeviceID, requestDeviceID, nil)
		httpx.WriteError(w, http.StatusUnauthorized, "device mismatch")
		return
	}
	resolvedDeviceID := sessionDeviceID
	if resolvedDeviceID == "" {
		resolvedDeviceID = requestDeviceID
	}

	account, err := h.Store.GetAccountByID(oldSession.UserID)
	if err != nil {
		logRefreshFailure("account_missing", oldSession.ID, oldSession.UserID, sessionDeviceID, requestDeviceID, err)
		httpx.WriteError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}
	user, err := h.Profiles.GetProfile(oldSession.UserID)
	if err != nil {
		logRefreshFailure("profile_missing", oldSession.ID, oldSession.UserID, sessionDeviceID, requestDeviceID, err)
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	expiresAt := time.Now().UTC().Add(auth.RefreshTokenTTL)
	if err := h.Store.RenewSession(oldSession.ID, expiresAt, resolvedDeviceID); err != nil {
		logRefreshFailure("renew_failed", oldSession.ID, oldSession.UserID, sessionDeviceID, requestDeviceID, err)
		httpx.WriteError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}

	accessToken, expiresIn, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:      account.ID,
		Username:    user.Username,
		DeviceID:    resolvedDeviceID,
		TrustTier:   account.TrustTier,
		AccountRole: account.AccountRole,
		SessionID:   oldSession.ID,
	})
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "session.refresh",
		UserID:    account.ID,
		DeviceID:  resolvedDeviceID,
		RiskLevel: "low",
	})
	slog.Info(
		"auth_refresh_succeeded",
		"user_id", account.ID,
		"session_id", oldSession.ID,
		"device_id", resolvedDeviceID,
		"legacy_device_bound", sessionDeviceID == "" && resolvedDeviceID != "",
	)

	// Slim payload: tokens + identity only. Full profile (avatar/bio) is loaded
	// via /api/profile — keeps refresh small so half-open mobile links finish.
	slim := model.User{
		ID:          user.ID,
		Username:    user.Username,
		DisplayName: user.DisplayName,
		CreatedAt:   user.CreatedAt,
	}
	httpx.WriteJSON(w, http.StatusOK, model.AuthResponse{
		// Prefer access_token; keep token for older clients (same value once).
		Token:        accessToken,
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		TokenID:      oldSession.ID,
		SessionID:    oldSession.ID,
		ExpiresIn:    expiresIn,
		AccountRole:  account.AccountRole,
		User:         slim,
	})
}

func logRefreshFailure(reason, sessionID, userID, sessionDeviceID, requestDeviceID string, err error) {
	attrs := []any{
		"reason", reason,
		"session_id", sessionID,
		"user_id", userID,
		"session_device_id", sessionDeviceID,
		"request_device_id", requestDeviceID,
	}
	if err != nil {
		attrs = append(attrs, "error", err.Error())
	}
	slog.Warn("auth_refresh_failed", attrs...)
}

func (h *Handler) issueAuthResponse(user model.User, account model.AccountRecord, deviceID string, r *http.Request) (model.AuthResponse, error) {
	sessionID := uuid.NewString()
	refreshRaw, refreshHash, err := auth.NewRefreshToken()
	if err != nil {
		return model.AuthResponse{}, err
	}
	if err := h.Store.CreateSession(model.SessionRecord{
		ID:               sessionID,
		UserID:           account.ID,
		DeviceID:         deviceID,
		RefreshTokenHash: refreshHash,
		ExpiresAt:        time.Now().UTC().Add(auth.RefreshTokenTTL),
	}); err != nil {
		return model.AuthResponse{}, err
	}

	accessToken, expiresIn, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:      account.ID,
		Username:    user.Username,
		DeviceID:    deviceID,
		TrustTier:   account.TrustTier,
		AccountRole: account.AccountRole,
		SessionID:   sessionID,
	})
	if err != nil {
		return model.AuthResponse{}, err
	}

	_ = r
	return model.AuthResponse{
		Token:        accessToken,
		AccessToken:  accessToken,
		RefreshToken: refreshRaw,
		TokenID:      sessionID,
		SessionID:    sessionID,
		ExpiresIn:    expiresIn,
		AccountRole:  account.AccountRole,
		User:         user,
	}, nil
}
