// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/messaging/dedup"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

const senderKeyEnvelopeType = 4

func (h *Handler) ListChannelPosts(w http.ResponseWriter, r *http.Request) {
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
	if chat.Type != model.ChatTypeChannel {
		httpx.WriteError(w, http.StatusBadRequest, "posts feed is only available for channels")
		return
	}

	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 200 {
			limit = parsed
		}
	}
	beforeID := r.URL.Query().Get("before")
	galleryOnly := r.URL.Query().Get("gallery") == "1" ||
		r.URL.Query().Get("gallery") == "true" ||
		strings.HasSuffix(r.URL.Path, "/gallery")

	messages, hasMore, err := h.Store.ListChannelPostsPage(chatID, claims.UserID, beforeID, limit, galleryOnly)
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
	if messages == nil {
		messages = []model.Message{}
	}
	httpx.WriteJSON(w, http.StatusOK, model.MessagesPageResponse{
		Messages: messages,
		HasMore:  hasMore,
	})
}

func (h *Handler) ListMessages(w http.ResponseWriter, r *http.Request, chatID string) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 200 {
			limit = parsed
		}
	}
	beforeID := r.URL.Query().Get("before")

	messages, hasMore, err := h.Store.ListMessagesPage(chatID, claims.UserID, beforeID, limit)
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
	if messages == nil {
		messages = []model.Message{}
	}
	for i := range messages {
		if strings.TrimSpace(messages[i].Ciphertext) != "" {
			messages[i].Body = ""
		}
	}
	httpx.WriteJSON(w, http.StatusOK, model.MessagesPageResponse{
		Messages: messages,
		HasMore:  hasMore,
	})
}

