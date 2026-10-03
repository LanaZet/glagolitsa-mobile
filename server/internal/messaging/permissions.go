// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"errors"
	"time"

	"glagolitsa/server/internal/store"
)

func (h *Handler) canSendToChat(chatID, userID string) (bool, error) {
	settings, err := h.Store.GetGroupSettings(chatID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return true, nil
		}
		return false, err
	}
	member, err := h.Store.GetGroupMember(chatID, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return settings.PermSendMessages != store.GroupPermAdmin, nil
		}
		return false, err
	}
	if store.IsMemberMuted(member, time.Now()) {
		return false, nil
	}
	if settings.PermSendMessages != store.GroupPermAdmin {
		return true, nil
	}
	return store.CanPostMessages(
		settings,
		member.Role,
		store.ResolveAdminRights(member.Role, member.AdminRights),
	), nil
}

func (h *Handler) canCommentInChat(chatID, userID string) (bool, error) {
	settings, err := h.Store.GetGroupSettings(chatID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return true, nil
		}
		return false, err
	}
	member, err := h.Store.GetGroupMember(chatID, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return false, nil
		}
		return false, err
	}
	if store.IsMemberMuted(member, time.Now()) {
		return false, nil
	}
	switch settings.PermComment {
	case store.GroupPermNone:
		return false, nil
	case store.GroupPermAdmin:
		return store.IsPrivilegedRole(member.Role), nil
	default: // all or empty
		return true, nil
	}
}

func (h *Handler) canReactInChat(chatID, userID string) (bool, error) {
	settings, err := h.Store.GetGroupSettings(chatID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return true, nil
		}
		return false, err
	}
	member, err := h.Store.GetGroupMember(chatID, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return false, nil
		}
		return false, err
	}
	if store.IsMemberMuted(member, time.Now()) {
		return false, nil
	}
	switch settings.PermReact {
	case store.GroupPermNone:
		return false, nil
	case store.GroupPermAdmin:
		return store.IsPrivilegedRole(member.Role), nil
	default:
		return true, nil
	}
}
