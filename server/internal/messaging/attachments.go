// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) CreateAttachment(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.Blobs == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "object storage is not configured")
		return
	}

	record, err := h.Store.CreateAttachmentSlot(store.CreateAttachmentInput{
		UploadedBy: claims.UserID,
		ExpiresAt:  store.NowUTC().Add(30 * 24 * time.Hour),
	})
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusCreated, model.CreateAttachmentResponse{
		AttachmentID: record.AttachmentID,
		ExpiresAt:    record.ExpiresAt.Format(time.RFC3339),
	})
}

func (h *Handler) UploadAttachment(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.Blobs == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "object storage is not configured")
		return
	}

	attachmentID := strings.TrimSpace(r.PathValue("id"))
	if attachmentID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "attachment id is required")
		return
	}

	record, err := h.Store.GetAttachment(attachmentID)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	if record.UploadedBy != claims.UserID {
		httpx.WriteError(w, http.StatusForbidden, "forbidden")
		return
	}
	if record.SizeBytes > 0 {
		httpx.WriteError(w, http.StatusConflict, "attachment already uploaded")
		return
	}
	if !record.ExpiresAt.After(store.NowUTC()) {
		httpx.WriteError(w, http.StatusGone, "attachment slot expired")
		return
	}

	limited := http.MaxBytesReader(w, r.Body, store.MaxAttachmentBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, "attachment too large")
		return
	}
	if len(data) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "encrypted attachment body is required")
		return
	}

	if err := h.Blobs.Put(r.Context(), record.ObjectKey, bytes.NewReader(data), int64(len(data)), "application/octet-stream"); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := h.Store.MarkAttachmentUploaded(attachmentID, int64(len(data))); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, model.AttachmentInfo{
		AttachmentID: record.AttachmentID,
		SizeBytes:    int64(len(data)),
		SizeBucket:   store.SizeBucket(len(data)),
		ExpiresAt:    record.ExpiresAt,
		CreatedAt:    record.CreatedAt,
	})
}

func (h *Handler) DownloadAttachment(w http.ResponseWriter, r *http.Request) {
	_, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.Blobs == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "object storage is not configured")
		return
	}

	attachmentID := strings.TrimSpace(r.PathValue("id"))
	if attachmentID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "attachment id is required")
		return
	}

	record, err := h.Store.GetAttachment(attachmentID)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}
	if record.SizeBytes <= 0 {
		httpx.WriteError(w, http.StatusNotFound, "attachment not uploaded")
		return
	}
	if !record.ExpiresAt.After(store.NowUTC()) {
		httpx.WriteError(w, http.StatusGone, "attachment expired")
		return
	}

	reader, err := h.Blobs.Get(r.Context(), record.ObjectKey)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer reader.Close()

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(record.SizeBytes, 10))
	w.Header().Set("X-Attachment-Size-Bucket", strconv.Itoa(record.SizeBucket))
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, reader)
}