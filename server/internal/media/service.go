// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"glagolitsa/server/internal/blobstore"
	"glagolitsa/server/internal/store"
)

type Service struct {
	Store   Store
	Blobs   blobstore.Store
	Config  Config
	Privacy PrivacyGuard
	CDN     CDNAdapter
	Scan    ScanSandbox
	// Access gates open (channel) media only; nil → open mode rejected.
	Access    ChannelAccess
	uploadsMu sync.Mutex
	uploads   map[string]*resumableUploadSession
}

type resumableUploadSession struct {
	FileID      string
	OwnerUserID string
	TempPath    string
	File        *os.File
	Hasher      hash.Hash
	Received    int64
	MaxBytes    int64
}

type ChunkUploadResult struct {
	UploadID   string
	NextOffset int64
	Completed  bool
	Info       *FileInfoResponse
}

func NewService(mediaStore Store, blobs blobstore.Store, cfg Config) *Service {
	return &Service{
		Store:   mediaStore,
		Blobs:   blobs,
		Config:  cfg,
		Privacy: PrivacyGuard{},
		CDN:     NewCDNAdapter(cfg),
		Scan:    NewScanSandbox(cfg),
		uploads: make(map[string]*resumableUploadSession),
	}
}

func (s *Service) Start(ctx context.Context) {
	// Retention loop is driven by jobs.Server (Mattermost-style job watcher).
	_ = ctx
}

// RunRetentionOnce purges expired media blobs (jobs worker entrypoint).
func (s *Service) RunRetentionOnce(ctx context.Context) error {
	if s.Store == nil || s.Blobs == nil {
		return nil
	}
	NewRetentionWorker(s.Store, s.Blobs, s.Config).purgeOnce(ctx)
	return nil
}

func (s *Service) CreateUploadSlot(ownerUserID string, req CreateSlotRequest) (CreateSlotResponse, error) {
	kind, err := normalizeKind(req.Kind)
	if err != nil {
		return CreateSlotResponse{}, err
	}
	mimeType := strings.TrimSpace(req.MimeType)
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	if err := s.Privacy.ValidateClientMimeType(mimeType); err != nil {
		return CreateSlotResponse{}, err
	}

	contentMode := strings.ToLower(strings.TrimSpace(req.ContentMode))
	if contentMode == "" {
		contentMode = store.MediaContentEncrypted
	}
	chatID := strings.TrimSpace(req.ChatID)
	maxBytes := s.Config.MaxBytes(kind)

	switch contentMode {
	case store.MediaContentEncrypted:
		// Default path — DM/group e2e. No channel access checks.
	case store.MediaContentOpen:
		// Strict: open path is channel plaintext photos only.
		if kind != KindPhoto {
			return CreateSlotResponse{}, fmt.Errorf("open media only supports kind=photo")
		}
		if chatID == "" {
			return CreateSlotResponse{}, fmt.Errorf("chat_id is required for open media")
		}
		if err := validateOpenPhotoClientMIME(mimeType); err != nil {
			return CreateSlotResponse{}, err
		}
		if s.Access == nil {
			return CreateSlotResponse{}, fmt.Errorf("open media access is not configured")
		}
		if err := s.Access.AllowOpenUpload(ownerUserID, chatID); err != nil {
			return CreateSlotResponse{}, err
		}
		maxBytes = OpenPhotoMaxBytes
		if mimeType == "application/octet-stream" {
			mimeType = "image/jpeg" // placeholder; sniff on upload
		}
	default:
		return CreateSlotResponse{}, fmt.Errorf("content_mode must be encrypted or open")
	}

	expiresAt := time.Now().UTC().Add(s.Config.DefaultTTL)
	record, err := s.Store.CreateFileSlot(store.CreateMediaFileInput{
		OwnerUserID:  ownerUserID,
		ChatID:       chatID,
		Kind:         kind,
		MimeType:     mimeType,
		ParentFileID: strings.TrimSpace(req.ParentFileID),
		ContentMode:  contentMode,
		ExpiresAt:    expiresAt,
	})
	if err != nil {
		return CreateSlotResponse{}, mapStoreErr(err)
	}
	return CreateSlotResponse{
		FileID:      record.FileID,
		ExpiresAt:   record.ExpiresAt.Format(time.RFC3339),
		MaxBytes:    maxBytes,
		Kind:        kind,
		ContentMode: contentMode,
	}, nil
}

