// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"encoding/json"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) DeleteMessage(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	chatID := strings.TrimSpace(r.PathValue("id"))
	messageID := strings.TrimSpace(r.PathValue("messageId"))
	if chatID == "" || messageID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat id and message id are required")
		return
	}

	deletedAt, err := h.Store.SoftDeleteMessage(chatID, messageID, claims.UserID)
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

	_, _ = h.Store.AppendChatEvent(store.ChatEventInput{
		ChatID:    chatID,
		EventType: model.ChatEventMessageDeleted,
		ActorID:   claims.UserID,
		EntityID:  messageID,
	})

	httpx.WriteJSON(w, http.StatusOK, model.DeleteMessageResponse{
		MessageID: messageID,
		DeletedAt: deletedAt,
	})
}

func (h *Handler) MarkRead(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.MarkReadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if strings.TrimSpace(req.ChatID) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}

	_, err := h.Store.GetChat(req.ChatID, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusForbidden, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"chat_id":     req.ChatID,
		"message_ids": req.MessageIDs,
		"status":      "read",
	})
}