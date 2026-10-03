// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package hooks

import (
	"context"
	"log/slog"

	"glagolitsa/server/internal/model"
)

// AuditMessageHook logs message lifecycle (Mattermost plugin hook analogue).
type AuditMessageHook struct {
	BaseMessageHook
}

func (AuditMessageHook) MessageHasBeenCreated(ctx context.Context, message model.Message) error {
	slog.Info("hook_message_created",
		"chat_id", message.ChatID,
		"message_id", message.ID,
		"sender_id", message.SenderID,
		"encrypted", message.Ciphertext != "",
	)
	return nil
}