// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package transport

import (
	"errors"
	"time"

	"glagolitsa/server/internal/keys"
	"glagolitsa/server/internal/store"
)

// RoutedEnvelope — результат маршрутизации: только opaque данные, без plaintext.
type RoutedEnvelope struct {
	EnvelopeID         string
	MailboxToken       string
	RecipientAccountID string
	SizeBucket         int
}

// Router — Message Router: определяет получателя и ставит в очередь. Не расшифровывает.
type Router struct {
	Keys  keys.MailboxLookup
	Queue QueueStore
}

type QueueStore interface {
	EnqueueRelayEnvelopes(envelopes []store.RelayEnvelopeInput) ([]string, error)
	RecordEnvelopeDelivery(envelopeID, recipientAccountID, mailboxToken string, sizeBucket int) error
}

func (r *Router) Route(inputs []store.RelayEnvelopeInput) ([]RoutedEnvelope, error) {
	if len(inputs) == 0 {
		return nil, errors.New("envelopes is required")
	}

	ids, err := r.Queue.EnqueueRelayEnvelopes(inputs)
	if err != nil {
		return nil, err
	}

	routed := make([]RoutedEnvelope, 0, len(inputs))
	for i, input := range inputs {
		accountID, err := r.Keys.MailboxOwnerAccountID(input.MailboxToken)
		if err != nil {
			return nil, err
		}
		envelopeID := ids[i]
		_ = r.Queue.RecordEnvelopeDelivery(envelopeID, accountID, input.MailboxToken, input.SizeBucket)
		routed = append(routed, RoutedEnvelope{
			EnvelopeID:         envelopeID,
			MailboxToken:       input.MailboxToken,
			RecipientAccountID: accountID,
			SizeBucket:         input.SizeBucket,
		})
	}
	return routed, nil
}

func BuildRelayInputs(envelopes []EnvelopeInput, expiresAtSec *int64) ([]store.RelayEnvelopeInput, error) {
	defaultTTL := 30 * 24 * time.Hour
	expiresAt := store.NowUTC().Add(defaultTTL)
	if expiresAtSec != nil && *expiresAtSec > 0 {
		expiresAt = store.NowUTC().Add(time.Duration(*expiresAtSec) * time.Second)
	}

	inputs := make([]store.RelayEnvelopeInput, 0, len(envelopes))
	for _, envelope := range envelopes {
		if envelope.MailboxToken == "" {
			return nil, errors.New("envelopes.mailbox_token is required")
		}
		if len(envelope.Ciphertext) == 0 {
			return nil, errors.New("envelopes.ciphertext is required")
		}
		if envelope.EnvelopeType != 1 && envelope.EnvelopeType != 3 {
			return nil, errors.New("envelopes.envelope_type must be 1 or 3")
		}
		inputs = append(inputs, store.RelayEnvelopeInput{
			MailboxToken: envelope.MailboxToken,
			EnvelopeType: envelope.EnvelopeType,
			Ciphertext:   envelope.Ciphertext,
			SizeBucket:   store.SizeBucket(len(envelope.Ciphertext)),
			ExpiresAt:    expiresAt,
		})
	}
	return inputs, nil
}

type EnvelopeInput struct {
	MailboxToken string
	EnvelopeType int
	Ciphertext   []byte
}