func (s *Service) UploadEncryptedBlob(
	ctx context.Context,
	fileID string,
	ownerUserID string,
	reader io.Reader,
	contentHash string,
) (FileInfoResponse, error) {
	if s.Blobs == nil {
		return FileInfoResponse{}, ErrStorageUnavailable
	}
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return FileInfoResponse{}, err
	}
	if record.OwnerUserID != ownerUserID {
		return FileInfoResponse{}, ErrForbidden
	}
	if record.SizeBytes > 0 {
		return FileInfoResponse{}, ErrAlreadyExists
	}
	// Never put plaintext open-channel photos through the e2e upload path.
	if record.IsOpen() {
		return FileInfoResponse{}, fmt.Errorf("open media must use open upload path")
	}
	contentHash = strings.TrimSpace(contentHash)
	if err := s.Privacy.ValidateEncryptedHash(contentHash); err != nil {
		return FileInfoResponse{}, err
	}

	maxBytes := s.Config.MaxBytes(record.Kind)
	tempFile, err := os.CreateTemp("", "media-upload-*")
	if err != nil {
		return FileInfoResponse{}, err
	}
	defer func() {
		_ = tempFile.Close()
		_ = os.Remove(tempFile.Name())
	}()
	hasher := sha256.New()
	written, err := io.CopyBuffer(
		io.MultiWriter(tempFile, hasher),
		io.LimitReader(reader, maxBytes+1),
		make([]byte, 64*1024),
	)
	if err != nil {
		return FileInfoResponse{}, err
	}
	if written == 0 {
		return FileInfoResponse{}, fmt.Errorf("encrypted blob is required")
	}
	if written > maxBytes {
		return FileInfoResponse{}, ErrUploadTooLarge
	}
	computedHex := hex.EncodeToString(hasher.Sum(nil))
	if contentHash != "" && !strings.EqualFold(contentHash, computedHex) {
		return FileInfoResponse{}, ErrChecksumMismatch
	}
	if contentHash == "" {
		contentHash = computedHex
	}

	if err := s.enforceQuota(record, written); err != nil {
		return FileInfoResponse{}, err
	}

	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return FileInfoResponse{}, err
	}
	scanStatus := s.Scan.Inspect(ctx, fileID, tempFile, written)
	if scanStatus == store.MediaScanRejected {
		return FileInfoResponse{}, ErrScanRejected
	}
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return FileInfoResponse{}, err
	}
	if err := s.Blobs.Put(ctx, record.StoragePath, tempFile, written, "application/octet-stream"); err != nil {
		return FileInfoResponse{}, err
	}
	if err := s.Store.MarkFileUploaded(fileID, written, contentHash, scanStatus, ""); err != nil {
		_ = s.Blobs.Delete(ctx, record.StoragePath)
		return FileInfoResponse{}, mapStoreErr(err)
	}

	updated, err := s.Store.GetFile(fileID)
	if err != nil {
		return FileInfoResponse{}, mapStoreErr(err)
	}
	return s.toInfo(updated), nil
}

