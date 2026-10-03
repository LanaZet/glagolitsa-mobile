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

func (h *Handler) SetReaction(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	messageID := strings.TrimSpace(r.PathValue("id"))
	if messageID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "message id is required")
		return
	}
	var req model.SetReactionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	emoji := strings.TrimSpace(req.Emoji)
	if !store.IsAllowedReactionEmoji(emoji) {
		httpx.WriteError(w, http.StatusBadRequest, "emoji not allowed")
		return
	}

	msg, err := h.Store.FindMessage("", messageID)
	if err != nil {
		status := http.StatusInternalServerError
		if err == store.ErrNotFound {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	chat, err := h.Store.GetChat(msg.ChatID, claims.UserID)
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
	okReact, err := h.canReactInChat(msg.ChatID, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !okReact {
		httpx.WriteError(w, http.StatusForbidden, store.ErrForbidden.Error())
		return
	}

	summary, err := h.Store.SetMessageReaction(messageID, claims.UserID, emoji)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.Hub.BroadcastToUsers(chat.MemberIDs, model.WSEvent{
		Event: "reaction.updated",
		Data: model.ReactionUpdatedData{
			MessageID: messageID,
			ChatID:    msg.ChatID,
			Summary:   summary,
		},
	})
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"message_id": messageID,
		"summary":    summary,
	})
}

func (h *Handler) ClearReaction(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	messageID := strings.TrimSpace(r.PathValue("id"))
	if messageID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "message id is required")
		return
	}
	msg, err := h.Store.FindMessage("", messageID)
	if err != nil {
		status := http.StatusInternalServerError
		if err == store.ErrNotFound {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	chat, err := h.Store.GetChat(msg.ChatID, claims.UserID)
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
	_ = chat
	summary, err := h.Store.ClearMessageReaction(messageID, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.Hub.BroadcastToUsers(chat.MemberIDs, model.WSEvent{
		Event: "reaction.updated",
		Data: model.ReactionUpdatedData{
			MessageID: messageID,
			ChatID:    msg.ChatID,
			Summary:   summary,
		},
	})
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"message_id": messageID,
		"summary":    summary,
	})
}
