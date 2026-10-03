// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"errors"
	"fmt"
	"strings"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// ChannelAccess gates open (plaintext) media for channels only.
// Encrypted DM/group path never calls this.
type ChannelAccess interface {
	// AllowOpenUpload: user may create/upload open media into chat (publisher).
	AllowOpenUpload(userID, chatID string) error
	// AllowOpenDownload: user may download open media for chat (member).
	AllowOpenDownload(userID, chatID string) error
}

// GroupChatAccess implements ChannelAccess via group/chat store.
type GroupChatAccess struct {
	Store interface {
		GetChat(chatID, userID string) (model.Chat, error)
		GetGroupSettings(groupID string) (store.GroupSettings, error)
		GetGroupMemberRole(groupID, userID string) (string, error)
	}
}

func NewGroupChatAccess(s interface {
	GetChat(chatID, userID string) (model.Chat, error)
	GetGroupSettings(groupID string) (store.GroupSettings, error)
	GetGroupMemberRole(groupID, userID string) (string, error)
}) *GroupChatAccess {
	return &GroupChatAccess{Store: s}
}

func (a *GroupChatAccess) AllowOpenUpload(userID, chatID string) error {
	if a == nil || a.Store == nil {
		return fmt.Errorf("open media access is not configured")
	}
	chatID = strings.TrimSpace(chatID)
	userID = strings.TrimSpace(userID)
	if chatID == "" || userID == "" {
		return ErrForbidden
	}
	chat, err := a.Store.GetChat(chatID, userID)
	if err != nil {
		return mapAccessErr(err)
	}
	if chat.Type != model.ChatTypeChannel {
		return fmt.Errorf("open media is only allowed for channels")
	}
	settings, err := a.Store.GetGroupSettings(chatID)
	if err != nil {
		return mapAccessErr(err)
	}
	// Channels are created with encryption_mode=none. Refuse open upload otherwise.
	if settings.EncryptionMode != model.EncryptionNone {
		return fmt.Errorf("open media requires channel encryption_mode=none")
	}
	role, err := a.Store.GetGroupMemberRole(chatID, userID)
	if err != nil {
		return mapAccessErr(err)
	}
	if settings.PermSendMessages == store.GroupPermAdmin && !store.IsPrivilegedRole(role) {
		return ErrForbidden
	}
	return nil
}

func (a *GroupChatAccess) AllowOpenDownload(userID, chatID string) error {
	if a == nil || a.Store == nil {
		return fmt.Errorf("open media access is not configured")
	}
	chatID = strings.TrimSpace(chatID)
	userID = strings.TrimSpace(userID)
	if chatID == "" || userID == "" {
		return ErrForbidden
	}
	if _, err := a.Store.GetChat(chatID, userID); err != nil {
		return mapAccessErr(err)
	}
	return nil
}

func mapAccessErr(err error) error {
	if errors.Is(err, store.ErrNotFound) {
		return ErrNotFound
	}
	if errors.Is(err, store.ErrForbidden) {
		return ErrForbidden
	}
	return err
}
