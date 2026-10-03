// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type Service struct {
	Store  Store
	Config Config
}

func NewService(store Store, cfg Config) *Service {
	return &Service{Store: store, Config: cfg}
}

func (s *Service) CreateGroup(creatorID string, req CreateGroupRequest) (GroupResponse, error) {
	count, err := s.Store.GroupsCreated24h(creatorID)
	if err != nil {
		return GroupResponse{}, err
	}
	if count >= s.Config.MaxGroupsCreated24h {
		return GroupResponse{}, fmt.Errorf("group creation rate limit exceeded")
	}
	avatarURL, err := model.NormalizeAvatarURL(req.AvatarURL)
	if err != nil {
		return GroupResponse{}, err
	}
	chat, settings, err := s.Store.CreateGroup(store.CreateGroupInput{
		Title:            strings.TrimSpace(req.Title),
		CreatorID:        creatorID,
		MemberIDs:        req.MemberIDs,
		ChatType:         model.ChatTypeGroup,
		Visibility:       model.VisibilityPrivate,
		EncryptionMode:   model.EncryptionE2E,
		PermSendMessages: store.GroupPermAll,
		AvatarURL:        avatarURL,
	})
	if err != nil {
		return GroupResponse{}, err
	}
	_ = s.Store.IncrementGroupsCreated24h(creatorID)
	s.audit(settings.GroupID, creatorID, AuditGroupCreated, "", map[string]any{
		"member_count": len(chat.MemberIDs),
	})
	_, _ = s.Store.AppendChatEvent(store.ChatEventInput{
		ChatID: chat.ID, EventType: model.ChatEventChatCreated, ActorID: creatorID,
		EntityID: chat.ID, Metadata: map[string]any{"type": "group"},
	})
	return s.buildGroupResponse(chat.ID, chat.Title, settings)
}

// CreateChannel applies product presets (variant 1) onto conversation policies (variant 3).
func (s *Service) CreateChannel(creatorID string, req CreateChannelRequest) (ChannelResponse, error) {
	title := strings.TrimSpace(req.Title)
	if title == "" {
		return ChannelResponse{}, fmt.Errorf("title is required")
	}
	visibility := strings.ToLower(strings.TrimSpace(req.Visibility))
	if visibility == "" {
		visibility = model.VisibilityPrivate
	}
	if visibility != model.VisibilityPublic && visibility != model.VisibilityPrivate {
		return ChannelResponse{}, fmt.Errorf("visibility must be public or private")
	}
	slug := normalizeSlug(req.Slug)
	if slug != "" {
		if err := validateChannelSlug(slug); err != nil {
			return ChannelResponse{}, err
		}
		ok, err := s.Store.IsChannelSlugAvailable(slug)
		if err != nil {
			return ChannelResponse{}, err
		}
		if !ok {
			return ChannelResponse{}, ErrAlreadyExists
		}
	} else if visibility == model.VisibilityPublic {
		if err := validateChannelSlug(slug); err != nil {
			return ChannelResponse{}, err
		}
	}

	count, err := s.Store.GroupsCreated24h(creatorID)
	if err != nil {
		return ChannelResponse{}, err
	}
	if count >= s.Config.MaxGroupsCreated24h {
		return ChannelResponse{}, fmt.Errorf("group creation rate limit exceeded")
	}

	avatarURL, err := model.NormalizeAvatarURL(req.AvatarURL)
	if err != nil {
		return ChannelResponse{}, err
	}

	joinInviteOnly := visibility != model.VisibilityPublic
	chat, settings, err := s.Store.CreateGroup(store.CreateGroupInput{
		Title:            title,
		CreatorID:        creatorID,
		ChatType:         model.ChatTypeChannel,
		Description:      strings.TrimSpace(req.Description),
		Visibility:       visibility,
		Slug:             slug,
		EncryptionMode:   model.EncryptionNone, // public/broadcast path: not e2e
		JoinByInviteOnly: &joinInviteOnly,
		PermSendMessages: store.GroupPermAdmin, // channel: admins publish
		PermInvite:       store.GroupPermAdmin,
		PermPin:          store.GroupPermAdmin,
		PermModerate:     store.GroupPermAdmin,
		AvatarURL:        avatarURL,
	})
	if err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			return ChannelResponse{}, ErrAlreadyExists
		}
		return ChannelResponse{}, err
	}
	_ = s.Store.IncrementGroupsCreated24h(creatorID)
	s.audit(settings.GroupID, creatorID, AuditGroupCreated, "", map[string]any{
		"type":       model.ChatTypeChannel,
		"visibility": visibility,
		"slug":       slug,
	})
	_, _ = s.Store.AppendChatEvent(store.ChatEventInput{
		ChatID: chat.ID, EventType: model.ChatEventChatCreated, ActorID: creatorID,
		EntityID: chat.ID, Metadata: map[string]any{"type": model.ChatTypeChannel, "visibility": visibility},
	})
	return s.buildChannelResponse(chat, settings)
}

