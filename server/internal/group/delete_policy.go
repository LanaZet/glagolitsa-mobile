// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// Permanent delete is owner-only for groups and channels.
// DMs are not wiped here; clients hide them locally.

func isWipeableChatType(chatType string) bool {
	return chatType == model.ChatTypeGroup || chatType == model.ChatTypeChannel
}

func canPermanentlyDeleteChat(chatType, actorRole string) error {
	if !isWipeableChatType(chatType) || actorRole != store.GroupRoleOwner {
		return ErrForbidden
	}
	return nil
}
