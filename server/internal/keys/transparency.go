// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

import (
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
)

func (h *Handler) ListKeyChanges(w http.ResponseWriter, r *http.Request) {
	_, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	accountID := strings.TrimSpace(r.PathValue("id"))
	if accountID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "user id is required")
		return
	}

	limit := 50
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			limit = parsed
		}
	}

	events, err := h.Store.ListKeyChangeEvents(accountID, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	response := make([]model.KeyChangeEvent, 0, len(events))
	for _, event := range events {
		response = append(response, model.KeyChangeEvent{
			ID:              event.ID,
			DeviceID:        event.DeviceID,
			EventType:       event.EventType,
			IdentityKeyHash: base64.StdEncoding.EncodeToString(event.IdentityKeyHash),
			SignedPrekeyID:  event.SignedPrekeyID,
			PrevEventHash:   base64.StdEncoding.EncodeToString(event.PrevEventHash),
			EventHash:       base64.StdEncoding.EncodeToString(event.EventHash),
			CreatedAt:       event.CreatedAt.UTC().Format("2006-01-02T15:04:05Z07:00"),
		})
	}
	httpx.WriteJSON(w, http.StatusOK, response)
}