func (s *Service) CheckChannelSlug(slug string) (bool, error) {
	slug = normalizeSlug(slug)
	if err := validateChannelSlug(slug); err != nil {
		return false, err
	}
	return s.Store.IsChannelSlugAvailable(slug)
}

// SearchPublicChannels — Telegram-style discover: any authenticated user can find public channels.
func (s *Service) SearchPublicChannels(query string) ([]ChannelResponse, error) {
	q := strings.TrimSpace(query)
	if q == "" {
		return nil, nil
	}
	chats, err := s.Store.SearchPublicChannels(q, 20)
	if err != nil {
		return nil, err
	}
	out := make([]ChannelResponse, 0, len(chats))
	for _, chat := range chats {
		settings, err := s.Store.GetGroupSettings(chat.ID)
		if err != nil {
			// Still return chat shell if settings missing.
			out = append(out, ChannelResponse{
				ID: chat.ID, Title: chat.Title, Type: model.ChatTypeChannel,
				Description: chat.Description, Visibility: chat.Visibility,
				Slug: chat.Slug, Encryption: chat.Encryption, AvatarURL: chat.AvatarURL,
				CreatedAt: chat.CreatedAt,
			})
			continue
		}
		resp, err := s.buildChannelResponse(chat, settings)
		if err != nil {
			continue
		}
		out = append(out, resp)
	}
	return out, nil
}

// JoinChannelBySlug adds the user as a subscriber by exact slug.
// Channels are invite-by-link: knowing /c/{slug} is enough (no public directory).
func (s *Service) JoinChannelBySlug(slug, userID string) (ChannelResponse, error) {
	chat, settings, err := s.Store.FindChannelBySlug(slug)
	if err != nil {
		return ChannelResponse{}, mapStoreErr(err)
	}
	// Already a member?
	if role, err := s.Store.GetGroupMemberRole(chat.ID, userID); err == nil && role != "" {
		return s.buildChannelResponse(chat, settings)
	}
	if _, err := s.addMemberInternal(chat.ID, userID, userID, AuditInviteJoined); err != nil {
		// Ignore key-rotation noise for non-e2e channels; membership still succeeds.
		if !errors.Is(err, ErrAlreadyExists) {
			// addMember may fail if already member race
			if role, rerr := s.Store.GetGroupMemberRole(chat.ID, userID); rerr == nil && role != "" {
				return s.buildChannelResponse(chat, settings)
			}
			return ChannelResponse{}, err
		}
	}
	// Reload members into chat
	members, _ := s.Store.ListGroupMembers(chat.ID)
	ids := make([]string, 0, len(members))
	for _, m := range members {
		ids = append(ids, m.UserID)
	}
	chat.MemberIDs = ids
	settings, _ = s.Store.GetGroupSettings(chat.ID)
	return s.buildChannelResponse(chat, settings)
}

