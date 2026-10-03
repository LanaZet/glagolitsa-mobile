// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"net/http"

	"glagolitsa/server/internal/blobstore"
	"glagolitsa/server/internal/hooks"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/keys"
	"glagolitsa/server/internal/messaging/dedup"
	"glagolitsa/server/internal/messaging/ws"
	"glagolitsa/server/internal/metrics"
)

// Handler — HTTP-обработчики Messaging Service.
type Handler struct {
	Store    Store
	Keys     keys.MailboxLookup
	Hub      *ws.Hub
	Blobs    blobstore.Store
	Notifier EnvelopeNotifier
	Metadata *ChatMetadataService
	Hooks    *hooks.Registry
	Dedup    *dedup.Cache
	Metrics  metrics.Interface
}

func NewHandler(store Store, keyLookup keys.MailboxLookup, hub *ws.Hub, blobs blobstore.Store, notifier EnvelopeNotifier) *Handler {
	if notifier == nil {
		notifier = NoopEnvelopeNotifier{}
	}
	return &Handler{
		Store:    store,
		Keys:     keyLookup,
		Hub:      hub,
		Blobs:    blobs,
		Notifier: notifier,
		Metadata: NewChatMetadataService(store, hub),
		Hooks:    hooks.NewRegistry(),
		Dedup:    dedup.NewCache(0, 0),
		Metrics:  metrics.Noop{},
	}
}

func (h *Handler) ChatMessages(method string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("id")
		if chatID == "" {
			httpx.WriteError(w, http.StatusBadRequest, "chat id is required")
			return
		}

		switch method {
		case http.MethodGet:
			h.ListMessages(w, r, chatID)
		case http.MethodPost:
			h.SendMessage(w, r, chatID)
		default:
			httpx.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		}
	}
}
