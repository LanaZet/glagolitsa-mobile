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

func (s *PostgresStore) CreateFileSlot(input CreateMediaFileInput) (MediaFileRecord, error) {
	if input.FileID == "" {
		input.FileID = uuid.NewString()
	}
	if input.StoragePath == "" {
		input.StoragePath = "media/" + input.Kind + "/" + input.FileID
	}
	if input.MimeType == "" {
		input.MimeType = "application/octet-stream"
	}
	if input.ExpiresAt.IsZero() {
		input.ExpiresAt = NowUTC().Add(30 * 24 * time.Hour)
	}
	createdAt := NowUTC()
	var chatID any
	if input.ChatID != "" {
		chatID = input.ChatID
	}
	var parentFileID any
	if input.ParentFileID != "" {
		parentFileID = input.ParentFileID
	}
	mode := input.ContentMode
	if mode == "" {
		mode = MediaContentEncrypted
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO media_files (
			file_id, owner_user_id, chat_id, kind, mime_type, storage_path,
			size_bytes, size_bucket, parent_file_id, scan_status, content_mode, expires_at, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, 0, 0, $7, $8, $9, $10, $11)
	`, input.FileID, input.OwnerUserID, chatID, input.Kind, input.MimeType, input.StoragePath,
		parentFileID, MediaScanSkipped, mode, input.ExpiresAt, createdAt)
	if err != nil {
		// Pre-031 fallback without content_mode.
		_, err = s.pool.Exec(context.Background(), `
			INSERT INTO media_files (
				file_id, owner_user_id, chat_id, kind, mime_type, storage_path,
				size_bytes, size_bucket, parent_file_id, scan_status, expires_at, created_at
			) VALUES ($1, $2, $3, $4, $5, $6, 0, 0, $7, $8, $9, $10)
		`, input.FileID, input.OwnerUserID, chatID, input.Kind, input.MimeType, input.StoragePath,
			parentFileID, MediaScanSkipped, input.ExpiresAt, createdAt)
		if err != nil {
			return MediaFileRecord{}, err
		}
	}
	return MediaFileRecord{
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
		CreatedAt:    createdAt,
	}, nil
}

func (s *PostgresStore) GetFile(fileID string) (MediaFileRecord, error) {
	var record MediaFileRecord
	var chatID, parentFileID, contentHash *string
	var deletedAt *time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT file_id, owner_user_id, chat_id, kind, mime_type, storage_path,
		       size_bytes, size_bucket, content_hash_encrypted, parent_file_id,
		       scan_status, COALESCE(content_mode, 'encrypted'), deleted_at, expires_at, created_at
		FROM media_files
		WHERE file_id = $1
	`, fileID).Scan(
		&record.FileID,
		&record.OwnerUserID,
		&chatID,
		&record.Kind,
		&record.MimeType,
		&record.StoragePath,
		&record.SizeBytes,
		&record.SizeBucket,
		&contentHash,
		&parentFileID,
		&record.ScanStatus,
		&record.ContentMode,
		&deletedAt,
		&record.ExpiresAt,
		&record.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return MediaFileRecord{}, ErrNotFound
	}
	if err != nil {
		// Pre-031 schema without content_mode.
		err = s.pool.QueryRow(context.Background(), `
			SELECT file_id, owner_user_id, chat_id, kind, mime_type, storage_path,
			       size_bytes, size_bucket, content_hash_encrypted, parent_file_id,
			       scan_status, deleted_at, expires_at, created_at
			FROM media_files
			WHERE file_id = $1
		`, fileID).Scan(
			&record.FileID,
			&record.OwnerUserID,
			&chatID,
			&record.Kind,
			&record.MimeType,
			&record.StoragePath,
			&record.SizeBytes,
			&record.SizeBucket,
			&contentHash,
			&parentFileID,
			&record.ScanStatus,
			&deletedAt,
			&record.ExpiresAt,
			&record.CreatedAt,
		)
		if errors.Is(err, pgx.ErrNoRows) {
			return MediaFileRecord{}, ErrNotFound
		}
		if err != nil {
			return MediaFileRecord{}, err
		}
		record.ContentMode = MediaContentEncrypted
	}
	if chatID != nil {
		record.ChatID = *chatID
	}
	if parentFileID != nil {
		record.ParentFileID = *parentFileID
	}
	if contentHash != nil {
		record.ContentHashEncrypted = *contentHash
	}
	if record.ContentMode == "" {
		record.ContentMode = MediaContentEncrypted
	}
	record.DeletedAt = deletedAt
	return record, nil
}

