// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package hooks

import (
	"context"

	"glagolitsa/server/internal/model"
)

// MessageHook — Mattermost app/properties/hooks.go style extension points.
type MessageHook interface {
	MessageWillBeCreated(ctx context.Context, message *model.Message) (*model.Message, error)
	MessageHasBeenCreated(ctx context.Context, message model.Message) error
}

// BaseMessageHook provides passthrough defaults.
type BaseMessageHook struct{}

func (BaseMessageHook) MessageWillBeCreated(ctx context.Context, message *model.Message) (*model.Message, error) {
	return message, nil
}

func (BaseMessageHook) MessageHasBeenCreated(ctx context.Context, message model.Message) error {
	return nil
}

// RelayHook fires after ciphertext envelopes are queued.
type RelayHook interface {
	EnvelopesRelayed(ctx context.Context, actorID string, envelopeIDs []string) error
}

type BaseRelayHook struct{}

func (BaseRelayHook) EnvelopesRelayed(ctx context.Context, actorID string, envelopeIDs []string) error {
	return nil
}