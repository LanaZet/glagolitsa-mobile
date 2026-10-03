// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"errors"
	"fmt"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

var ErrInvalidChatUpdate = errors.New("invalid chat update")

type ChatMetadataService struct {
	Store Store
	Hub   chatUpdateBroadcaster
}

type chatUpdateBroadcaster interface {
	BroadcastChatUpdated(memberIDs []string, chat model.Chat)
}

func NewChatMetadataService(store Store, hub chatUpdateBroadcaster) *ChatMetadataService {
	return &ChatMetadataService{Store: store, Hub: hub}
}

func (s *ChatMetadataService) UpdateAvatar(chatID, actorID, rawAvatarURL string) (model.Chat, error) {
	chat, err := s.Store.GetChat(chatID, actorID)
	if err != nil {
		return model.Chat{}, err
	}
	if chat.Type != model.ChatTypeGroup && chat.Type != model.ChatTypeChannel {
		return model.Chat{}, fmt.Errorf("%w: only groups and channels can have an icon", ErrInvalidChatUpdate)
	}

	avatarURL, err := model.NormalizeAvatarURL(rawAvatarURL)
	if err != nil {
		return model.Chat{}, fmt.Errorf("%w: %v", ErrInvalidChatUpdate, err)
	}
	if ok, err := s.canChangeChatInfo(chatID, actorID); err != nil {
		return model.Chat{}, err
	} else if !ok {
		return model.Chat{}, store.ErrForbidden
	}
	if err := s.Store.UpdateChatAvatar(chatID, avatarURL); err != nil {
		return model.Chat{}, err
	}
	chat.AvatarURL = avatarURL
	if s.Hub != nil {
		s.Hub.BroadcastChatUpdated(chat.MemberIDs, chat)
	}
	return chat, nil
}

func (s *ChatMetadataService) canChangeChatInfo(chatID, userID string) (bool, error) {
	settings, err := s.Store.GetGroupSettings(chatID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return true, nil
		}
		return false, err
	}
	member, err := s.Store.GetGroupMember(chatID, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return false, nil
		}
		return false, err
	}
	return store.CanChangeInfo(
		settings,
		member.Role,
		store.ResolveAdminRights(member.Role, member.AdminRights),
	), nil
}
