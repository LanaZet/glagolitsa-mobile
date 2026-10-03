// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) GetChat(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	chatID := strings.TrimSpace(r.PathValue("id"))
	if chatID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat id is required")
		return
	}

	chat, err := h.Store.GetChat(chatID, claims.UserID)
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

	httpx.WriteJSON(w, http.StatusOK, chat)
}

func (h *Handler) UpdateChat(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	chatID := strings.TrimSpace(r.PathValue("id"))
	if chatID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat id is required")
		return
	}

	var req model.UpdateChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	if req.AvatarURL == nil {
		chat, err := h.Store.GetChat(chatID, claims.UserID)
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
		httpx.WriteJSON(w, http.StatusOK, chat)
		return
	}
	updated, err := h.Metadata.UpdateAvatar(chatID, claims.UserID, *req.AvatarURL)
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, store.ErrNotFound):
			status = http.StatusNotFound
		case errors.Is(err, store.ErrForbidden):
			status = http.StatusForbidden
		case errors.Is(err, ErrInvalidChatUpdate):
			status = http.StatusBadRequest
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, updated)
}
