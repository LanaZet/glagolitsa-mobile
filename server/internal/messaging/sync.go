// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"net/http"
	"strconv"
	"time"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) Sync(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	since := time.Time{}
	if raw := r.URL.Query().Get("since"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			since = parsed.UTC()
		}
	}

	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 500 {
			limit = parsed
		}
	}

	var chats []model.Chat
	var err error
	if since.IsZero() {
		chats, err = h.Store.ListChatsForUser(claims.UserID)
	} else {
		chats, err = h.Store.ListChatsUpdatedSince(claims.UserID, since)
	}
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if chats == nil {
		chats = []model.Chat{}
	}

	events, err := h.Store.ListChatEvents(claims.UserID, since, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if events == nil {
		events = []model.ChatEvent{}
	}

	hasMore := len(events) >= limit
	httpx.WriteJSON(w, http.StatusOK, model.SyncResponse{
		ServerTime: store.NowUTC(),
		Chats:      chats,
		Events:     events,
		HasMore:    hasMore,
	})
}