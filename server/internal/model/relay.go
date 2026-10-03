// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

type RelayEnvelopeRequest struct {
	MailboxToken  string `json:"mailbox_token"`
	DeliveryToken string `json:"delivery_token,omitempty"`
	EnvelopeType  int    `json:"envelope_type"`
	Ciphertext    string `json:"ciphertext"`
}

type RelayMessageRequest struct {
	PairwiseID      string                 `json:"pairwise_id,omitempty"`
	ClientMessageID string                 `json:"client_message_id,omitempty"`
	Envelopes       []RelayEnvelopeRequest `json:"envelopes"`
	ExpiresAtSec    *int64                 `json:"expires_at_sec,omitempty"`
}

type RelayMessageResponse struct {
	Enqueued int      `json:"enqueued"`
	IDs      []string `json:"envelope_ids"`
}

type QueuedEnvelope struct {
	EnvelopeID   string    `json:"envelope_id"`
	MailboxToken string    `json:"mailbox_token"`
	EnvelopeType int       `json:"envelope_type"`
	Ciphertext   string    `json:"ciphertext"`
	SizeBucket   int       `json:"size_bucket"`
	CreatedAt    time.Time `json:"created_at"`
	ExpiresAt    time.Time `json:"expires_at"`
}

type MessageQueueResponse struct {
	Envelopes []QueuedEnvelope `json:"envelopes"`
}

type AckMessageQueueRequest struct {
	EnvelopeIDs []string `json:"envelope_ids"`
}

type AckMessageQueueResponse struct {
	Deleted int `json:"deleted"`
}

type EnvelopeNewData struct {
	EnvelopeID   string `json:"envelope_id"`
	MailboxToken string `json:"mailbox_token"`
}

type RotateMailboxResponse struct {
	DeviceID             string `json:"device_id"`
	MailboxToken         string `json:"mailbox_token"`
	PreviousMailboxToken string `json:"previous_mailbox_token,omitempty"`
	PreviousExpiresAt    string `json:"previous_expires_at,omitempty"`
}