func (s *Service) buildChannelResponse(chat model.Chat, settings store.GroupSettings) (ChannelResponse, error) {
	members, err := s.Store.ListGroupMembers(chat.ID)
	if err != nil {
		return ChannelResponse{}, mapStoreErr(err)
	}
	memberResponses := make([]MemberResponse, 0, len(members))
	ids := make([]string, 0, len(members))
	for _, member := range members {
		rights := resolveAdminRights(member.Role, member.AdminRights)
		var rightsPtr *store.AdminRights
		if store.IsPrivilegedRole(member.Role) {
			rightsPtr = &rights
		}
		memberResponses = append(memberResponses, MemberResponse{
			UserID: member.UserID, Role: member.Role, JoinedAt: member.JoinedAt, MutedUntil: member.MutedUntil,
			AdminRights: rightsPtr,
		})
		ids = append(ids, member.UserID)
	}
	return ChannelResponse{
		ID:                chat.ID,
		Title:             chat.Title,
		Type:              model.ChatTypeChannel,
		Description:       settings.Description,
		Visibility:        settings.Visibility,
		Slug:              settings.Slug,
		Encryption:        settings.EncryptionMode,
		AvatarURL:         chat.AvatarURL,
		MemberIDs:         ids,
		CreatedAt:         chat.CreatedAt,
		Members:           memberResponses,
		Settings:          settingsFromStore(settings),
		MembershipVersion: settings.MembershipVersion,
	}, nil
}

func normalizeSlug(slug string) string {
	return strings.ToLower(strings.TrimSpace(slug))
}

func validateChannelSlug(slug string) error {
	if slug == "" {
		return fmt.Errorf("slug is required for public channels")
	}
	if len(slug) < 3 || len(slug) > 32 {
		return fmt.Errorf("slug must be 3–32 characters")
	}
	for _, r := range slug {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' {
			continue
		}
		return fmt.Errorf("slug may contain only lowercase letters, digits, and underscore")
	}
	return nil
}

func (s *Service) GetGroup(groupID, viewerID string) (GroupResponse, error) {
	if err := s.ensureGroup(groupID); err != nil {
		return GroupResponse{}, err
	}
	if err := s.Store.EnsureManagedGroup(groupID, viewerID); err != nil {
		return GroupResponse{}, mapStoreErr(err)
	}
	if _, _, err := s.requireMember(groupID, viewerID); err != nil {
		return GroupResponse{}, err
	}
	chat, err := s.Store.GetChat(groupID, viewerID)
	if err != nil {
		return GroupResponse{}, mapStoreErr(err)
	}
	settings, err := s.Store.GetGroupSettings(groupID)
	if err != nil {
		return GroupResponse{}, mapStoreErr(err)
	}
	return s.buildGroupResponse(chat.ID, chat.Title, settings)
}

