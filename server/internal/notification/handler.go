// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"encoding/json"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
)

type Handler struct {
	Service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{Service: service}
}

func (h *Handler) RegisterToken(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req RegisterTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	platform := strings.ToLower(strings.TrimSpace(req.Platform))
	switch platform {
	case PlatformIOS, PlatformAndroid, PlatformWeb, PlatformUnifiedPush, "fcm", "up", "simple_push":
		if platform == "fcm" {
			platform = PlatformAndroid
		}
		if platform == "up" || platform == "simple_push" {
			platform = PlatformUnifiedPush
		}
	case "apns":
		platform = PlatformIOS
	default:
		httpx.WriteError(w, http.StatusBadRequest, "platform must be ios, android, web, or unifiedpush")
		return
	}
	if platform == PlatformUnifiedPush {
		tok := strings.TrimSpace(req.Token)
		if !strings.HasPrefix(tok, "https://") {
			httpx.WriteError(w, http.StatusBadRequest, "unifiedpush token must be https endpoint")
			return
		}
	}
	deviceID := strings.TrimSpace(req.DeviceID)
	if deviceID == "" {
		deviceID = strings.TrimSpace(r.Header.Get("X-Device-Id"))
	}
	if deviceID == "" || strings.TrimSpace(req.Token) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device_id and token are required")
		return
	}

	record, err := h.Service.Store.UpsertPushToken(claims.UserID, deviceID, platform, strings.TrimSpace(req.Token))
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, RegisterTokenResponse{
		ID:       record.ID,
		Platform: record.Platform,
		DeviceID: record.DeviceID,
		Status:   record.TokenStatus,
	})
}

func (h *Handler) RevokeToken(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	tokenID := strings.TrimSpace(r.PathValue("id"))
	if tokenID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "token id is required")
		return
	}
	if err := h.Service.Store.RevokePushToken(claims.UserID, tokenID); err != nil {
		httpx.WriteError(w, http.StatusNotFound, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) GetPreferences(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	prefs, err := h.Service.Store.GetPreferences(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, prefs)
}

func (h *Handler) UpdatePreferences(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req UpdatePreferencesRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	prefs, err := h.Service.Store.UpsertPreferences(claims.UserID, func(p *Preferences) error {
		if req.MessagesEnabled != nil {
			p.MessagesEnabled = *req.MessagesEnabled
		}
		if req.CallsEnabled != nil {
			p.CallsEnabled = *req.CallsEnabled
		}
		if req.NewDeviceEnabled != nil {
			p.NewDeviceEnabled = *req.NewDeviceEnabled
		}
		if req.ShowSenderName != nil {
			p.ShowSenderName = *req.ShowSenderName
		}
		if req.ShowMessagePreview != nil {
			p.ShowMessagePreview = *req.ShowMessagePreview
		}
		if req.BadgeEnabled != nil {
			p.BadgeEnabled = *req.BadgeEnabled
		}
		return nil
	})
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, prefs)
}