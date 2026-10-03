// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"strings"
	"time"

	"github.com/google/uuid"
)

type mediaRecord struct {
	MediaFileRecord
	data []byte
}

func (s *MemoryStore) CreateFileSlot(input CreateMediaFileInput) (MediaFileRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.mediaFiles == nil {
		s.mediaFiles = make(map[string]*mediaRecord)
	}
	if input.FileID == "" {
		input.FileID = uuid.NewString()
	}
	if input.StoragePath == "" {
		input.StoragePath = "media/" + input.Kind + "/" + input.FileID
	}
	if input.ExpiresAt.IsZero() {
		input.ExpiresAt = NowUTC().Add(30 * 24 * time.Hour)
	}
	mode := input.ContentMode
	if mode == "" {
		mode = MediaContentEncrypted
	}
	record := &mediaRecord{
		MediaFileRecord: MediaFileRecord{
			FileID:       input.FileID,
			OwnerUserID:  input.OwnerUserID,
			ChatID:       input.ChatID,
			Kind:         input.Kind,
			MimeType:     input.MimeType,
			StoragePath:  input.StoragePath,
			ParentFileID: input.ParentFileID,
			ScanStatus:   MediaScanSkipped,
			ContentMode:  mode,
			ExpiresAt:    input.ExpiresAt,
			CreatedAt:    NowUTC(),
		},
	}
	s.mediaFiles[input.FileID] = record
	return record.MediaFileRecord, nil
}

func (s *MemoryStore) GetFile(fileID string) (MediaFileRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	record, ok := s.mediaFiles[fileID]
	if !ok {
		return MediaFileRecord{}, ErrNotFound
	}
	return record.MediaFileRecord, nil
}

func (s *MemoryStore) MarkFileUploaded(fileID string, sizeBytes int64, contentHashEncrypted string, scanStatus string, mimeType string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.mediaFiles[fileID]
	if !ok {
		return ErrNotFound
	}
	if record.SizeBytes > 0 {
		return ErrAlreadyExists
	}
	record.SizeBytes = sizeBytes
	record.SizeBucket = SizeBucket(int(sizeBytes))
	record.ContentHashEncrypted = contentHashEncrypted
	if scanStatus == "" {
		scanStatus = MediaScanSkipped
	}
	record.ScanStatus = scanStatus
	if strings.TrimSpace(mimeType) != "" {
		record.MimeType = strings.TrimSpace(mimeType)
	}
	return nil
}

func (s *MemoryStore) SoftDeleteFile(fileID, ownerUserID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.mediaFiles[fileID]
	if !ok {
		return ErrNotFound
	}
	if record.OwnerUserID != ownerUserID {
		return ErrForbidden
	}
	now := NowUTC()
	record.DeletedAt = &now
	return nil
}

func (s *MemoryStore) ListExpiredFiles(before time.Time, limit int) ([]MediaFileRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []MediaFileRecord
	for _, record := range s.mediaFiles {
		if record.DeletedAt != nil || !record.ExpiresAt.After(before) {
			out = append(out, record.MediaFileRecord)
			if len(out) >= limit {
				break
			}
		}
	}
	return out, nil
}

func (s *MemoryStore) PurgeFile(fileID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.mediaFiles[fileID]; !ok {
		return ErrNotFound
	}
	delete(s.mediaFiles, fileID)
	return nil
}

func (s *MemoryStore) TotalMediaBytesByUser(ownerUserID string) (int64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	now := NowUTC()
	var total int64
	for _, record := range s.mediaFiles {
		if record.OwnerUserID != ownerUserID || record.DeletedAt != nil || !record.ExpiresAt.After(now) {
			continue
		}
		total += record.SizeBytes
	}
	return total, nil
}

func (s *MemoryStore) TotalMediaBytesByChat(chatID string) (int64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	now := NowUTC()
	var total int64
	for _, record := range s.mediaFiles {
		if record.ChatID != chatID || record.DeletedAt != nil || !record.ExpiresAt.After(now) {
			continue
		}
		total += record.SizeBytes
	}
	return total, nil
}
