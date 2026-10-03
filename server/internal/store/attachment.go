// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import "time"

const MaxAttachmentBytes = 50 << 20 // 50 MiB encrypted blob

type AttachmentRecord struct {
	AttachmentID string
	ObjectKey    string
	SizeBytes    int64
	SizeBucket   int
	UploadedBy   string
	ExpiresAt    time.Time
	CreatedAt    time.Time
}

type CreateAttachmentInput struct {
	AttachmentID string
	ObjectKey    string
	UploadedBy   string
	ExpiresAt    time.Time
}