// UploadOpenBlob stores plaintext channel photo. Rejects encrypted-mode slots.
func (s *Service) UploadOpenBlob(
	ctx context.Context,
	fileID string,
	ownerUserID string,
	reader io.Reader,
) (FileInfoResponse, error) {
	if s.Blobs == nil {
		return FileInfoResponse{}, ErrStorageUnavailable
	}
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return FileInfoResponse{}, err
	}
	if record.OwnerUserID != ownerUserID {
		return FileInfoResponse{}, ErrForbidden
	}
	if record.SizeBytes > 0 {
		return FileInfoResponse{}, ErrAlreadyExists
	}
	if !record.IsOpen() {
		return FileInfoResponse{}, fmt.Errorf("slot is not open media; use encrypted upload")
	}
	if record.ChatID == "" {
		return FileInfoResponse{}, fmt.Errorf("open media requires chat_id on slot")
	}
	if s.Access != nil {
		if err := s.Access.AllowOpenUpload(ownerUserID, record.ChatID); err != nil {
			return FileInfoResponse{}, err
		}
	}

	maxBytes := OpenPhotoMaxBytes
	tempFile, err := os.CreateTemp("", "media-open-*")
	if err != nil {
		return FileInfoResponse{}, err
	}
	defer func() {
		_ = tempFile.Close()
		_ = os.Remove(tempFile.Name())
	}()
	hasher := sha256.New()
	written, err := io.CopyBuffer(
		io.MultiWriter(tempFile, hasher),
		io.LimitReader(reader, maxBytes+1),
		make([]byte, 64*1024),
	)
	if err != nil {
		return FileInfoResponse{}, err
	}
	if written == 0 {
		return FileInfoResponse{}, fmt.Errorf("photo payload is required")
	}
	if written > maxBytes {
		return FileInfoResponse{}, ErrUploadTooLarge
	}

	// Sniff magic bytes from the start of the temp file.
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return FileInfoResponse{}, err
	}
	head := make([]byte, 16)
	n, _ := io.ReadFull(tempFile, head)
	sniffed := sniffOpenImageMIME(head[:n])
	if sniffed == "" {
		return FileInfoResponse{}, fmt.Errorf("file is not a supported image (jpeg/png/webp)")
	}

	if err := s.enforceQuota(record, written); err != nil {
		return FileInfoResponse{}, err
	}
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return FileInfoResponse{}, err
	}
	// Open images: light scan mode — mark clean after sniff (no decrypt).
	scanStatus := store.MediaScanClean
	if err := s.Blobs.Put(ctx, record.StoragePath, tempFile, written, sniffed); err != nil {
		return FileInfoResponse{}, err
	}
	contentHash := hex.EncodeToString(hasher.Sum(nil))
	if err := s.Store.MarkFileUploaded(fileID, written, contentHash, scanStatus, sniffed); err != nil {
		_ = s.Blobs.Delete(ctx, record.StoragePath)
		return FileInfoResponse{}, mapStoreErr(err)
	}
	updated, err := s.Store.GetFile(fileID)
	if err != nil {
		return FileInfoResponse{}, mapStoreErr(err)
	}
	info := s.toInfo(updated)

	// Gallery thumb from blob bytes (more reliable than reusing the temp handle).
	if blobReader, berr := s.Blobs.Get(ctx, updated.StoragePath); berr == nil {
		func() {
			defer blobReader.Close()
			if thumbID, terr := s.createOpenThumb(ctx, ownerUserID, record.ChatID, fileID, blobReader); terr == nil {
				info.ThumbFileID = thumbID
			}
		}()
	} else if _, err := tempFile.Seek(0, io.SeekStart); err == nil {
		if thumbID, terr := s.createOpenThumb(ctx, ownerUserID, record.ChatID, fileID, tempFile); terr == nil {
			info.ThumbFileID = thumbID
		}
	}
	return info, nil
}

// createOpenThumb stores a downscaled JPEG sibling for gallery grids.
func (s *Service) createOpenThumb(ctx context.Context, ownerUserID, chatID, parentFileID string, src io.Reader) (string, error) {
	thumbBytes, err := buildOpenJPEGThumb(src)
	if err != nil || len(thumbBytes) == 0 {
		return "", err
	}
	expiresAt := time.Now().UTC().Add(s.Config.DefaultTTL)
	slot, err := s.Store.CreateFileSlot(store.CreateMediaFileInput{
		OwnerUserID:  ownerUserID,
		ChatID:       chatID,
		Kind:         KindThumbnail,
		MimeType:     "image/jpeg",
		ParentFileID: parentFileID,
		ContentMode:  store.MediaContentOpen,
		ExpiresAt:    expiresAt,
	})
	if err != nil {
		return "", err
	}
	if err := s.Blobs.Put(ctx, slot.StoragePath, bytes.NewReader(thumbBytes), int64(len(thumbBytes)), "image/jpeg"); err != nil {
		_ = s.Store.SoftDeleteFile(slot.FileID, ownerUserID)
		return "", err
	}
	hash := sha256.Sum256(thumbBytes)
	if err := s.Store.MarkFileUploaded(slot.FileID, int64(len(thumbBytes)), hex.EncodeToString(hash[:]), store.MediaScanClean, "image/jpeg"); err != nil {
		_ = s.Blobs.Delete(ctx, slot.StoragePath)
		return "", err
	}
	return slot.FileID, nil
}

