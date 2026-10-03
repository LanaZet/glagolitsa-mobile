// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"time"

	"glagolitsa/server/internal/store"
)

const (
	AuditGroupCreated      = "group.created"
	AuditMemberAdded       = "member.added"
	AuditMemberRemoved     = "member.removed"
	AuditMemberLeft        = "member.left"
	AuditMemberBanned      = "member.banned"
	AuditMemberUnbanned    = "member.unbanned"
	AuditMemberMuted       = "member.muted"
	AuditMemberUnmuted     = "member.unmuted"
	AuditRoleChanged       = "member.role_changed"
	AuditSettingsUpdated   = "settings.updated"
	AuditInviteCreated     = "invite.created"
	AuditInviteRevoked     = "invite.revoked"
	AuditInviteJoined      = "invite.joined"
	AuditJoinRequest       = "join.requested"
	AuditJoinApproved      = "join.approved"
	AuditItemPinned        = "item.pinned"
	AuditKeyRotationNeeded = "key.rotation_required"
)

type CreateGroupRequest struct {
	Title     string   `json:"title"`
	MemberIDs []string `json:"member_ids,omitempty"`
	AvatarURL string   `json:"avatar_url,omitempty"`
}

// CreateChannelRequest — product surface for public/private channels (Telegram-like).
// Server applies Matrix-like policy presets under the hood.
type CreateChannelRequest struct {
	Title       string `json:"title"`
	Description string `json:"description,omitempty"`
	// Visibility: private | public (default public for channel wizard).
	Visibility string `json:"visibility,omitempty"`
	// Slug required when visibility=public (discoverable address).
	Slug      string `json:"slug,omitempty"`
	AvatarURL string `json:"avatar_url,omitempty"`
}

type GroupResponse struct {
	GroupID             string           `json:"group_id"`
	Title               string           `json:"title"`
	Members             []MemberResponse `json:"members"`
	Settings            SettingsResponse `json:"settings"`
	MembershipVersion   int              `json:"membership_version"`
	KeyRotationRequired bool             `json:"key_rotation_required,omitempty"`
}

// ChannelResponse is the create/join payload: chat shell + policies.
type ChannelResponse struct {
	ID                  string           `json:"id"`
	Title               string           `json:"title"`
	Type                string           `json:"type"`
	Description         string           `json:"description,omitempty"`
	Visibility          string           `json:"visibility,omitempty"`
	Slug                string           `json:"slug,omitempty"`
	Encryption          string           `json:"encryption,omitempty"`
	AvatarURL           string           `json:"avatar_url,omitempty"`
	MemberIDs           []string         `json:"member_ids"`
	CreatedAt           time.Time        `json:"created_at"`
	Members             []MemberResponse `json:"members,omitempty"`
	Settings            SettingsResponse `json:"settings"`
	MembershipVersion   int              `json:"membership_version"`
	KeyRotationRequired bool             `json:"key_rotation_required,omitempty"`
}

type MemberResponse struct {
	UserID      string             `json:"user_id"`
	Role        string             `json:"role"`
	JoinedAt    time.Time          `json:"joined_at"`
	MutedUntil  *time.Time         `json:"muted_until,omitempty"`
	AdminRights *store.AdminRights `json:"admin_rights,omitempty"`
}

type SettingsResponse struct {
	JoinByInviteOnly    bool   `json:"join_by_invite_only"`
	JoinRequestsEnabled bool   `json:"join_requests_enabled"`
	PermInvite          string `json:"perm_invite"`
	PermSendMessages    string `json:"perm_send_messages"`
	PermPin             string `json:"perm_pin"`
	PermModerate        string `json:"perm_moderate"`
	PermChangeInfo      string `json:"perm_change_info"`
	PermComment         string `json:"perm_comment"`
	PermReact           string `json:"perm_react"`
	Visibility          string `json:"visibility"`
	Slug                string `json:"slug,omitempty"`
	Description         string `json:"description,omitempty"`
	EncryptionMode      string `json:"encryption_mode"`
}