func (s *Service) UpdateSettings(groupID, actorID string, req UpdateSettingsRequest) (GroupResponse, error) {
	if _, err := s.requireChangeInfoRight(groupID, actorID); err != nil {
		return GroupResponse{}, err
	}
	input := store.UpdateGroupSettingsInput{
		JoinByInviteOnly: req.JoinByInviteOnly, JoinRequestsEnabled: req.JoinRequestsEnabled,
		PermInvite: req.PermInvite, PermSendMessages: req.PermSendMessages,
		PermPin: req.PermPin, PermModerate: req.PermModerate,
		PermChangeInfo: req.PermChangeInfo,
		PermComment:    req.PermComment, PermReact: req.PermReact,
	}
	if req.PermInvite != nil {
		if err := validatePermValue(*req.PermInvite); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermSendMessages != nil {
		if err := validatePermValue(*req.PermSendMessages); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermPin != nil {
		if err := validatePermValue(*req.PermPin); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermModerate != nil {
		if err := validatePermValue(*req.PermModerate); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermChangeInfo != nil {
		if err := validatePermValue(*req.PermChangeInfo); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermComment != nil {
		if err := validateEngagementPermValue(*req.PermComment); err != nil {
			return GroupResponse{}, err
		}
	}
	if req.PermReact != nil {
		if err := validateEngagementPermValue(*req.PermReact); err != nil {
			return GroupResponse{}, err
		}
	}
	settings, err := s.Store.UpdateGroupSettings(groupID, input)
	if err != nil {
		return GroupResponse{}, mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditSettingsUpdated, "", nil)
	chat, _ := s.Store.GetChat(groupID, actorID)
	return s.buildGroupResponse(groupID, chat.Title, settings)
}

func (s *Service) AddMember(groupID, actorID, targetUserID string) (MembershipChangeResponse, error) {
	if _, err := s.requireInviteRight(groupID, actorID); err != nil {
		return MembershipChangeResponse{}, err
	}
	return s.addMemberInternal(groupID, actorID, targetUserID, AuditMemberAdded)
}

func (s *Service) RemoveMember(groupID, actorID, targetUserID string) (MembershipChangeResponse, error) {
	if _, err := s.requireModeratableTarget(groupID, actorID, targetUserID); err != nil {
		return MembershipChangeResponse{}, err
	}
	if err := s.Store.RemoveGroupMember(groupID, targetUserID); err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	version, err := s.Store.BumpMembershipVersion(groupID)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	s.emitMembershipChange(groupID, actorID, targetUserID, model.ChatEventMemberRemoved, version)
	s.audit(groupID, actorID, AuditMemberRemoved, targetUserID, nil)
	return MembershipChangeResponse{GroupID: groupID, UserID: targetUserID, MembershipVersion: version, KeyRotationRequired: true}, nil
}

// DeleteGroup permanently wipes a group or channel. Owner only; DMs are rejected.
func (s *Service) DeleteGroup(groupID, actorID string) error {
	chat, err := s.Store.PeekChat(groupID)
	if err != nil {
		return mapStoreErr(err)
	}
	role, err := s.Store.GetGroupMemberRole(groupID, actorID)
	if err != nil {
		return mapStoreErr(err)
	}
	if err := canPermanentlyDeleteChat(chat.Type, role); err != nil {
		return err
	}
	if err := s.Store.PermanentDeleteChat(groupID); err != nil {
		return mapStoreErr(err)
	}
	return nil
}

func (s *Service) LeaveGroup(groupID, userID string) (MembershipChangeResponse, error) {
	if _, _, err := s.requireMember(groupID, userID); err != nil {
		return MembershipChangeResponse{}, err
	}
	if err := s.Store.RemoveGroupMember(groupID, userID); err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	version, err := s.Store.BumpMembershipVersion(groupID)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	s.emitMembershipChange(groupID, userID, userID, model.ChatEventMemberLeft, version)
	s.audit(groupID, userID, AuditMemberLeft, userID, nil)
	s.audit(groupID, userID, AuditKeyRotationNeeded, "", map[string]any{"membership_version": version})
	return MembershipChangeResponse{GroupID: groupID, UserID: userID, MembershipVersion: version, KeyRotationRequired: true}, nil
}

func (s *Service) UpdateMemberRole(groupID, actorID, targetUserID, newRole string) error {
	role, _, rights, err := s.memberContext(groupID, actorID)
	if err != nil {
		return err
	}
	targetRole, err := s.Store.GetGroupMemberRole(groupID, targetUserID)
	if err != nil {
		return mapStoreErr(err)
	}
	if targetUserID == actorID {
		return ErrForbidden
	}
	if targetRole == store.GroupRoleOwner {
		return ErrForbidden
	}
	want := strings.ToLower(strings.TrimSpace(newRole))
	if want == store.GroupRoleOwner {
		if role != store.GroupRoleOwner {
			return ErrForbidden
		}
		if err := s.Store.TransferGroupOwnership(groupID, actorID, targetUserID); err != nil {
			return mapStoreErr(err)
		}
		s.audit(groupID, actorID, AuditRoleChanged, targetUserID, map[string]any{
			"role": store.GroupRoleOwner, "transfer": true,
		})
		return nil
	}
	if !canAddAdmins(role, rights) {
		return ErrForbidden
	}
	if targetRole == store.GroupRoleAdmin && role != store.GroupRoleOwner {
		return ErrForbidden
	}
	switch want {
	case store.GroupRoleAdmin, store.GroupRoleMember:
	default:
		return fmt.Errorf("role must be admin, member, or owner")
	}
	if err := s.Store.SetGroupMemberRole(groupID, targetUserID, want); err != nil {
		return mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditRoleChanged, targetUserID, map[string]any{"role": want})
	return nil
}

func (s *Service) CreateInvite(groupID, actorID string, req CreateInviteRequest) (InviteResponse, error) {
	if _, err := s.requireInviteRight(groupID, actorID); err != nil {
		return InviteResponse{}, err
	}
	hours := req.ExpiresInHours
	if hours <= 0 {
		hours = s.Config.DefaultInviteHours
	}
	invite, err := s.Store.CreateGroupInvite(store.CreateInviteInput{
		GroupID:          groupID,
		CreatedBy:        actorID,
		Title:            strings.TrimSpace(req.Title),
		ExpiresAt:        time.Now().UTC().Add(time.Duration(hours) * time.Hour),
		MaxUses:          req.MaxUses,
		RequiresApproval: req.RequiresApproval,
	})
	if err != nil {
		return InviteResponse{}, err
	}
	s.audit(groupID, actorID, AuditInviteCreated, "", map[string]any{"invite_id": invite.ID})
	return inviteResponseFromStore(invite), nil
}

func (s *Service) ListInvites(groupID, actorID string) ([]InviteResponse, error) {
	if _, err := s.requireInviteRight(groupID, actorID); err != nil {
		return nil, err
	}
	invites, err := s.Store.ListGroupInvites(groupID)
	if err != nil {
		return nil, mapStoreErr(err)
	}
	out := make([]InviteResponse, 0, len(invites))
	for _, invite := range invites {
		out = append(out, inviteResponseFromStore(invite))
	}
	return out, nil
}

func (s *Service) RevokeInvite(groupID, actorID, inviteID string) error {
	if _, err := s.requireInviteRight(groupID, actorID); err != nil {
		return err
	}
	if err := s.Store.RevokeGroupInvite(groupID, inviteID); err != nil {
		return mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditInviteRevoked, "", map[string]any{"invite_id": inviteID})
	return nil
}

func (s *Service) PreviewInvite(token, viewerID string) (InvitePreviewResponse, error) {
	invite, err := s.Store.GetGroupInviteByToken(strings.TrimSpace(token))
	if err != nil {
		return InvitePreviewResponse{}, mapStoreErr(err)
	}
	expired := invite.RevokedAt != nil || !invite.ExpiresAt.After(time.Now().UTC()) ||
		(invite.MaxUses != nil && invite.UseCount >= *invite.MaxUses)
	chat, err := s.Store.PeekChat(invite.GroupID)
	if err != nil {
		return InvitePreviewResponse{}, mapStoreErr(err)
	}
	members, _ := s.Store.ListGroupMembers(invite.GroupID)
	already := false
	if viewerID != "" {
		if _, err := s.Store.GetGroupMemberRole(invite.GroupID, viewerID); err == nil {
			already = true
		}
	}
	return InvitePreviewResponse{
		Token:            invite.Token,
		GroupID:          invite.GroupID,
		Title:            chat.Title,
		ChatType:         chat.Type,
		Visibility:       chat.Visibility,
		Slug:             chat.Slug,
		MemberCount:      len(members),
		RequiresApproval: invite.RequiresApproval,
		AlreadyMember:    already,
		Expired:          expired,
	}, nil
}

func (s *Service) JoinByInvite(token, userID string) (MembershipChangeResponse, error) {
	invite, err := s.Store.GetGroupInviteByToken(token)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	if invite.RevokedAt != nil || !invite.ExpiresAt.After(time.Now().UTC()) {
		return MembershipChangeResponse{}, ErrGone
	}
	if invite.MaxUses != nil && invite.UseCount >= *invite.MaxUses {
		return MembershipChangeResponse{}, ErrGone
	}
	banned, err := s.Store.IsGroupBanned(invite.GroupID, userID)
	if err != nil {
		return MembershipChangeResponse{}, err
	}
	if banned {
		return MembershipChangeResponse{}, ErrForbidden
	}
	if _, err := s.Store.GetGroupMemberRole(invite.GroupID, userID); err == nil {
		return MembershipChangeResponse{}, ErrAlreadyExists
	}
	if invite.RequiresApproval {
		req, err := s.Store.CreateGroupJoinRequest(invite.GroupID, userID)
		if err != nil {
			return MembershipChangeResponse{}, mapStoreErr(err)
		}
		s.audit(invite.GroupID, userID, AuditJoinRequest, userID, map[string]any{"request_id": req.ID, "invite_id": invite.ID})
		return MembershipChangeResponse{GroupID: invite.GroupID, UserID: userID, PendingApproval: true}, nil
	}
	if err := s.Store.ConsumeGroupInvite(invite.ID); err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	resp, err := s.addMemberInternal(invite.GroupID, userID, userID, AuditInviteJoined)
	if err != nil {
		return MembershipChangeResponse{}, err
	}
	return resp, nil
}

func (s *Service) RequestJoin(groupID, userID string) (store.GroupJoinRequest, error) {
	settings, err := s.Store.GetGroupSettings(groupID)
	if err != nil {
		return store.GroupJoinRequest{}, mapStoreErr(err)
	}
	if !settings.JoinRequestsEnabled {
		return store.GroupJoinRequest{}, fmt.Errorf("join requests are disabled")
	}
	banned, err := s.Store.IsGroupBanned(groupID, userID)
	if err != nil {
		return store.GroupJoinRequest{}, err
	}
	if banned {
		return store.GroupJoinRequest{}, ErrForbidden
	}
	req, err := s.Store.CreateGroupJoinRequest(groupID, userID)
	if err != nil {
		return store.GroupJoinRequest{}, err
	}
	s.audit(groupID, userID, AuditJoinRequest, userID, map[string]any{"request_id": req.ID})
	return req, nil
}

func (s *Service) ApproveJoinRequest(groupID, actorID, requestID string) (MembershipChangeResponse, error) {
	if _, err := s.requireAdmin(groupID, actorID); err != nil {
		return MembershipChangeResponse{}, err
	}
	req, err := s.Store.ResolveGroupJoinRequest(requestID, actorID, store.JoinRequestApproved)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	if req.GroupID != groupID {
		return MembershipChangeResponse{}, ErrNotFound
	}
	resp, err := s.addMemberInternal(groupID, actorID, req.UserID, AuditJoinApproved)
	if err != nil && !errors.Is(err, ErrAlreadyExists) {
		return MembershipChangeResponse{}, err
	}
	s.audit(groupID, actorID, AuditJoinApproved, req.UserID, map[string]any{"request_id": requestID})
	return resp, nil
}

func (s *Service) RejectJoinRequest(groupID, actorID, requestID string) error {
	if _, err := s.requireAdmin(groupID, actorID); err != nil {
		return err
	}
	req, err := s.Store.ResolveGroupJoinRequest(requestID, actorID, store.JoinRequestRejected)
	if err != nil {
		return mapStoreErr(err)
	}
	if req.GroupID != groupID {
		return ErrNotFound
	}
	return nil
}

func (s *Service) ListJoinRequests(groupID, actorID string) ([]store.GroupJoinRequest, error) {
	if _, err := s.requireAdmin(groupID, actorID); err != nil {
		return nil, err
	}
	return s.Store.ListGroupJoinRequests(groupID)
}

func (s *Service) MuteMember(groupID, actorID, targetUserID string, durationMinutes int) error {
	if _, err := s.requireModeratableTarget(groupID, actorID, targetUserID); err != nil {
		return err
	}
	var until time.Time
	switch {
	case durationMinutes < 0:
		// Telegram-style "forever" restrict.
		until = time.Date(9999, 12, 31, 23, 59, 59, 0, time.UTC)
	case durationMinutes == 0:
		until = time.Now().UTC().Add(60 * time.Minute)
	default:
		until = time.Now().UTC().Add(time.Duration(durationMinutes) * time.Minute)
	}
	if err := s.Store.MuteGroupMember(groupID, targetUserID, until); err != nil {
		return mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditMemberMuted, targetUserID, map[string]any{"until": until.Format(time.RFC3339)})
	return nil
}

func (s *Service) UnmuteMember(groupID, actorID, targetUserID string) error {
	if _, err := s.requireModeratableTarget(groupID, actorID, targetUserID); err != nil {
		return err
	}
	if err := s.Store.UnmuteGroupMember(groupID, targetUserID); err != nil {
		return mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditMemberUnmuted, targetUserID, nil)
	return nil
}

func (s *Service) BanMember(groupID, actorID, targetUserID, reason string) (MembershipChangeResponse, error) {
	if _, err := s.requireModeratableTarget(groupID, actorID, targetUserID); err != nil {
		return MembershipChangeResponse{}, err
	}
	if err := s.Store.BanGroupMember(groupID, targetUserID, actorID, reason); err != nil {
		return MembershipChangeResponse{}, err
	}
	version, err := s.Store.BumpMembershipVersion(groupID)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditMemberBanned, targetUserID, map[string]any{"reason": reason})
	return MembershipChangeResponse{GroupID: groupID, UserID: targetUserID, MembershipVersion: version, KeyRotationRequired: true}, nil
}

func (s *Service) UnbanMember(groupID, actorID, targetUserID string) error {
	if _, err := s.requireAdmin(groupID, actorID); err != nil {
		return err
	}
	if err := s.Store.UnbanGroupMember(groupID, targetUserID); err != nil {
		return mapStoreErr(err)
	}
	s.audit(groupID, actorID, AuditMemberUnbanned, targetUserID, nil)
	return nil
}

func (s *Service) PinItem(groupID, actorID, itemRef string) (store.GroupPinnedItem, error) {
	if _, err := s.requirePinRight(groupID, actorID); err != nil {
		return store.GroupPinnedItem{}, err
	}
	itemRef = strings.TrimSpace(itemRef)
	if itemRef == "" {
		return store.GroupPinnedItem{}, fmt.Errorf("item_ref is required")
	}
	item, err := s.Store.PinGroupItem(groupID, itemRef, actorID)
	if err != nil {
		return store.GroupPinnedItem{}, err
	}
	s.audit(groupID, actorID, AuditItemPinned, "", map[string]any{"item_ref": itemRef, "pin_id": item.ID})
	return item, nil
}

func (s *Service) UnpinItem(groupID, actorID, itemID string) error {
	if _, err := s.requirePinRight(groupID, actorID); err != nil {
		return err
	}
	return mapStoreErr(s.Store.UnpinGroupItem(groupID, itemID))
}

func (s *Service) ListPinned(groupID, viewerID string) ([]store.GroupPinnedItem, error) {
	if _, _, err := s.requireMember(groupID, viewerID); err != nil {
		return nil, err
	}
	return s.Store.ListGroupPinnedItems(groupID)
}

func (s *Service) ListAudit(groupID, viewerID string, limit int) ([]store.GroupAuditEvent, error) {
	if _, err := s.requireAdmin(groupID, viewerID); err != nil {
		return nil, err
	}
	return s.Store.ListGroupAuditEvents(groupID, limit)
}

func (s *Service) KeyEpoch(groupID, viewerID string) (MembershipChangeResponse, error) {
	if _, _, err := s.requireMember(groupID, viewerID); err != nil {
		return MembershipChangeResponse{}, err
	}
	settings, err := s.Store.GetGroupSettings(groupID)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	return MembershipChangeResponse{
		GroupID: groupID, MembershipVersion: settings.MembershipVersion,
	}, nil
}

func (s *Service) addMemberInternal(groupID, actorID, targetUserID, auditAction string) (MembershipChangeResponse, error) {
	banned, err := s.Store.IsGroupBanned(groupID, targetUserID)
	if err != nil {
		return MembershipChangeResponse{}, err
	}
	if banned {
		return MembershipChangeResponse{}, ErrForbidden
	}
	if err := s.Store.AddGroupMember(groupID, targetUserID, store.GroupRoleMember); err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	version, err := s.Store.BumpMembershipVersion(groupID)
	if err != nil {
		return MembershipChangeResponse{}, mapStoreErr(err)
	}
	s.emitMembershipChange(groupID, actorID, targetUserID, model.ChatEventMemberJoined, version)
	s.audit(groupID, actorID, auditAction, targetUserID, map[string]any{"membership_version": version})
	s.audit(groupID, actorID, AuditKeyRotationNeeded, "", map[string]any{"membership_version": version})
	return MembershipChangeResponse{GroupID: groupID, UserID: targetUserID, MembershipVersion: version, KeyRotationRequired: true}, nil
}

func (s *Service) buildGroupResponse(groupID, title string, settings store.GroupSettings) (GroupResponse, error) {
	members, err := s.Store.ListGroupMembers(groupID)
	if err != nil {
		return GroupResponse{}, mapStoreErr(err)
	}
	memberResponses := make([]MemberResponse, 0, len(members))
	for _, member := range members {
		rights := resolveAdminRights(member.Role, member.AdminRights)
		var rightsPtr *store.AdminRights
		if store.IsPrivilegedRole(member.Role) {
			rightsPtr = &rights
		}
		memberResponses = append(memberResponses, MemberResponse{
			UserID: member.UserID, Role: member.Role, JoinedAt: member.JoinedAt, MutedUntil: member.MutedUntil,
			AdminRights: rightsPtr,
		})
	}
	return GroupResponse{
		GroupID: groupID, Title: title, Members: memberResponses,
		Settings: settingsFromStore(settings), MembershipVersion: settings.MembershipVersion,
	}, nil
}

func (s *Service) ensureGroup(groupID string) error {
	isGroup, err := s.Store.IsGroupChat(groupID)
	if err != nil {
		return mapStoreErr(err)
	}
	if !isGroup {
		return ErrNotFound
	}
	return nil
}

func (s *Service) emitMembershipChange(groupID, actorID, targetUserID, eventType string, version int) {
	_, _ = s.Store.AppendChatEvent(store.ChatEventInput{
		ChatID: groupID, EventType: eventType, ActorID: actorID, EntityID: targetUserID,
		Metadata: map[string]any{"membership_version": version, "key_rotation_required": true},
	})
}

func (s *Service) audit(groupID, actorID, action, targetUserID string, metadata map[string]any) {
	_, _ = s.Store.AppendGroupAudit(store.GroupAuditEvent{
		GroupID: groupID, ActorID: actorID, Action: action, TargetUserID: targetUserID, Metadata: metadata,
	})
}

func mapStoreErr(err error) error {
	switch {
	case errors.Is(err, store.ErrNotFound):
		return ErrNotFound
	case errors.Is(err, store.ErrForbidden):
		return ErrForbidden
	case errors.Is(err, store.ErrAlreadyExists):
		return ErrAlreadyExists
	case errors.Is(err, store.ErrGone):
		return ErrGone
	default:
		return err
	}
}

var (
	ErrNotFound      = errors.New("not found")
	ErrForbidden     = errors.New("forbidden")
	ErrAlreadyExists = errors.New("already exists")
	ErrGone          = errors.New("gone")
)
