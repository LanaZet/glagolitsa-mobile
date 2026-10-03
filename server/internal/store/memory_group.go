// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) CreateGroup(input CreateGroupInput) (model.Chat, GroupSettings, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if input.Title == "" {
		return model.Chat{}, GroupSettings{}, errors.New("title is required")
	}
	chatType := input.ChatType
	if chatType == "" {
		chatType = model.ChatTypeGroup
	}
	if chatType != model.ChatTypeGroup && chatType != model.ChatTypeChannel {
		return model.Chat{}, GroupSettings{}, errors.New("invalid chat type")
	}
	if s.chats == nil {
		s.chats = make(map[string]*model.Chat)
	}
	if s.groupSettings == nil {
		s.groupSettings = make(map[string]GroupSettings)
	}
	if s.groupMembers == nil {
		s.groupMembers = make(map[string]map[string]GroupMember)
	}
	if input.GroupID == "" {
		input.GroupID = uuid.NewString()
	}
	// Unique slug check for channels.
	if input.Slug != "" {
		want := normalizeChannelSlug(input.Slug)
		for _, gs := range s.groupSettings {
			if normalizeChannelSlug(gs.Slug) == want {
				return model.Chat{}, GroupSettings{}, ErrAlreadyExists
			}
		}
	}
	createdAt := NowUTC()
	memberSet := map[string]struct{}{input.CreatorID: {}}
	for _, id := range input.MemberIDs {
		if id != "" {
			memberSet[id] = struct{}{}
		}
	}
	memberIDs := make([]string, 0, len(memberSet))
	members := make(map[string]GroupMember)
	for memberID := range memberSet {
		role := GroupRoleMember
		if memberID == input.CreatorID {
			role = GroupRoleOwner
		}
		members[memberID] = GroupMember{UserID: memberID, Role: role, JoinedAt: createdAt}
		memberIDs = append(memberIDs, memberID)
	}
	settings := defaultGroupSettings(input, createdAt)
	settings.GroupID = input.GroupID
	settings.MembershipVersion = 1
	chat := &model.Chat{
		ID:          input.GroupID,
		Title:       input.Title,
		Type:        chatType,
		MemberIDs:   memberIDs,
		CreatedAt:   createdAt,
		Description: settings.Description,
		Visibility:  settings.Visibility,
		Slug:        settings.Slug,
		Encryption:  settings.EncryptionMode,
		AvatarURL:   input.AvatarURL,
	}
	s.chats[input.GroupID] = chat
	s.groupMembers[input.GroupID] = members
	s.groupSettings[input.GroupID] = settings
	return *chat, settings, nil
}

func (s *MemoryStore) GetGroupSettings(groupID string) (GroupSettings, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	settings, ok := s.groupSettings[groupID]
	if !ok {
		return GroupSettings{}, ErrNotFound
	}
	return settings, nil
}

func (s *MemoryStore) UpdateGroupSettings(groupID string, input UpdateGroupSettingsInput) (GroupSettings, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	settings, ok := s.groupSettings[groupID]
	if !ok {
		return GroupSettings{}, ErrNotFound
	}
	if input.JoinByInviteOnly != nil {
		settings.JoinByInviteOnly = *input.JoinByInviteOnly
	}
	if input.JoinRequestsEnabled != nil {
		settings.JoinRequestsEnabled = *input.JoinRequestsEnabled
	}
	if input.PermInvite != nil {
		settings.PermInvite = *input.PermInvite
	}
	if input.PermSendMessages != nil {
		settings.PermSendMessages = *input.PermSendMessages
	}
	if input.PermPin != nil {
		settings.PermPin = *input.PermPin
	}
	if input.PermModerate != nil {
		settings.PermModerate = *input.PermModerate
	}
	if input.PermChangeInfo != nil {
		settings.PermChangeInfo = *input.PermChangeInfo
	}
	if input.PermComment != nil {
		settings.PermComment = *input.PermComment
	}
	if input.PermReact != nil {
		settings.PermReact = *input.PermReact
	}
	if input.Visibility != nil {
		settings.Visibility = *input.Visibility
	}
	if input.Slug != nil {
		settings.Slug = *input.Slug
	}
	if input.Description != nil {
		settings.Description = *input.Description
	}
	if input.EncryptionMode != nil {
		settings.EncryptionMode = *input.EncryptionMode
	}
	settings.UpdatedAt = NowUTC()
	s.groupSettings[groupID] = settings
	return settings, nil
}

