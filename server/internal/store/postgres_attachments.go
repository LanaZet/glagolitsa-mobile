// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func (s *PostgresStore) CreateAttachmentSlot(input CreateAttachmentInput) (AttachmentRecord, error) {
	if input.AttachmentID == "" {
		input.AttachmentID = uuid.NewString()
	}
	if input.ObjectKey == "" {
		input.ObjectKey = "attachments/" + input.AttachmentID
	}
	if input.ExpiresAt.IsZero() {
		input.ExpiresAt = NowUTC().Add(30 * 24 * time.Hour)
	}
	createdAt := NowUTC()
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO attachments (
			attachment_id, object_key, size_bytes, size_bucket, uploaded_by, expires_at, created_at
		) VALUES ($1, $2, 0, 0, $3, $4, $5)
	`, input.AttachmentID, input.ObjectKey, input.UploadedBy, input.ExpiresAt, createdAt)
	if err != nil {
		return AttachmentRecord{}, err
	}
	return AttachmentRecord{
		AttachmentID: input.AttachmentID,
		ObjectKey:    input.ObjectKey,
		UploadedBy:   input.UploadedBy,
		ExpiresAt:    input.ExpiresAt,
		CreatedAt:    createdAt,
	}, nil
}

func (s *PostgresStore) GetAttachment(attachmentID string) (AttachmentRecord, error) {
	var record AttachmentRecord
	err := s.pool.QueryRow(context.Background(), `
		SELECT attachment_id, object_key, size_bytes, size_bucket, uploaded_by, expires_at, created_at
		FROM attachments
		WHERE attachment_id = $1
	`, attachmentID).Scan(
		&record.AttachmentID,
		&record.ObjectKey,
		&record.SizeBytes,
		&record.SizeBucket,
		&record.UploadedBy,
		&record.ExpiresAt,
		&record.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return AttachmentRecord{}, ErrNotFound
	}
	return record, err
}

func (s *PostgresStore) MarkAttachmentUploaded(attachmentID string, sizeBytes int64) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE attachments
		SET size_bytes = $2,
		    size_bucket = $3
		WHERE attachment_id = $1 AND size_bytes = 0
	`, attachmentID, sizeBytes, SizeBucket(int(sizeBytes)))
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrAlreadyExists
	}
	return nil
}
