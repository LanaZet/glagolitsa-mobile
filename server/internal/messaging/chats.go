// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"encoding/json"
	"net/http"

	"github.com/google/uuid"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) ListChats(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	chats, err := h.Store.ListChatsForUser(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if chats == nil {
		chats = []model.Chat{}
	}
	httpx.WriteJSON(w, http.StatusOK, chats)
}

func (h *Handler) CreateChat(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.CreateChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	avatarURL, err := model.NormalizeAvatarURL(req.AvatarURL)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	memberIDs := uniqueIDs(append(req.MemberIDs, claims.UserID))
	chat := model.Chat{
		ID:        uuid.NewString(),
		Title:     req.Title,
		Type:      model.ChatTypeGroup,
		MemberIDs: memberIDs,
		CreatedAt: store.NowUTC(),
		AvatarURL: avatarURL,
	}

	created, err := h.Store.CreateChat(chat)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	if created.Type == model.ChatTypeGroup {
		_ = h.Store.EnsureManagedGroup(created.ID, claims.UserID)
	}

	_, _ = h.Store.AppendChatEvent(store.ChatEventInput{
		ChatID:    created.ID,
		EventType: model.ChatEventChatCreated,
		ActorID:   claims.UserID,
		EntityID:  created.ID,
		Metadata: map[string]any{
			"type":        created.Type,
			"member_count": len(created.MemberIDs),
		},
	})

	httpx.WriteJSON(w, http.StatusCreated, created)
}

func (h *Handler) CreateDM(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.CreateDMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if req.UserID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "user_id is required")
		return
	}

	chat, err := h.Store.FindOrCreateDM(claims.UserID, req.UserID)
	if err != nil {
		if err == store.ErrNotFound {
			httpx.WriteError(w, http.StatusBadRequest, "could not create conversation")
			return
		}
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, chat)
}

func uniqueIDs(ids []string) []string {
	seen := make(map[string]struct{}, len(ids))
	result := make([]string, 0, len(ids))
	for _, id := range ids {
		if id == "" {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		result = append(result, id)
	}
	return result
}