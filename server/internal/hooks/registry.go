// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package hooks

import (
	"context"

	"glagolitsa/server/internal/model"
)

// Registry — Mattermost RunMultiHook pattern without RPC plugins.
type Registry struct {
	messageHooks []MessageHook
	relayHooks   []RelayHook
}

func NewRegistry() *Registry {
	return &Registry{}
}

func (r *Registry) AddMessageHook(hook MessageHook) {
	if hook == nil {
		return
	}
	r.messageHooks = append(r.messageHooks, hook)
}

func (r *Registry) AddRelayHook(hook RelayHook) {
	if hook == nil {
		return
	}
	r.relayHooks = append(r.relayHooks, hook)
}

func (r *Registry) RunMessageWillBeCreated(ctx context.Context, message *model.Message) (*model.Message, error) {
	current := message
	for _, hook := range r.messageHooks {
		next, err := hook.MessageWillBeCreated(ctx, current)
		if err != nil {
			return nil, err
		}
		current = next
	}
	return current, nil
}

func (r *Registry) RunMessageHasBeenCreated(ctx context.Context, message model.Message) {
	for _, hook := range r.messageHooks {
		_ = hook.MessageHasBeenCreated(ctx, message)
	}
}

func (r *Registry) RunEnvelopesRelayed(ctx context.Context, actorID string, envelopeIDs []string) {
	for _, hook := range r.relayHooks {
		_ = hook.EnvelopesRelayed(ctx, actorID, envelopeIDs)
	}
}