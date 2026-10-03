// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"glagolitsa/server/internal/httpx"
)

type Handler struct {
	Service *Service
}

type chunkUploadMeta struct {
	uploadID      string
	offset        int64
	chunkChecksum string
	complete      bool
}

func NewHandler(service *Service) *Handler {
	return &Handler{Service: service}
}

func (h *Handler) CreateUploadSlot(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.Service == nil || h.Service.Blobs == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "object storage is not configured")
		return
	}

	var req CreateSlotRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	resp, err := h.Service.CreateUploadSlot(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) UploadFile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	fileID := strings.TrimSpace(r.PathValue("id"))
	if fileID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "file id is required")
		return
	}

	// Open channel slots: plaintext photo path (never chunked e2e).
	if mode, err := h.slotContentMode(fileID); err == nil && mode == "open" {
		info, err := h.Service.UploadOpenBlob(r.Context(), fileID, claims.UserID, r.Body)
		if err != nil {
			h.writeServiceError(w, err)
			return
		}
		httpx.WriteJSON(w, http.StatusOK, info)
		return
	}

	if handled := h.tryHandleChunkUpload(w, r, fileID, claims.UserID, false); handled {
		return
	}

	contentHash := strings.TrimSpace(r.Header.Get("X-Content-Hash"))
	info, err := h.Service.UploadEncryptedBlob(r.Context(), fileID, claims.UserID, r.Body, contentHash)
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, info)
}

func (h *Handler) slotContentMode(fileID string) (string, error) {
	if h.Service == nil || h.Service.Store == nil {
		return "", ErrNotFound
	}
	rec, err := h.Service.Store.GetFile(fileID)
	if err != nil {
		return "", err
	}
	if rec.ContentMode == "" {
		return "encrypted", nil
	}
	return rec.ContentMode, nil
}

func (h *Handler) DownloadFile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	fileID := strings.TrimSpace(r.PathValue("id"))
	if fileID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "file id is required")
		return
	}

	record, reader, err := h.Service.DownloadBlob(r.Context(), fileID, claims.UserID)
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	defer reader.Close()

	contentType := "application/octet-stream"
	if record.IsOpen() && record.MimeType != "" {
		contentType = record.MimeType
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Length", strconv.FormatInt(record.SizeBytes, 10))
	w.Header().Set("X-Media-Size-Bucket", strconv.Itoa(record.SizeBucket))
	if record.ContentMode != "" {
		w.Header().Set("X-Media-Content-Mode", record.ContentMode)
	}
	if record.ContentHashEncrypted != "" {
		w.Header().Set("X-Content-Hash", record.ContentHashEncrypted)
	}
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, reader)
}

func (h *Handler) GetFileMeta(w http.ResponseWriter, r *http.Request) {
	_, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	fileID := strings.TrimSpace(r.PathValue("id"))
	if fileID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "file id is required")
		return
	}

	info, err := h.Service.GetFileInfo(fileID)
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, info)
}

func (h *Handler) DeleteFile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	fileID := strings.TrimSpace(r.PathValue("id"))
	if fileID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "file id is required")
		return
	}
	if err := h.Service.DeleteFile(fileID, claims.UserID); err != nil {
		h.writeServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) CDNRedirect(w http.ResponseWriter, r *http.Request) {
	fileID := strings.TrimSpace(r.PathValue("id"))
	if fileID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "file id is required")
		return
	}
	if h.Service == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "media service unavailable")
		return
	}
	url := h.Service.CDN.PublicURL(fileID)
	if url == "" {
		httpx.WriteError(w, http.StatusNotFound, "cdn is not configured")
		return
	}
	http.Redirect(w, r, url, http.StatusTemporaryRedirect)
}

// Legacy attachment endpoints (этап 6.1 compatibility).
func (h *Handler) LegacyCreateAttachment(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.Service == nil || h.Service.Blobs == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "object storage is not configured")
		return
	}
	resp, err := h.Service.CreateUploadSlot(claims.UserID, CreateSlotRequest{Kind: KindDocument})
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, LegacyCreateAttachmentResponse{
		AttachmentID: resp.FileID,
		ExpiresAt:    resp.ExpiresAt,
	})
}

