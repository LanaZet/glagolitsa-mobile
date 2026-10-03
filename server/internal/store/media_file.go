// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import "time"

const (
	MediaKindPhoto     = "photo"
	MediaKindVideo     = "video"
	MediaKindDocument  = "document"
	MediaKindVoice     = "voice"
	MediaKindAudio     = "audio"
	MediaKindAvatar    = "avatar"
	MediaKindThumbnail = "thumbnail"
	MediaKindSticker   = "sticker"

	MediaScanPending  = "pending"
	MediaScanClean    = "clean"
	MediaScanRejected = "rejected"
	MediaScanSkipped  = "skipped"

	// Content modes — keep encrypted default so DM/group never change by accident.
	MediaContentEncrypted = "encrypted"
	MediaContentOpen      = "open"
)

type MediaFileRecord struct {
	FileID               string
	OwnerUserID          string
	ChatID               string
	Kind                 string
	MimeType             string
	StoragePath          string
	SizeBytes            int64
	SizeBucket           int
	ContentHashEncrypted string
	ParentFileID         string
	ScanStatus           string
	ContentMode          string // encrypted | open
	DeletedAt            *time.Time
	ExpiresAt            time.Time
	CreatedAt            time.Time
}

func (r MediaFileRecord) IsOpen() bool {
	return r.ContentMode == MediaContentOpen
}

type CreateMediaFileInput struct {
	FileID       string
	OwnerUserID  string
	ChatID       string
	Kind         string
	MimeType     string
	StoragePath  string
	ParentFileID string
	ContentMode  string // encrypted (default) | open
	ExpiresAt    time.Time
}