func (s *PostgresStore) MarkFileUploaded(fileID string, sizeBytes int64, contentHashEncrypted string, scanStatus string, mimeType string) error {
	if scanStatus == "" {
		scanStatus = MediaScanSkipped
	}
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE media_files
		SET size_bytes = $2,
		    size_bucket = $3,
		    content_hash_encrypted = NULLIF($4, ''),
		    scan_status = $5,
		    mime_type = CASE WHEN NULLIF($6, '') IS NULL THEN mime_type ELSE $6 END
		WHERE file_id = $1 AND size_bytes = 0 AND deleted_at IS NULL
	`, fileID, sizeBytes, SizeBucket(int(sizeBytes)), contentHashEncrypted, scanStatus, mimeType)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrAlreadyExists
	}
	return nil
}

func (s *PostgresStore) TotalMediaBytesByUser(ownerUserID string) (int64, error) {
	var total int64
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(SUM(size_bytes), 0)
		FROM media_files
		WHERE owner_user_id = $1
		  AND deleted_at IS NULL
		  AND expires_at > NOW()
	`, ownerUserID).Scan(&total)
	return total, err
}

func (s *PostgresStore) TotalMediaBytesByChat(chatID string) (int64, error) {
	var total int64
	err := s.pool.QueryRow(context.Background(), `
		SELECT COALESCE(SUM(size_bytes), 0)
		FROM media_files
		WHERE chat_id = $1
		  AND deleted_at IS NULL
		  AND expires_at > NOW()
	`, chatID).Scan(&total)
	return total, err
}

func (s *PostgresStore) SoftDeleteFile(fileID, ownerUserID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE media_files
		SET deleted_at = NOW()
		WHERE file_id = $1 AND owner_user_id = $2 AND deleted_at IS NULL
	`, fileID, ownerUserID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) ListExpiredFiles(before time.Time, limit int) ([]MediaFileRecord, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT file_id, owner_user_id, chat_id, kind, mime_type, storage_path,
		       size_bytes, size_bucket, content_hash_encrypted, parent_file_id,
		       scan_status, deleted_at, expires_at, created_at
		FROM media_files
		WHERE expires_at <= $1 OR deleted_at IS NOT NULL
		ORDER BY expires_at ASC
		LIMIT $2
	`, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []MediaFileRecord
	for rows.Next() {
		var record MediaFileRecord
		var chatID, parentFileID, contentHash *string
		var deletedAt *time.Time
		if err := rows.Scan(
			&record.FileID,
			&record.OwnerUserID,
			&chatID,
			&record.Kind,
			&record.MimeType,
			&record.StoragePath,
			&record.SizeBytes,
			&record.SizeBucket,
			&contentHash,
			&parentFileID,
			&record.ScanStatus,
			&deletedAt,
			&record.ExpiresAt,
			&record.CreatedAt,
		); err != nil {
			return nil, err
		}
		if chatID != nil {
			record.ChatID = *chatID
		}
		if parentFileID != nil {
			record.ParentFileID = *parentFileID
		}
		if contentHash != nil {
			record.ContentHashEncrypted = *contentHash
		}
		record.DeletedAt = deletedAt
		out = append(out, record)
	}
	return out, rows.Err()
}

func (s *PostgresStore) PurgeFile(fileID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM media_files WHERE file_id = $1
	`, fileID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
