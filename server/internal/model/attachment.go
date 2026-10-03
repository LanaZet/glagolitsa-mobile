// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

type CreateAttachmentResponse struct {
	AttachmentID string `json:"attachment_id"`
	ExpiresAt    string `json:"expires_at"`
}

type AttachmentInfo struct {
	AttachmentID string    `json:"attachment_id"`
	SizeBytes    int64     `json:"size_bytes"`
	SizeBucket   int       `json:"size_bucket"`
	ExpiresAt    time.Time `json:"expires_at"`
	CreatedAt    time.Time `json:"created_at"`
}