// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"time"

	"glagolitsa/server/internal/store"
)

type Store interface {
	CreateFileSlot(input store.CreateMediaFileInput) (store.MediaFileRecord, error)
	GetFile(fileID string) (store.MediaFileRecord, error)
	// mimeType optional: when non-empty updates mime_type (open photo sniff).
	MarkFileUploaded(fileID string, sizeBytes int64, contentHashEncrypted string, scanStatus string, mimeType string) error
	SoftDeleteFile(fileID, ownerUserID string) error
	ListExpiredFiles(before time.Time, limit int) ([]store.MediaFileRecord, error)
	PurgeFile(fileID string) error
	TotalMediaBytesByUser(ownerUserID string) (int64, error)
	TotalMediaBytesByChat(chatID string) (int64, error)
}
