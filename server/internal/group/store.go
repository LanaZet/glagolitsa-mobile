// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type Store interface {
	CreateGroup(input store.CreateGroupInput) (model.Chat, store.GroupSettings, error)
	GetGroupSettings(groupID string) (store.GroupSettings, error)
	UpdateGroupSettings(groupID string, input store.UpdateGroupSettingsInput) (store.GroupSettings, error)
	ListGroupMembers(groupID string) ([]store.GroupMember, error)
	GetGroupMember(groupID, userID string) (store.GroupMember, error)
	GetGroupMemberRole(groupID, userID string) (string, error)
	AddGroupMember(groupID, userID, role string) error
	RemoveGroupMember(groupID, userID string) error
	SetGroupMemberRole(groupID, userID, role string) error
	TransferGroupOwnership(groupID, fromUserID, toUserID string) error
	BumpMembershipVersion(groupID string) (int, error)
	IsGroupBanned(groupID, userID string) (bool, error)
	BanGroupMember(groupID, userID, bannedBy, reason string) error
	UnbanGroupMember(groupID, userID string) error
	MuteGroupMember(groupID, userID string, until time.Time) error
	UnmuteGroupMember(groupID, userID string) error
	CreateGroupInvite(input store.CreateInviteInput) (store.GroupInvite, error)
	GetGroupInviteByToken(token string) (store.GroupInvite, error)
	ListGroupInvites(groupID string) ([]store.GroupInvite, error)
	RevokeGroupInvite(groupID, inviteID string) error
	ConsumeGroupInvite(inviteID string) error
	PeekChat(chatID string) (model.Chat, error)
	CreateGroupJoinRequest(groupID, userID string) (store.GroupJoinRequest, error)
	ListGroupJoinRequests(groupID string) ([]store.GroupJoinRequest, error)
	ResolveGroupJoinRequest(requestID, resolverID, status string) (store.GroupJoinRequest, error)
	PinGroupItem(groupID, itemRef, pinnedBy string) (store.GroupPinnedItem, error)
	UnpinGroupItem(groupID, itemID string) error
	ListGroupPinnedItems(groupID string) ([]store.GroupPinnedItem, error)
	AppendGroupAudit(event store.GroupAuditEvent) (store.GroupAuditEvent, error)
	ListGroupAuditEvents(groupID string, limit int) ([]store.GroupAuditEvent, error)
	IsGroupChat(groupID string) (bool, error)
	EnsureManagedGroup(chatID, preferredOwnerID string) error
	FindChannelBySlug(slug string) (model.Chat, store.GroupSettings, error)
	SearchPublicChannels(query string, limit int) ([]model.Chat, error)
	IsChannelSlugAvailable(slug string) (bool, error)
	IncrementGroupsCreated24h(userID string) error
	GroupsCreated24h(userID string) (int, error)
	GetChat(chatID, userID string) (model.Chat, error)
	UpdateChatAvatar(chatID, avatarURL string) error
	AppendChatEvent(event store.ChatEventInput) (model.ChatEvent, error)
	// PermanentDeleteChat wipes messages, members, and the chat row.
	PermanentDeleteChat(chatID string) error
}
