// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
)

func (h *Handler) Logout(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	sessionID := strings.TrimSpace(claims.SessionID)
	if sessionID != "" {
		_ = h.Store.RevokeSession(sessionID)
	}

	_ = h.Store.RecordAudit(model.AuditEventInput{
		EventType: "logout.success",
		UserID:    claims.UserID,
		DeviceID:  claims.DeviceID,
		RiskLevel: "low",
	})

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
