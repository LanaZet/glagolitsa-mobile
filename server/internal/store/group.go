// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import "time"

const (
	GroupRoleOwner  = "owner"
	GroupRoleAdmin  = "admin"
	GroupRoleMember = "member"

	GroupPermAdmin = "admin"
	GroupPermAll   = "all"

	JoinRequestPending  = "pending"
	JoinRequestApproved = "approved"
	JoinRequestRejected = "rejected"
)

// Group permission values: admin | all | none (none only for comment/react).
const (
	GroupPermNone = "none"
)

type GroupSettings struct {
	GroupID             string
	MembershipVersion   int
	JoinByInviteOnly    bool
	JoinRequestsEnabled bool
	PermInvite          string
	PermSendMessages    string
	PermPin             string
	PermModerate        string
	PermChangeInfo      string // admin | all
	PermComment         string // admin | all | none
	PermReact           string // admin | all | none
	// Hybrid channel/group policies (Matrix-like conversation state).
	Visibility     string // private | public
	Slug           string
	Description    string
	EncryptionMode string // e2e | none
	UpdatedAt      time.Time
}

type GroupMember struct {
	UserID      string
	Role        string
	JoinedAt    time.Time
	MutedUntil  *time.Time
	AdminRights *AdminRights
}

// AdminRights is the Telegram admin flag set. Owner always has every flag.
type AdminRights struct {
	ChangeInfo     bool `json:"change_info"`
	DeleteMessages bool `json:"delete_messages"`
	BanUsers       bool `json:"ban_users"`
	InviteUsers    bool `json:"invite_users"`
	PinMessages    bool `json:"pin_messages"`
	AddAdmins      bool `json:"add_admins"`
	PostMessages   bool `json:"post_messages"`
	EditMessages   bool `json:"edit_messages"`
}

func DefaultAdminRights() AdminRights {
	return AdminRights{
		ChangeInfo:     true,
		DeleteMessages: true,
		BanUsers:       true,
		InviteUsers:    true,
		PinMessages:    true,
		AddAdmins:      false,
		PostMessages:   true,
		EditMessages:   false,
	}
}

func OwnerAdminRights() AdminRights {
	rights := DefaultAdminRights()
	rights.AddAdmins = true
	rights.EditMessages = true
	return rights
}

func ResolveAdminRights(role string, stored *AdminRights) AdminRights {
	if role == GroupRoleOwner {
		return OwnerAdminRights()
	}
	if role == GroupRoleAdmin {
		if stored != nil {
			return *stored
		}
		return DefaultAdminRights()
	}
	return AdminRights{}
}

func IsPrivilegedRole(role string) bool {
	return role == GroupRoleOwner || role == GroupRoleAdmin
}

func IsMemberMuted(member GroupMember, now time.Time) bool {
	return member.MutedUntil != nil && member.MutedUntil.After(now.UTC())
}

func CanPostMessages(settings GroupSettings, role string, rights AdminRights) bool {
	if role == GroupRoleOwner {
		return true
	}
	if role == GroupRoleAdmin {
		return rights.PostMessages
	}
	return settings.PermSendMessages == GroupPermAll
}

func CanChangeInfo(settings GroupSettings, role string, rights AdminRights) bool {
	if role == GroupRoleOwner {
		return true
	}
	if role == GroupRoleAdmin {
		return rights.ChangeInfo
	}
	return settings.PermChangeInfo == GroupPermAll
}

type GroupInvite struct {
	ID               string
	GroupID          string
	Token            string
	CreatedBy        string
	Title            string
	ExpiresAt        time.Time
	MaxUses          *int
	UseCount         int
	RequiresApproval bool
	RevokedAt        *time.Time
	CreatedAt        time.Time
}

type CreateInviteInput struct {
	GroupID          string
	CreatedBy        string
	Title            string
	ExpiresAt        time.Time
	MaxUses          *int
	RequiresApproval bool
}

type GroupJoinRequest struct {
	ID         string
	GroupID    string
	UserID     string
	Status     string
	CreatedAt  time.Time
	ResolvedAt *time.Time
	ResolvedBy string
}

type GroupBan struct {
	GroupID  string
	UserID   string
	BannedBy string
	Reason   string
	BannedAt time.Time
}

type GroupPinnedItem struct {
	ID       string
	GroupID  string
	ItemRef  string
	PinnedBy string
	PinnedAt time.Time
}

type GroupAuditEvent struct {
	ID           string
	GroupID      string
	ActorID      string
	Action       string
	TargetUserID string
	Metadata     map[string]any
	CreatedAt    time.Time
}

type CreateGroupInput struct {
	GroupID             string
	Title               string
	CreatorID           string
	MemberIDs           []string
	ChatType            string // group | channel (default group)
	Description         string
	Visibility          string // private | public
	Slug                string
	EncryptionMode      string // e2e | none
	JoinByInviteOnly    *bool
	JoinRequestsEnabled *bool
	PermInvite          string
	PermSendMessages    string
	PermPin             string
	PermModerate        string
	PermChangeInfo      string
	AvatarURL           string
}

type UpdateGroupSettingsInput struct {
	JoinByInviteOnly    *bool
	JoinRequestsEnabled *bool
	PermInvite          *string
	PermSendMessages    *string
	PermPin             *string
	PermModerate        *string
	PermChangeInfo      *string
	PermComment         *string
	PermReact           *string
	Visibility          *string
	Slug                *string
	Description         *string
	EncryptionMode      *string
}
