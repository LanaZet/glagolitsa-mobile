// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"time"

	"github.com/google/uuid"
)

type attachmentRecord struct {
	AttachmentRecord
	data []byte
}

func (s *MemoryStore) CreateAttachmentSlot(input CreateAttachmentInput) (AttachmentRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.attachments == nil {
		s.attachments = make(map[string]*attachmentRecord)
	}
	if input.AttachmentID == "" {
		input.AttachmentID = uuid.NewString()
	}
	if input.ObjectKey == "" {
		input.ObjectKey = "attachments/" + input.AttachmentID
	}
	if input.ExpiresAt.IsZero() {
		input.ExpiresAt = NowUTC().Add(30 * 24 * time.Hour)
	}
	record := &attachmentRecord{
		AttachmentRecord: AttachmentRecord{
			AttachmentID: input.AttachmentID,
			ObjectKey:    input.ObjectKey,
			UploadedBy:   input.UploadedBy,
			ExpiresAt:    input.ExpiresAt,
			CreatedAt:    NowUTC(),
		},
	}
	s.attachments[input.AttachmentID] = record
	return record.AttachmentRecord, nil
}

func (s *MemoryStore) GetAttachment(attachmentID string) (AttachmentRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	record, ok := s.attachments[attachmentID]
	if !ok {
		return AttachmentRecord{}, ErrNotFound
	}
	return record.AttachmentRecord, nil
}

func (s *MemoryStore) MarkAttachmentUploaded(attachmentID string, sizeBytes int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.attachments[attachmentID]
	if !ok {
		return ErrNotFound
	}
	if record.SizeBytes > 0 {
		return ErrAlreadyExists
	}
	record.SizeBytes = sizeBytes
	record.SizeBucket = SizeBucket(int(sizeBytes))
	return nil
}

func (s *MemoryStore) PutAttachmentData(attachmentID string, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.attachments[attachmentID]
	if !ok {
		return ErrNotFound
	}
	record.data = append([]byte(nil), data...)
	record.SizeBytes = int64(len(data))
	record.SizeBucket = SizeBucket(len(data))
	return nil
}

func (s *MemoryStore) GetAttachmentData(attachmentID string) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	record, ok := s.attachments[attachmentID]
	if !ok || len(record.data) == 0 {
		return nil, ErrNotFound
	}
	return append([]byte(nil), record.data...), nil
}
