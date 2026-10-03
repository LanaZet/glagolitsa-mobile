// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) ConfirmDevice(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	var req model.ConfirmDeviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if strings.TrimSpace(req.ConfirmingDeviceID) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "confirming_device_id is required")
		return
	}

	if err := h.Store.ConfirmDevice(deviceID, claims.UserID, req.ConfirmingDeviceID); err != nil {
		status := http.StatusInternalServerError
		switch err {
		case store.ErrNotFound:
			status = http.StatusNotFound
		case store.ErrForbidden:
			status = http.StatusForbidden
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "device.confirmed",
		UserID:    claims.UserID,
		DeviceID:  deviceID,
		RiskLevel: "low",
		Metadata: map[string]any{
			"confirmed_by": req.ConfirmingDeviceID,
		},
	})

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "active"})
}

func (h *Handler) GetDeviceConfirmCode(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	code, expires, err := h.Store.GetPendingDeviceCode(deviceID, claims.UserID)
	if err != nil {
		status := http.StatusInternalServerError
		switch err {
		case store.ErrNotFound:
			status = http.StatusNotFound
		case store.ErrForbidden:
			status = http.StatusForbidden
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, model.DeviceConfirmCodeResponse{
		DeviceID:         deviceID,
		ConfirmationCode: code,
		ExpiresAt:        expires.Format(time.RFC3339),
	})
}

func (h *Handler) RevokeDevice(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	if err := h.Store.RevokeDevice(deviceID, claims.UserID); err != nil {
		httpx.WriteError(w, http.StatusNotFound, err.Error())
		return
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "device.revoked",
		UserID:    claims.UserID,
		DeviceID:  deviceID,
		RiskLevel: "medium",
	})

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "revoked"})
}