func (s *MemoryStore) ListGroupMembers(groupID string) ([]GroupMember, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return nil, ErrNotFound
	}
	out := make([]GroupMember, 0, len(members))
	for _, member := range members {
		out = append(out, member)
	}
	return out, nil
}

func (s *MemoryStore) GetGroupMember(groupID, userID string) (GroupMember, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return GroupMember{}, ErrNotFound
	}
	member, ok := members[userID]
	if !ok {
		return GroupMember{}, ErrNotFound
	}
	return member, nil
}

func (s *MemoryStore) GetGroupMemberRole(groupID, userID string) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return "", ErrNotFound
	}
	member, ok := members[userID]
	if !ok {
		return "", ErrNotFound
	}
	return member.Role, nil
}

func (s *MemoryStore) AddGroupMember(groupID, userID, role string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	if _, exists := members[userID]; exists {
		return ErrAlreadyExists
	}
	members[userID] = GroupMember{UserID: userID, Role: role, JoinedAt: NowUTC()}
	s.groupMembers[groupID] = members
	if chat, ok := s.chats[groupID]; ok {
		chat.MemberIDs = append(chat.MemberIDs, userID)
	}
	return nil
}

func (s *MemoryStore) RemoveGroupMember(groupID, userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	if _, exists := members[userID]; !exists {
		return ErrNotFound
	}
	delete(members, userID)
	if chat, ok := s.chats[groupID]; ok {
		filtered := make([]string, 0, len(chat.MemberIDs))
		for _, id := range chat.MemberIDs {
			if id != userID {
				filtered = append(filtered, id)
			}
		}
		chat.MemberIDs = filtered
	}
	return nil
}

func (s *MemoryStore) SetGroupMemberRole(groupID, userID, role string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	member, ok := members[userID]
	if !ok {
		return ErrNotFound
	}
	member.Role = role
	members[userID] = member
	return nil
}

func (s *MemoryStore) TransferGroupOwnership(groupID, fromUserID, toUserID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	from, ok := members[fromUserID]
	if !ok {
		return ErrNotFound
	}
	to, ok := members[toUserID]
	if !ok {
		return ErrNotFound
	}
	from.Role = GroupRoleAdmin
	to.Role = GroupRoleOwner
	members[fromUserID] = from
	members[toUserID] = to
	return nil
}

func (s *MemoryStore) BumpMembershipVersion(groupID string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	settings, ok := s.groupSettings[groupID]
	if !ok {
		return 0, ErrNotFound
	}
	settings.MembershipVersion++
	settings.UpdatedAt = NowUTC()
	s.groupSettings[groupID] = settings
	return settings.MembershipVersion, nil
}

func (s *MemoryStore) IsGroupBanned(groupID, userID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.groupBans == nil {
		return false, nil
	}
	bans, ok := s.groupBans[groupID]
	if !ok {
		return false, nil
	}
	_, banned := bans[userID]
	return banned, nil
}

func (s *MemoryStore) BanGroupMember(groupID, userID, bannedBy, reason string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupBans == nil {
		s.groupBans = make(map[string]map[string]GroupBan)
	}
	_ = s.removeGroupMemberLocked(groupID, userID)
	bans := s.groupBans[groupID]
	if bans == nil {
		bans = make(map[string]GroupBan)
	}
	bans[userID] = GroupBan{GroupID: groupID, UserID: userID, BannedBy: bannedBy, Reason: reason, BannedAt: NowUTC()}
	s.groupBans[groupID] = bans
	return nil
}

