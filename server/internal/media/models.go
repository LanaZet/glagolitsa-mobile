// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import "time"

const (
	KindPhoto     = "photo"
	KindVideo     = "video"
	KindDocument  = "document"
	KindVoice     = "voice"
	KindAudio     = "audio"
	KindAvatar    = "avatar"
	KindThumbnail = "thumbnail"
	KindSticker   = "sticker"

	ScanPending  = "pending"
	ScanClean    = "clean"
	ScanRejected = "rejected"
	ScanSkipped  = "skipped"
)

type CreateSlotRequest struct {
	Kind         string `json:"kind"`
	ChatID       string `json:"chat_id,omitempty"`
	MimeType     string `json:"mime_type,omitempty"`
	ParentFileID string `json:"parent_file_id,omitempty"`
	// ContentMode: omit/encrypted = e2e path (default); "open" = channel plaintext photo.
	ContentMode string `json:"content_mode,omitempty"`
}

type CreateSlotResponse struct {
	FileID      string `json:"file_id"`
	ExpiresAt   string `json:"expires_at"`
	MaxBytes    int64  `json:"max_bytes"`
	Kind        string `json:"kind"`
	ContentMode string `json:"content_mode,omitempty"`
}

type FileInfoResponse struct {
	FileID               string `json:"file_id"`
	Kind                 string `json:"kind"`
	MimeType             string `json:"mime_type"`
	SizeBytes            int64  `json:"size_bytes"`
	SizeBucket           int    `json:"size_bucket"`
	ContentHashEncrypted string `json:"content_hash_encrypted,omitempty"`
	ParentFileID         string `json:"parent_file_id,omitempty"`
	ContentMode          string `json:"content_mode,omitempty"`
	// ThumbFileID — open-path gallery thumb (kind=thumbnail, content_mode=open).
	ThumbFileID string    `json:"thumb_file_id,omitempty"`
	CDNURL      string    `json:"cdn_url,omitempty"`
	ExpiresAt   time.Time `json:"expires_at"`
	CreatedAt   time.Time `json:"created_at"`
}

// Legacy attachment API compatibility.
type LegacyCreateAttachmentResponse struct {
	AttachmentID string `json:"attachment_id"`
	ExpiresAt    string `json:"expires_at"`
}

type LegacyAttachmentInfo struct {
	AttachmentID string    `json:"attachment_id"`
	SizeBytes    int64     `json:"size_bytes"`
	SizeBucket   int       `json:"size_bucket"`
	ExpiresAt    time.Time `json:"expires_at"`
	CreatedAt    time.Time `json:"created_at"`
}