func (s *Service) UploadEncryptedChunk(
	ctx context.Context,
	fileID string,
	ownerUserID string,
	uploadID string,
	offset int64,
	chunkChecksum string,
	complete bool,
	reader io.Reader,
) (ChunkUploadResult, error) {
	if s.Blobs == nil {
		return ChunkUploadResult{}, ErrStorageUnavailable
	}
	if uploadID = strings.TrimSpace(uploadID); uploadID == "" {
		return ChunkUploadResult{}, fmt.Errorf("upload_id is required")
	}
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return ChunkUploadResult{}, err
	}
	if record.OwnerUserID != ownerUserID {
		return ChunkUploadResult{}, ErrForbidden
	}
	if record.SizeBytes > 0 {
		return ChunkUploadResult{}, ErrAlreadyExists
	}

	sessionKey := fileID + ":" + uploadID
	session, err := s.getOrCreateUploadSession(sessionKey, record, ownerUserID, offset)
	if err != nil {
		return ChunkUploadResult{}, err
	}
	cleanupOnError := complete
	defer func() {
		if cleanupOnError {
			s.closeAndDeleteUploadSession(sessionKey)
		}
	}()

	chunkMax := s.Config.ChunkMaxBytes
	if chunkMax <= 0 {
		chunkMax = 2 << 20
	}
	chunkReader := io.LimitReader(reader, chunkMax+1)
	chunk, err := io.ReadAll(chunkReader)
	if err != nil {
		return ChunkUploadResult{}, err
	}
	if int64(len(chunk)) > chunkMax {
		return ChunkUploadResult{}, ErrChunkTooLarge
	}
	if len(chunk) == 0 && !complete {
		return ChunkUploadResult{}, fmt.Errorf("chunk payload is required")
	}

	if checksum := strings.TrimSpace(chunkChecksum); checksum != "" {
		calculated := sha256.Sum256(chunk)
		if !strings.EqualFold(checksum, hex.EncodeToString(calculated[:])) {
			return ChunkUploadResult{}, ErrChecksumMismatch
		}
	}
	if len(chunk) > 0 {
		if _, err := session.File.Write(chunk); err != nil {
			return ChunkUploadResult{}, err
		}
		if _, err := session.Hasher.Write(chunk); err != nil {
			return ChunkUploadResult{}, err
		}
		session.Received += int64(len(chunk))
	}
	if session.Received > session.MaxBytes {
		s.closeAndDeleteUploadSession(sessionKey)
		cleanupOnError = false
		return ChunkUploadResult{}, ErrUploadTooLarge
	}
	if !complete {
		return ChunkUploadResult{
			UploadID:   uploadID,
			NextOffset: session.Received,
			Completed:  false,
		}, nil
	}
	if session.Received == 0 {
		return ChunkUploadResult{}, fmt.Errorf("encrypted blob is required")
	}
	if err := s.enforceQuota(record, session.Received); err != nil {
		return ChunkUploadResult{}, err
	}

	if _, err := session.File.Seek(0, io.SeekStart); err != nil {
		return ChunkUploadResult{}, err
	}
	scanStatus := s.Scan.Inspect(ctx, fileID, session.File, session.Received)
	if scanStatus == store.MediaScanRejected {
		return ChunkUploadResult{}, ErrScanRejected
	}
	if _, err := session.File.Seek(0, io.SeekStart); err != nil {
		return ChunkUploadResult{}, err
	}
	if err := s.Blobs.Put(ctx, record.StoragePath, session.File, session.Received, "application/octet-stream"); err != nil {
		return ChunkUploadResult{}, err
	}
	finalHash := hex.EncodeToString(session.Hasher.Sum(nil))
	if err := s.Store.MarkFileUploaded(fileID, session.Received, finalHash, scanStatus, ""); err != nil {
		_ = s.Blobs.Delete(ctx, record.StoragePath)
		return ChunkUploadResult{}, mapStoreErr(err)
	}
	updated, err := s.Store.GetFile(fileID)
	if err != nil {
		return ChunkUploadResult{}, mapStoreErr(err)
	}
	info := s.toInfo(updated)
	cleanupOnError = false
	s.closeAndDeleteUploadSession(sessionKey)
	return ChunkUploadResult{
		UploadID:   uploadID,
		NextOffset: session.Received,
		Completed:  true,
		Info:       &info,
	}, nil
}