func (s *MemoryStore) UnbanGroupMember(groupID, userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	bans, ok := s.groupBans[groupID]
	if !ok {
		return ErrNotFound
	}
	if _, ok := bans[userID]; !ok {
		return ErrNotFound
	}
	delete(bans, userID)
	return nil
}

func (s *MemoryStore) MuteGroupMember(groupID, userID string, until time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	member, ok := members[userID]
	if !ok {
		return ErrNotFound
	}
	member.MutedUntil = &until
	members[userID] = member
	return nil
}

func (s *MemoryStore) UnmuteGroupMember(groupID, userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	member, ok := members[userID]
	if !ok {
		return ErrNotFound
	}
	member.MutedUntil = nil
	members[userID] = member
	return nil
}

func (s *MemoryStore) CreateGroupInvite(input CreateInviteInput) (GroupInvite, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupInvitesByToken == nil {
		s.groupInvitesByToken = make(map[string]GroupInvite)
	}
	token, err := randomInviteToken()
	if err != nil {
		return GroupInvite{}, err
	}
	invite := GroupInvite{
		ID: uuid.NewString(), GroupID: input.GroupID, Token: token, CreatedBy: input.CreatedBy,
		Title: input.Title, ExpiresAt: input.ExpiresAt, MaxUses: input.MaxUses,
		RequiresApproval: input.RequiresApproval, CreatedAt: NowUTC(),
	}
	s.groupInvitesByToken[token] = invite
	return invite, nil
}

func (s *MemoryStore) ListGroupInvites(groupID string) ([]GroupInvite, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []GroupInvite
	for _, invite := range s.groupInvitesByToken {
		if invite.GroupID == groupID && invite.RevokedAt == nil {
			out = append(out, invite)
		}
	}
	return out, nil
}

func (s *MemoryStore) RevokeGroupInvite(groupID, inviteID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := NowUTC()
	for token, invite := range s.groupInvitesByToken {
		if invite.ID == inviteID && invite.GroupID == groupID {
			if invite.RevokedAt != nil {
				return ErrGone
			}
			invite.RevokedAt = &now
			s.groupInvitesByToken[token] = invite
			return nil
		}
	}
	return ErrNotFound
}

func (s *MemoryStore) PeekChat(chatID string) (model.Chat, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	chat, ok := s.chats[chatID]
	if !ok || chat == nil {
		return model.Chat{}, ErrNotFound
	}
	return *chat, nil
}

func (s *MemoryStore) GetGroupInviteByToken(token string) (GroupInvite, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	invite, ok := s.groupInvitesByToken[token]
	if !ok {
		return GroupInvite{}, ErrNotFound
	}
	return invite, nil
}

func (s *MemoryStore) ConsumeGroupInvite(inviteID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for token, invite := range s.groupInvitesByToken {
		if invite.ID != inviteID {
			continue
		}
		if invite.RevokedAt != nil || !invite.ExpiresAt.After(NowUTC()) {
			return ErrGone
		}
		if invite.MaxUses != nil && invite.UseCount >= *invite.MaxUses {
			return ErrGone
		}
		invite.UseCount++
		s.groupInvitesByToken[token] = invite
		return nil
	}
	return ErrNotFound
}

func (s *MemoryStore) CreateGroupJoinRequest(groupID, userID string) (GroupJoinRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupJoinRequests == nil {
		s.groupJoinRequests = make(map[string]GroupJoinRequest)
	}
	req := GroupJoinRequest{ID: uuid.NewString(), GroupID: groupID, UserID: userID, Status: JoinRequestPending, CreatedAt: NowUTC()}
	s.groupJoinRequests[req.ID] = req
	return req, nil
}

func (s *MemoryStore) ListGroupJoinRequests(groupID string) ([]GroupJoinRequest, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []GroupJoinRequest
	for _, req := range s.groupJoinRequests {
		if req.GroupID == groupID && req.Status == JoinRequestPending {
			out = append(out, req)
		}
	}
	return out, nil
}

