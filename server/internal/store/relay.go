// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"time"

	"github.com/google/uuid"
)

type RelayEnvelopeInput struct {
	MailboxToken string
	EnvelopeType int
	Ciphertext   []byte
	SizeBucket   int
	ExpiresAt    time.Time
}

type QueuedEnvelopeRecord struct {
	EnvelopeID   string
	MailboxToken string
	EnvelopeType int
	Ciphertext   []byte
	SizeBucket   int
	CreatedAt    time.Time
	ExpiresAt    time.Time
}

func SizeBucket(byteLength int) int {
	buckets := []int{512, 1024, 2048, 4096, 8192, 16384, 32768}
	for _, bucket := range buckets {
		if byteLength <= bucket {
			return bucket
		}
	}
	return ((byteLength + 4095) / 4096) * 4096
}

func NewMailboxToken() string {
	return uuid.NewString()
}