func (s *Service) DownloadEncryptedBlob(ctx context.Context, fileID string) (store.MediaFileRecord, io.ReadCloser, error) {
	return s.DownloadBlob(ctx, fileID, "")
}

// DownloadBlob returns media bytes. For open (channel) media, requesterUserID
// must be a chat member. For encrypted blobs, requesterUserID is ignored
// (legacy auth-only gate at HTTP layer) so DM/group path stays unchanged.
func (s *Service) DownloadBlob(ctx context.Context, fileID, requesterUserID string) (store.MediaFileRecord, io.ReadCloser, error) {
	if s.Blobs == nil {
		return store.MediaFileRecord{}, nil, ErrStorageUnavailable
	}
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return store.MediaFileRecord{}, nil, err
	}
	if record.SizeBytes <= 0 {
		return store.MediaFileRecord{}, nil, ErrNotFound
	}
	if record.IsOpen() {
		if requesterUserID == "" {
			return store.MediaFileRecord{}, nil, ErrForbidden
		}
		if s.Access == nil {
			return store.MediaFileRecord{}, nil, fmt.Errorf("open media access is not configured")
		}
		if err := s.Access.AllowOpenDownload(requesterUserID, record.ChatID); err != nil {
			return store.MediaFileRecord{}, nil, err
		}
	}
	switch record.ScanStatus {
	case store.MediaScanPending:
		return store.MediaFileRecord{}, nil, ErrQuarantined
	case store.MediaScanRejected:
		return store.MediaFileRecord{}, nil, ErrScanRejected
	}
	reader, err := s.Blobs.Get(ctx, record.StoragePath)
	if err != nil {
		return store.MediaFileRecord{}, nil, err
	}
	return record, reader, nil
}

func (s *Service) GetFileInfo(fileID string) (FileInfoResponse, error) {
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return FileInfoResponse{}, err
	}
	if record.SizeBytes <= 0 {
		return FileInfoResponse{}, ErrNotFound
	}
	return s.toInfo(record), nil
}

func (s *Service) DeleteFile(fileID, ownerUserID string) error {
	record, err := s.loadActiveFile(fileID)
	if err != nil {
		return err
	}
	if record.OwnerUserID != ownerUserID {
		return ErrForbidden
	}
	if err := s.Store.SoftDeleteFile(fileID, ownerUserID); err != nil {
		return mapStoreErr(err)
	}
	if s.Blobs != nil && record.StoragePath != "" {
		_ = s.Blobs.Delete(context.Background(), record.StoragePath)
	}
	return nil
}

func (s *Service) toInfo(record store.MediaFileRecord) FileInfoResponse {
	mode := record.ContentMode
	if mode == "" {
		mode = store.MediaContentEncrypted
	}
	return FileInfoResponse{
		FileID:               record.FileID,
		Kind:                 record.Kind,
		MimeType:             record.MimeType,
		SizeBytes:            record.SizeBytes,
		SizeBucket:           record.SizeBucket,
		ContentHashEncrypted: record.ContentHashEncrypted,
		ParentFileID:         record.ParentFileID,
		ContentMode:          mode,
		CDNURL:               s.CDN.PublicURL(record.FileID),
		ExpiresAt:            record.ExpiresAt,
		CreatedAt:            record.CreatedAt,
	}
}

func (s *Service) loadActiveFile(fileID string) (store.MediaFileRecord, error) {
	record, err := s.Store.GetFile(fileID)
	if err != nil {
		return store.MediaFileRecord{}, mapStoreErr(err)
	}
	if record.DeletedAt != nil {
		return store.MediaFileRecord{}, ErrNotFound
	}
	if !record.ExpiresAt.After(time.Now().UTC()) {
		return store.MediaFileRecord{}, ErrGone
	}
	return record, nil
}

