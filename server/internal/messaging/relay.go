// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/messaging/transport"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/relayauth"
	"glagolitsa/server/internal/store"
)

const relayAfterAcceptTimeout = 5 * time.Second

func (h *Handler) RelayMessage(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.RelayMessageRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(req.Envelopes) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "envelopes is required")
		return
	}
	if err := h.checkRelayLimits(claims.UserID, claims.TrustTier, len(req.Envelopes)); err != nil {
		if errors.Is(err, store.ErrForbidden) {
			httpx.WriteError(w, http.StatusTooManyRequests, "relay envelope limit exceeded")
			return
		}
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	transportInputs := make([]transport.EnvelopeInput, 0, len(req.Envelopes))
	for _, envelope := range req.Envelopes {
		ciphertext, err := decodeRelayBase64("envelopes.ciphertext", envelope.Ciphertext)
		if err != nil {
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
			return
		}
		transportInputs = append(transportInputs, transport.EnvelopeInput{
			MailboxToken: strings.TrimSpace(envelope.MailboxToken),
			EnvelopeType: envelope.EnvelopeType,
			Ciphertext:   ciphertext,
		})
	}

	inputs, err := transport.BuildRelayInputs(transportInputs, req.ExpiresAtSec)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	for _, envelope := range req.Envelopes {
		if !relayauth.ValidateDeliveryToken(envelope.MailboxToken, envelope.DeliveryToken) {
			httpx.WriteError(w, http.StatusForbidden, "invalid delivery token")
			return
		}
	}

	router := transport.Router{Keys: h.Keys, Queue: h.Store}
	routed, err := router.Route(inputs)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	ids := make([]string, 0, len(routed))
	for _, item := range routed {
		ids = append(ids, item.EnvelopeID)
	}

	httpx.WriteJSON(w, http.StatusCreated, model.RelayMessageResponse{
		Enqueued: len(ids),
		IDs:      ids,
	})

	afterAcceptCtx := context.WithoutCancel(r.Context())
	go func() {
		ctx, cancel := context.WithTimeout(afterAcceptCtx, relayAfterAcceptTimeout)
		defer cancel()
		h.afterRelayAccepted(ctx, claims.UserID, routed, ids)
	}()
}

func (h *Handler) afterRelayAccepted(ctx context.Context, actorID string, routed []transport.RoutedEnvelope, ids []string) {
	if err := h.Store.IncrementRelayEnvelopeCount(actorID, len(routed)); err != nil {
		slog.Warn("relay_after_accept_failed", "stage", "increment_limits", "user_id", actorID, "error", err.Error())
	}
	if h.Metrics != nil {
		h.Metrics.IncrementRelayEnqueue(len(routed))
	}

	for _, item := range routed {
		if _, err := h.Store.AppendChatEvent(store.ChatEventInput{
			EventType: model.ChatEventEnvelopeRelayed,
			ActorID:   actorID,
			EntityID:  item.EnvelopeID,
			Metadata: map[string]any{
				"mailbox_token": item.MailboxToken,
				"size_bucket":   item.SizeBucket,
			},
		}); err != nil {
			slog.Warn(
				"relay_after_accept_failed",
				"stage", "append_event",
				"user_id", actorID,
				"envelope_id", item.EnvelopeID,
				"error", err.Error(),
			)
		}

		var exclude []string
		if h.Hub != nil {
			h.Hub.BroadcastEnvelope([]string{item.RecipientAccountID}, model.EnvelopeNewData{
				EnvelopeID:   item.EnvelopeID,
				MailboxToken: item.MailboxToken,
			})
			exclude = append([]string(nil), h.Hub.ConnectedDeviceIDs(item.RecipientAccountID)...)
		}

		if h.Notifier != nil {
			if err := h.Notifier.NotifyNewEnvelope(item.RecipientAccountID, exclude); err != nil {
				slog.Warn(
					"relay_push_notify_failed",
					"user_id", item.RecipientAccountID,
					"exclude_devices", len(exclude),
					"error", err.Error(),
				)
			}
		}
	}

	if h.Hooks != nil {
		h.Hooks.RunEnvelopesRelayed(ctx, actorID, ids)
	}
}

// SendMessageRelay — alias POST /messages/send → relay (только ciphertext).
func (h *Handler) SendMessageRelay(w http.ResponseWriter, r *http.Request) {
	h.RelayMessage(w, r)
}

func (h *Handler) ListMessageQueue(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "X-Device-Id header is required")
		return
	}

	mailboxTokens, err := h.Keys.ResolveMailboxTokens(deviceID, claims.UserID)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	// Live poll proves this install still owns the device — keeps it out of stale purge.
	_ = h.Store.TouchDeviceLastSeen(deviceID, claims.UserID)

	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 200 {
			limit = parsed
		}
	}

	records, err := h.Store.ListQueuedEnvelopes(mailboxTokens, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	envelopeIDs := make([]string, 0, len(records))
	envelopes := make([]model.QueuedEnvelope, 0, len(records))
	for _, record := range records {
		envelopeIDs = append(envelopeIDs, record.EnvelopeID)
		envelopes = append(envelopes, queuedEnvelopeToModel(record))
	}
	_ = h.Store.MarkEnvelopesFetched(envelopeIDs)

	httpx.WriteJSON(w, http.StatusOK, model.MessageQueueResponse{Envelopes: envelopes})
}

func (h *Handler) AckMessageQueue(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "X-Device-Id header is required")
		return
	}

	var req model.AckMessageQueueRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(req.EnvelopeIDs) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "envelope_ids is required")
		return
	}

	mailboxTokens, err := h.Keys.ResolveMailboxTokens(deviceID, claims.UserID)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	deleted, err := h.Store.AckQueuedEnvelopes(mailboxTokens, req.EnvelopeIDs)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Store.MarkEnvelopesAcked(req.EnvelopeIDs)

	httpx.WriteJSON(w, http.StatusOK, model.AckMessageQueueResponse{Deleted: deleted})
}

func queuedEnvelopeToModel(record store.QueuedEnvelopeRecord) model.QueuedEnvelope {
	return model.QueuedEnvelope{
		EnvelopeID:   record.EnvelopeID,
		MailboxToken: record.MailboxToken,
		EnvelopeType: record.EnvelopeType,
		Ciphertext:   base64.StdEncoding.EncodeToString(record.Ciphertext),
		SizeBucket:   record.SizeBucket,
		CreatedAt:    record.CreatedAt,
		ExpiresAt:    record.ExpiresAt,
	}
}

func decodeRelayBase64(field, value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, errors.New(field + " is required")
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return nil, errors.New(field + " must be valid base64")
	}
	return decoded, nil
}