func (s *MemoryStore) ResolveGroupJoinRequest(requestID, resolverID, status string) (GroupJoinRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	req, ok := s.groupJoinRequests[requestID]
	if !ok || req.Status != JoinRequestPending {
		return GroupJoinRequest{}, ErrNotFound
	}
	now := NowUTC()
	req.Status = status
	req.ResolvedAt = &now
	req.ResolvedBy = resolverID
	s.groupJoinRequests[requestID] = req
	return req, nil
}

func (s *MemoryStore) PinGroupItem(groupID, itemRef, pinnedBy string) (GroupPinnedItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupPinned == nil {
		s.groupPinned = make(map[string][]GroupPinnedItem)
	}
	item := GroupPinnedItem{ID: uuid.NewString(), GroupID: groupID, ItemRef: itemRef, PinnedBy: pinnedBy, PinnedAt: NowUTC()}
	s.groupPinned[groupID] = append(s.groupPinned[groupID], item)
	return item, nil
}

func (s *MemoryStore) UnpinGroupItem(groupID, itemID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, ok := s.groupPinned[groupID]
	if !ok {
		return ErrNotFound
	}
	for i, item := range items {
		if item.ID == itemID {
			s.groupPinned[groupID] = append(items[:i], items[i+1:]...)
			return nil
		}
	}
	return ErrNotFound
}

func (s *MemoryStore) ListGroupPinnedItems(groupID string) ([]GroupPinnedItem, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]GroupPinnedItem(nil), s.groupPinned[groupID]...), nil
}

func (s *MemoryStore) AppendGroupAudit(event GroupAuditEvent) (GroupAuditEvent, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupAudit == nil {
		s.groupAudit = make(map[string][]GroupAuditEvent)
	}
	if event.ID == "" {
		event.ID = uuid.NewString()
	}
	if event.CreatedAt.IsZero() {
		event.CreatedAt = NowUTC()
	}
	s.groupAudit[event.GroupID] = append(s.groupAudit[event.GroupID], event)
	return event, nil
}

func (s *MemoryStore) ListGroupAuditEvents(groupID string, limit int) ([]GroupAuditEvent, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	events := s.groupAudit[groupID]
	if limit > 0 && len(events) > limit {
		events = events[len(events)-limit:]
	}
	return append([]GroupAuditEvent(nil), events...), nil
}

func (s *MemoryStore) IsGroupChat(groupID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	chat, ok := s.chats[groupID]
	if !ok {
		return false, ErrNotFound
	}
	return chat.Type == model.ChatTypeGroup || chat.Type == model.ChatTypeChannel, nil
}

func (s *MemoryStore) EnsureManagedGroup(chatID, preferredOwnerID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	chat, ok := s.chats[chatID]
	if !ok || chat == nil {
		return ErrNotFound
	}
	if chat.Type != model.ChatTypeGroup && chat.Type != model.ChatTypeChannel {
		return nil
	}
	if s.groupSettings == nil {
		s.groupSettings = make(map[string]GroupSettings)
	}
	if s.groupMembers == nil {
		s.groupMembers = make(map[string]map[string]GroupMember)
	}
	if _, exists := s.groupSettings[chatID]; !exists {
		enc := model.EncryptionE2E
		if chat.Type == model.ChatTypeChannel {
			enc = model.EncryptionNone
		}
		s.groupSettings[chatID] = GroupSettings{
			GroupID:             chatID,
			MembershipVersion:   1,
			JoinByInviteOnly:    true,
			PermInvite:          GroupPermAdmin,
			PermSendMessages:    GroupPermAll,
			PermPin:             GroupPermAdmin,
			PermModerate:        GroupPermAdmin,
			PermChangeInfo:      GroupPermAdmin,
			PermComment:         GroupPermAll,
			PermReact:           GroupPermAll,
			Visibility:          model.VisibilityPrivate,
			EncryptionMode:      enc,
			UpdatedAt:           NowUTC(),
		}
	}
	members := s.groupMembers[chatID]
	if members == nil {
		members = make(map[string]GroupMember)
		s.groupMembers[chatID] = members
		for _, id := range chat.MemberIDs {
			members[id] = GroupMember{UserID: id, Role: GroupRoleMember, JoinedAt: chat.CreatedAt}
		}
	}
	for _, member := range members {
		if member.Role == GroupRoleOwner {
			return nil
		}
	}
	ownerID := ""
	if preferredOwnerID != "" {
		if _, ok := members[preferredOwnerID]; ok {
			ownerID = preferredOwnerID
		}
	}
	if ownerID == "" {
		for _, member := range members {
			ownerID = member.UserID
			break
		}
	}
	if ownerID == "" {
		return nil
	}
	member := members[ownerID]
	member.Role = GroupRoleOwner
	members[ownerID] = member
	return nil
}