type UpdateSettingsRequest struct {
	JoinByInviteOnly    *bool   `json:"join_by_invite_only,omitempty"`
	JoinRequestsEnabled *bool   `json:"join_requests_enabled,omitempty"`
	PermInvite          *string `json:"perm_invite,omitempty"`
	PermSendMessages    *string `json:"perm_send_messages,omitempty"`
	PermPin             *string `json:"perm_pin,omitempty"`
	PermModerate        *string `json:"perm_moderate,omitempty"`
	PermChangeInfo      *string `json:"perm_change_info,omitempty"`
	PermComment         *string `json:"perm_comment,omitempty"`
	PermReact           *string `json:"perm_react,omitempty"`
}

type AddMemberRequest struct {
	UserID string `json:"user_id"`
}

type UpdateMemberRequest struct {
	Role string `json:"role"`
}

type CreateInviteRequest struct {
	Title             string `json:"title,omitempty"`
	ExpiresInHours    int    `json:"expires_in_hours,omitempty"`
	MaxUses           *int   `json:"max_uses,omitempty"`
	RequiresApproval  bool   `json:"requires_approval,omitempty"`
}

type InviteResponse struct {
	InviteID          string    `json:"invite_id"`
	Token             string    `json:"token"`
	GroupID           string    `json:"group_id"`
	Title             string    `json:"title,omitempty"`
	Link              string    `json:"link"`
	ExpiresAt         time.Time `json:"expires_at"`
	MaxUses           *int      `json:"max_uses,omitempty"`
	UseCount          int       `json:"use_count"`
	RequiresApproval  bool      `json:"requires_approval"`
	CreatedAt         time.Time `json:"created_at"`
}

type InvitePreviewResponse struct {
	Token             string `json:"token"`
	GroupID           string `json:"group_id"`
	Title             string `json:"title"`
	ChatType          string `json:"chat_type"`
	Visibility        string `json:"visibility,omitempty"`
	Slug              string `json:"slug,omitempty"`
	MemberCount       int    `json:"member_count"`
	RequiresApproval  bool   `json:"requires_approval"`
	AlreadyMember     bool   `json:"already_member"`
	Expired           bool   `json:"expired"`
}

type MuteMemberRequest struct {
	DurationMinutes int `json:"duration_minutes"`
}

type BanMemberRequest struct {
	Reason string `json:"reason,omitempty"`
}

type PinItemRequest struct {
	ItemRef string `json:"item_ref"`
}

type MembershipChangeResponse struct {
	GroupID             string `json:"group_id"`
	UserID              string `json:"user_id,omitempty"`
	MembershipVersion   int    `json:"membership_version"`
	KeyRotationRequired bool   `json:"key_rotation_required"`
	PendingApproval     bool   `json:"pending_approval,omitempty"`
}

const inviteLinkPrefix = "https://glagolitsa.app/join/"

func inviteResponseFromStore(invite store.GroupInvite) InviteResponse {
	return InviteResponse{
		InviteID:         invite.ID,
		Token:            invite.Token,
		GroupID:          invite.GroupID,
		Title:            invite.Title,
		Link:             inviteLinkPrefix + invite.Token,
		ExpiresAt:        invite.ExpiresAt,
		MaxUses:          invite.MaxUses,
		UseCount:         invite.UseCount,
		RequiresApproval: invite.RequiresApproval,
		CreatedAt:        invite.CreatedAt,
	}
}

func settingsFromStore(s store.GroupSettings) SettingsResponse {
	comment := s.PermComment
	if comment == "" {
		comment = store.GroupPermAll
	}
	react := s.PermReact
	if react == "" {
		react = store.GroupPermAll
	}
	changeInfo := s.PermChangeInfo
	if changeInfo == "" {
		changeInfo = store.GroupPermAdmin
	}
	return SettingsResponse{
		JoinByInviteOnly:    s.JoinByInviteOnly,
		JoinRequestsEnabled: s.JoinRequestsEnabled,
		PermInvite:          s.PermInvite,
		PermSendMessages:    s.PermSendMessages,
		PermPin:             s.PermPin,
		PermModerate:        s.PermModerate,
		PermChangeInfo:      changeInfo,
		PermComment:         comment,
		PermReact:           react,
		Visibility:          s.Visibility,
		Slug:                s.Slug,
		Description:         s.Description,
		EncryptionMode:      s.EncryptionMode,
	}
}