func (h *Handler) LegacyUploadAttachment(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	attachmentID := strings.TrimSpace(r.PathValue("id"))
	if attachmentID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "attachment id is required")
		return
	}
	if handled := h.tryHandleChunkUpload(w, r, attachmentID, claims.UserID, true); handled {
		return
	}
	info, err := h.Service.UploadEncryptedBlob(r.Context(), attachmentID, claims.UserID, r.Body, "")
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, LegacyAttachmentInfo{
		AttachmentID: info.FileID,
		SizeBytes:    info.SizeBytes,
		SizeBucket:   info.SizeBucket,
		ExpiresAt:    info.ExpiresAt,
		CreatedAt:    info.CreatedAt,
	})
}

func readChunkUploadMeta(r *http.Request) (*chunkUploadMeta, error) {
	uploadID := strings.TrimSpace(r.Header.Get("X-Upload-Id"))
	if uploadID == "" {
		return nil, nil
	}
	offset, err := strconv.ParseInt(strings.TrimSpace(r.Header.Get("X-Upload-Offset")), 10, 64)
	if err != nil || offset < 0 {
		return nil, errors.New("valid X-Upload-Offset is required for chunk upload")
	}
	return &chunkUploadMeta{
		uploadID:      uploadID,
		offset:        offset,
		chunkChecksum: strings.TrimSpace(r.Header.Get("X-Chunk-SHA256")),
		complete:      strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Upload-Complete")), "true"),
	}, nil
}

func (h *Handler) tryHandleChunkUpload(w http.ResponseWriter, r *http.Request, fileID, userID string, legacy bool) bool {
	meta, err := readChunkUploadMeta(r)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return true
	}
	if meta == nil {
		return false
	}
	result, err := h.Service.UploadEncryptedChunk(
		r.Context(),
		fileID,
		userID,
		meta.uploadID,
		meta.offset,
		meta.chunkChecksum,
		meta.complete,
		r.Body,
	)
	if err != nil {
		h.writeServiceError(w, err)
		return true
	}
	if result.Completed && result.Info != nil {
		if legacy {
			httpx.WriteJSON(w, http.StatusOK, LegacyAttachmentInfo{
				AttachmentID: result.Info.FileID,
				SizeBytes:    result.Info.SizeBytes,
				SizeBucket:   result.Info.SizeBucket,
				ExpiresAt:    result.Info.ExpiresAt,
				CreatedAt:    result.Info.CreatedAt,
			})
		} else {
			httpx.WriteJSON(w, http.StatusOK, *result.Info)
		}
		return true
	}
	httpx.WriteJSON(w, http.StatusAccepted, map[string]any{
		"upload_id":   result.UploadID,
		"next_offset": result.NextOffset,
		"completed":   false,
	})
	return true
}

func (h *Handler) LegacyDownloadAttachment(w http.ResponseWriter, r *http.Request) {
	h.DownloadFile(w, r)
}

func (h *Handler) writeServiceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		httpx.WriteError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, ErrForbidden):
		httpx.WriteError(w, http.StatusForbidden, err.Error())
	case errors.Is(err, ErrAlreadyExists):
		httpx.WriteError(w, http.StatusConflict, err.Error())
	case errors.Is(err, ErrGone):
		httpx.WriteError(w, http.StatusGone, err.Error())
	case errors.Is(err, ErrUploadTooLarge), errors.Is(err, ErrChunkTooLarge):
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, err.Error())
	case errors.Is(err, ErrChecksumMismatch), errors.Is(err, ErrOffsetMismatch):
		httpx.WriteError(w, http.StatusConflict, err.Error())
	case errors.Is(err, ErrQuarantined):
		httpx.WriteError(w, http.StatusLocked, err.Error())
	case errors.Is(err, ErrScanRejected):
		httpx.WriteError(w, http.StatusUnprocessableEntity, err.Error())
	case errors.Is(err, ErrUserQuotaExceeded), errors.Is(err, ErrChatQuotaExceeded):
		httpx.WriteError(w, http.StatusInsufficientStorage, err.Error())
	default:
		if strings.Contains(err.Error(), "exceeds limit") {
			httpx.WriteError(w, http.StatusRequestEntityTooLarge, err.Error())
			return
		}
		if strings.Contains(err.Error(), "object storage is not configured") {
			httpx.WriteError(w, http.StatusServiceUnavailable, err.Error())
			return
		}
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
	}
}