func (h *Handler) SendMessage(w http.ResponseWriter, r *http.Request, chatID string) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.SendMessageRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
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

	if chat.Type == model.ChatTypeDM {
		httpx.WriteError(w, http.StatusGone, "direct messages must use encrypted relay; plaintext body is deprecated")
		return
	}

	// Channels use open (server-visible) posts: body is allowed, no e2e ciphertext.
	// Groups remain sender-key encrypted (see below).
	if chat.Type == model.ChatTypeChannel {
		h.sendChannelOpenMessage(w, r, chat, claims.UserID, req)
		return
	}

	canSend, err := h.canSendToChat(chatID, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !canSend {
		httpx.WriteError(w, http.StatusForbidden, store.ErrForbidden.Error())
		return
	}

	encrypted, err := decodeEncryptedGroupRequest(req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	if encrypted == nil {
		httpx.WriteError(w, http.StatusGone, "group messages must use sender-key encryption")
		return
	}

	message := model.Message{
		ID:             uuid.NewString(),
		ChatID:         chatID,
		SenderID:       claims.UserID,
		PendingID:      req.PendingID,
		CreatedAt:      store.NowUTC(),
		EnvelopeType:   &encrypted.envelopeType,
		Ciphertext:     encrypted.ciphertextB64,
		SenderDeviceID: encrypted.senderDeviceID,
	}

	if req.PendingID != "" {
		if _, state, ok := h.Dedup.Get(req.PendingID); ok && state == dedup.StateCommitted {
			if existing, err := h.Store.FindMessageByPendingID(chatID, req.PendingID); err == nil {
				h.Metrics.IncrementDedupHit("message")
				httpx.WriteJSON(w, http.StatusOK, existing)
				return
			}
		}
		if !h.Dedup.Reserve(req.PendingID) {
			httpx.WriteError(w, http.StatusConflict, "message with this pending_id is in flight")
			return
		}
	}

	if h.Hooks != nil {
		next, err := h.Hooks.RunMessageWillBeCreated(r.Context(), &message)
		if err != nil {
			if req.PendingID != "" {
				h.Dedup.Release(req.PendingID)
			}
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
			return
		}
		message = *next
	}

	created, err := h.Store.AddMessage(message)
	if err != nil {
		if req.PendingID != "" {
			h.Dedup.Release(req.PendingID)
		}
		status := http.StatusInternalServerError
		switch err {
		case store.ErrForbidden:
			status = http.StatusForbidden
		case store.ErrNotFound:
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	if req.PendingID != "" {
		h.Dedup.Commit(req.PendingID, created.ID)
	}
	h.Metrics.IncrementMessageCreate()
	if h.Hooks != nil {
		h.Hooks.RunMessageHasBeenCreated(r.Context(), created)
	}

	_, _ = h.Store.AppendChatEvent(store.ChatEventInput{
		ChatID:    chatID,
		EventType: model.ChatEventMessageSent,
		ActorID:   claims.UserID,
		EntityID:  created.ID,
		Metadata: map[string]any{
			"has_body": created.Body != "",
		},
	})

	for _, memberID := range chat.MemberIDs {
		memberChat, err := h.Store.GetChat(chatID, memberID)
		if err == nil {
			h.Hub.BroadcastChatUpdated([]string{memberID}, memberChat)
		}
	}
	h.Hub.BroadcastMessage(chat.MemberIDs, created)
	httpx.WriteJSON(w, http.StatusCreated, created)
}

// sendChannelOpenMessage publishes a server-visible channel post or comment.
// Root posts require perm_send (admin by default). Thread replies are allowed
// for any member in this stage; finer perm_comment lands in a later migration.
func (h *Handler) sendChannelOpenMessage(w http.ResponseWriter, r *http.Request, chat model.Chat, userID string, req model.SendMessageRequest) {
	chatID := chat.ID
	body := strings.TrimSpace(req.Body)
	if body == "" {
		httpx.WriteError(w, http.StatusBadRequest, "body is required for channel posts")
		return
	}
	if strings.TrimSpace(req.Ciphertext) != "" {
		httpx.WriteError(w, http.StatusBadRequest, "channel posts must not include e2e ciphertext")
		return
	}

	threadRoot := strings.TrimSpace(req.ThreadRootID)
	isComment := threadRoot != ""

	if isComment {
		ok, err := h.canCommentInChat(chatID, userID)
		if err != nil {
			httpx.WriteError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if !ok {
			httpx.WriteError(w, http.StatusForbidden, store.ErrForbidden.Error())
			return
		}
	} else {
		canSend, err := h.canSendToChat(chatID, userID)
		if err != nil {
			httpx.WriteError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if !canSend {
			httpx.WriteError(w, http.StatusForbidden, store.ErrForbidden.Error())
			return
		}
	}

	message := model.Message{
		ID:               uuid.NewString(),
		ChatID:           chatID,
		SenderID:         userID,
		Body:             body,
		PendingID:        req.PendingID,
		CreatedAt:        store.NowUTC(),
		ReplyToMessageID: strings.TrimSpace(req.ReplyToMessageID),
		ThreadRootID:     threadRoot,
		ThreadParentID:   strings.TrimSpace(req.ThreadParentID),
		Visibility:       strings.TrimSpace(req.Visibility),
		Metadata:         req.Metadata,
	}

	if req.PendingID != "" {
		if _, state, ok := h.Dedup.Get(req.PendingID); ok && state == dedup.StateCommitted {
			if existing, err := h.Store.FindMessageByPendingID(chatID, req.PendingID); err == nil {
				h.Metrics.IncrementDedupHit("message")
				httpx.WriteJSON(w, http.StatusOK, existing)
				return
			}
		}
		if !h.Dedup.Reserve(req.PendingID) {
			httpx.WriteError(w, http.StatusConflict, "message with this pending_id is in flight")
			return
		}
	}

	if h.Hooks != nil {
		next, err := h.Hooks.RunMessageWillBeCreated(r.Context(), &message)
		if err != nil {
			if req.PendingID != "" {
				h.Dedup.Release(req.PendingID)
			}
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
			return
		}
		message = *next
	}

	created, err := h.Store.AddMessage(message)
	if err != nil {
		if req.PendingID != "" {
			h.Dedup.Release(req.PendingID)
		}
		status := http.StatusInternalServerError
		switch err {
		case store.ErrForbidden:
			status = http.StatusForbidden
		case store.ErrNotFound:
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	if req.PendingID != "" {
		h.Dedup.Commit(req.PendingID, created.ID)
	}
	h.Metrics.IncrementMessageCreate()
	if h.Hooks != nil {
		h.Hooks.RunMessageHasBeenCreated(r.Context(), created)
	}

	_, _ = h.Store.AppendChatEvent(store.ChatEventInput{
		ChatID:    chatID,
		EventType: model.ChatEventMessageSent,
		ActorID:   userID,
		EntityID:  created.ID,
		Metadata: map[string]any{
			"has_body":   created.Body != "",
			"channel":    true,
			"is_comment": isComment,
		},
	})

	for _, memberID := range chat.MemberIDs {
		memberChat, err := h.Store.GetChat(chatID, memberID)
		if err == nil {
			h.Hub.BroadcastChatUpdated([]string{memberID}, memberChat)
		}
	}
	h.Hub.BroadcastMessage(chat.MemberIDs, created)
	httpx.WriteJSON(w, http.StatusCreated, created)
}

type encryptedGroupRequest struct {
	envelopeType   int
	ciphertextB64  string
	senderDeviceID string
	rawCiphertext  []byte
}

func decodeEncryptedGroupRequest(req model.SendMessageRequest) (*encryptedGroupRequest, error) {
	ciphertextB64 := strings.TrimSpace(req.Ciphertext)
	if ciphertextB64 == "" {
		return nil, nil
	}
	if req.EnvelopeType == nil {
		return nil, errPlain("envelope_type is required for encrypted group messages")
	}
	if *req.EnvelopeType != senderKeyEnvelopeType {
		return nil, errPlain("envelope_type must be 4 (sender key)")
	}
	senderDeviceID := strings.TrimSpace(req.SenderDeviceID)
	if senderDeviceID == "" {
		return nil, errPlain("sender_device_id is required for encrypted group messages")
	}
	raw, err := base64.StdEncoding.DecodeString(ciphertextB64)
	if err != nil {
		return nil, errPlain("invalid ciphertext encoding")
	}
	if len(raw) == 0 {
		return nil, errPlain("ciphertext is required")
	}
	return &encryptedGroupRequest{
		envelopeType:   *req.EnvelopeType,
		ciphertextB64:  ciphertextB64,
		senderDeviceID: senderDeviceID,
		rawCiphertext:  raw,
	}, nil
}

type plainError string

func (e plainError) Error() string { return string(e) }

func errPlain(message string) error { return plainError(message) }
