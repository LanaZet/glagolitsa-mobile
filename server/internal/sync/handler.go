// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"glagolitsa/server/internal/httpx"
)

type Handler struct {
	Service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{Service: service}
}

func (h *Handler) ListEvents(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	after := int64(0)
	if raw := r.URL.Query().Get("after_event_id"); raw != "" {
		if parsed, err := strconv.ParseInt(raw, 10, 64); err == nil {
			after = parsed
		}
	}
	limit := 0
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil {
			limit = parsed
		}
	}
	resp, err := h.Service.ListEvents(claims.UserID, after, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) Delta(w http.ResponseWriter, r *http.Request) {
	h.ListEvents(w, r)
}

func (h *Handler) LegacySync(w http.ResponseWriter, r *http.Request) {
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
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			limit = parsed
		}
	}
	resp, err := h.Service.LegacySync(claims.UserID, since, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) RegisterDevice(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req RegisterDeviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.RegisterDevice(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) Ack(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req AckRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if err := h.Service.Ack(claims.UserID, req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) GetSnapshot(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	resp, err := h.Service.GetSnapshot(claims.UserID)
	if err != nil {
		if errors.Is(err, ErrNotFound) {
			httpx.WriteError(w, http.StatusNotFound, err.Error())
			return
		}
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) SaveSnapshot(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req struct {
		EventID      int64  `json:"event_id"`
		SnapshotData string `json:"snapshot_data"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	data, err := decodeB64(req.SnapshotData)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid snapshot_data encoding")
		return
	}
	resp, err := h.Service.SaveSnapshot(claims.UserID, req.EventID, data)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) PushEvent(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req PushEventRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.PushEvent(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	status := http.StatusOK
	if resp.Queued {
		status = http.StatusAccepted
	}
	if resp.Rejected {
		status = http.StatusConflict
	}
	httpx.WriteJSON(w, status, resp)
}

func (h *Handler) UploadSnapshotBinary(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	eventID, _ := strconv.ParseInt(r.URL.Query().Get("event_id"), 10, 64)
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 8<<20))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "snapshot too large")
		return
	}
	resp, err := h.Service.SaveSnapshot(claims.UserID, eventID, data)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) writeSensitivePath(path string) bool {
	return strings.HasPrefix(path, "/api/sync/")
}