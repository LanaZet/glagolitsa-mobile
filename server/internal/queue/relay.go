// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package queue

import (
	"time"

	"glagolitsa/server/internal/store"
)

type RelayQueue interface {
	EnqueueRelayEnvelopes(envelopes []store.RelayEnvelopeInput) ([]string, error)
	ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]store.QueuedEnvelopeRecord, error)
	AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error)
}

const DefaultEnvelopeTTL = 30 * 24 * time.Hour