func (s *MemoryStore) FindChannelBySlug(slug string) (model.Chat, GroupSettings, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	want := normalizeChannelSlug(slug)
	if want == "" {
		return model.Chat{}, GroupSettings{}, ErrNotFound
	}
	for id, gs := range s.groupSettings {
		if normalizeChannelSlug(gs.Slug) != want {
			continue
		}
		chat, ok := s.chats[id]
		if !ok || chat.Type != model.ChatTypeChannel {
			continue
		}
		out := *chat
		out.Description = gs.Description
		out.Visibility = gs.Visibility
		out.Slug = gs.Slug
		out.Encryption = gs.EncryptionMode
		return out, gs, nil
	}
	return model.Chat{}, GroupSettings{}, ErrNotFound
}

func (s *MemoryStore) IsChannelSlugAvailable(slug string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	want := normalizeChannelSlug(slug)
	if want == "" {
		return false, nil
	}
	for _, gs := range s.groupSettings {
		if normalizeChannelSlug(gs.Slug) == want {
			return false, nil
		}
	}
	return true, nil
}

// SearchPublicChannels — any authenticated user can discover public channels by title/slug/description.
func (s *MemoryStore) SearchPublicChannels(query string, limit int) ([]model.Chat, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return nil, nil
	}
	if limit <= 0 {
		limit = 20
	}
	out := make([]model.Chat, 0)
	for id, gs := range s.groupSettings {
		if gs.Visibility != model.VisibilityPublic {
			continue
		}
		chat, ok := s.chats[id]
		if !ok || chat.Type != model.ChatTypeChannel {
			continue
		}
		title := strings.ToLower(chat.Title)
		slug := strings.ToLower(gs.Slug)
		desc := strings.ToLower(gs.Description)
		if !strings.Contains(title, q) && !strings.Contains(slug, q) && !strings.Contains(desc, q) {
			continue
		}
		item := *chat
		item.Description = gs.Description
		item.Visibility = gs.Visibility
		item.Slug = gs.Slug
		item.Encryption = gs.EncryptionMode
		out = append(out, item)
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (s *MemoryStore) IncrementGroupsCreated24h(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.groupsCreated24h == nil {
		s.groupsCreated24h = make(map[string]int)
		s.groupsWindowStart = make(map[string]time.Time)
	}
	start := s.groupsWindowStart[userID]
	if start.IsZero() || start.Before(NowUTC().Add(-24*time.Hour)) {
		s.groupsCreated24h[userID] = 1
		s.groupsWindowStart[userID] = NowUTC()
		return nil
	}
	s.groupsCreated24h[userID]++
	return nil
}

func (s *MemoryStore) GroupsCreated24h(userID string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	start := s.groupsWindowStart[userID]
	if start.IsZero() || start.Before(NowUTC().Add(-24*time.Hour)) {
		return 0, nil
	}
	return s.groupsCreated24h[userID], nil
}

func (s *MemoryStore) removeGroupMemberLocked(groupID, userID string) error {
	members, ok := s.groupMembers[groupID]
	if !ok {
		return ErrNotFound
	}
	delete(members, userID)
	if chat, ok := s.chats[groupID]; ok {
		filtered := make([]string, 0, len(chat.MemberIDs))
		for _, id := range chat.MemberIDs {
			if id != userID {
				filtered = append(filtered, id)
			}
		}
		chat.MemberIDs = filtered
	}
	return nil
}