func normalizeKind(raw string) (string, error) {
	kind := strings.ToLower(strings.TrimSpace(raw))
	if kind == "" {
		return KindDocument, nil
	}
	switch kind {
	case KindPhoto, KindVideo, KindDocument, KindVoice, KindAudio, KindAvatar, KindThumbnail, KindSticker:
		return kind, nil
	default:
		return "", fmt.Errorf("kind must be photo, video, document, voice, audio, avatar, thumbnail, or sticker")
	}
}

func mapStoreErr(err error) error {
	switch {
	case errors.Is(err, store.ErrNotFound):
		return ErrNotFound
	case errors.Is(err, store.ErrForbidden):
		return ErrForbidden
	case errors.Is(err, store.ErrAlreadyExists):
		return ErrAlreadyExists
	default:
		return err
	}
}

var (
	ErrNotFound           = errors.New("not found")
	ErrAlreadyExists      = errors.New("already exists")
	ErrForbidden          = errors.New("forbidden")
	ErrGone               = errors.New("gone")
	ErrStorageUnavailable = errors.New("object storage is not configured")
	ErrUploadTooLarge     = errors.New("encrypted blob exceeds size limit")
	ErrChunkTooLarge      = errors.New("chunk exceeds size limit")
	ErrChecksumMismatch   = errors.New("checksum mismatch")
	ErrScanRejected       = errors.New("blob rejected by scan sandbox")
	ErrQuarantined        = errors.New("blob is quarantined pending scan")
	ErrUserQuotaExceeded  = errors.New("user media quota exceeded")
	ErrChatQuotaExceeded  = errors.New("chat media quota exceeded")
	ErrOffsetMismatch     = errors.New("chunk offset mismatch")
)

func (s *Service) enforceQuota(record store.MediaFileRecord, incomingBytes int64) error {
	if s.Config.UserQuotaBytes > 0 {
		used, err := s.Store.TotalMediaBytesByUser(record.OwnerUserID)
		if err != nil {
			return err
		}
		if used+incomingBytes > s.Config.UserQuotaBytes {
			return ErrUserQuotaExceeded
		}
	}
	if record.ChatID != "" && s.Config.ChatQuotaBytes > 0 {
		used, err := s.Store.TotalMediaBytesByChat(record.ChatID)
		if err != nil {
			return err
		}
		if used+incomingBytes > s.Config.ChatQuotaBytes {
			return ErrChatQuotaExceeded
		}
	}
	return nil
}

func (s *Service) getOrCreateUploadSession(
	sessionKey string,
	record store.MediaFileRecord,
	ownerUserID string,
	offset int64,
) (*resumableUploadSession, error) {
	s.uploadsMu.Lock()
	defer s.uploadsMu.Unlock()
	session := s.uploads[sessionKey]
	if session != nil {
		if session.OwnerUserID != ownerUserID || session.FileID != record.FileID {
			return nil, ErrForbidden
		}
		if offset != session.Received {
			return nil, ErrOffsetMismatch
		}
		return session, nil
	}
	if offset != 0 {
		return nil, ErrOffsetMismatch
	}
	tempFile, err := os.CreateTemp("", "media-chunk-*")
	if err != nil {
		return nil, err
	}
	session = &resumableUploadSession{
		FileID:      record.FileID,
		OwnerUserID: ownerUserID,
		TempPath:    tempFile.Name(),
		File:        tempFile,
		Hasher:      sha256.New(),
		MaxBytes:    s.Config.MaxBytes(record.Kind),
	}
	s.uploads[sessionKey] = session
	return session, nil
}

func (s *Service) closeAndDeleteUploadSession(sessionKey string) {
	s.uploadsMu.Lock()
	session := s.uploads[sessionKey]
	delete(s.uploads, sessionKey)
	s.uploadsMu.Unlock()
	if session == nil {
		return
	}
	_ = session.File.Close()
	_ = os.Remove(session.TempPath)
}
