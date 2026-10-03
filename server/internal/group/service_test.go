// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"errors"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestCreateGroupAndAddMemberRotatesKeyEpoch(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})

	group, err := svc.CreateGroup("admin-user", CreateGroupRequest{Title: "E2EE Team", MemberIDs: []string{"member-1"}})
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	if group.MembershipVersion != 1 {
		t.Fatalf("expected version 1, got %d", group.MembershipVersion)
	}

	change, err := svc.AddMember(group.GroupID, "admin-user", "member-2")
	if err != nil {
		t.Fatalf("add member: %v", err)
	}
	if !change.KeyRotationRequired {
		t.Fatal("expected key rotation flag")
	}
	if change.MembershipVersion != 2 {
		t.Fatalf("expected version 2, got %d", change.MembershipVersion)
	}
}

func TestOwnerCanCreateListAndRevokeInviteLink(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{Title: "Team"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	foundOwner := false
	for _, member := range group.Members {
		if member.UserID == "owner" && member.Role == store.GroupRoleOwner {
			foundOwner = true
		}
	}
	if !foundOwner {
		t.Fatalf("creator is not owner: %+v", group.Members)
	}
	invite, err := svc.CreateInvite(group.GroupID, "owner", CreateInviteRequest{Title: "friends", ExpiresInHours: 24})
	if err != nil {
		t.Fatalf("create invite: %v", err)
	}
	if invite.Link == "" || invite.Token == "" {
		t.Fatalf("missing link/token: %+v", invite)
	}
	listed, err := svc.ListInvites(group.GroupID, "owner")
	if err != nil || len(listed) != 1 {
		t.Fatalf("list=%v err=%v", listed, err)
	}
	preview, err := svc.PreviewInvite(invite.Token, "stranger")
	if err != nil {
		t.Fatalf("preview: %v", err)
	}
	if preview.Title != "Team" || preview.AlreadyMember || preview.Expired {
		t.Fatalf("preview=%+v", preview)
	}
	if err := svc.RevokeInvite(group.GroupID, "owner", invite.InviteID); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if _, err := svc.JoinByInvite(invite.Token, "stranger"); err == nil {
		t.Fatal("revoked invite should not join")
	}
}

func TestInviteRequiresApprovalCreatesJoinRequest(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{Title: "Closed"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	invite, err := svc.CreateInvite(group.GroupID, "owner", CreateInviteRequest{RequiresApproval: true, ExpiresInHours: 2})
	if err != nil {
		t.Fatalf("invite: %v", err)
	}
	change, err := svc.JoinByInvite(invite.Token, "newbie")
	if err != nil {
		t.Fatalf("join: %v", err)
	}
	if !change.PendingApproval {
		t.Fatal("expected pending approval")
	}
	if _, err := mem.GetGroupMemberRole(group.GroupID, "newbie"); err == nil {
		t.Fatal("should not be a member yet")
	}
}

func TestMemberCannotInviteWhenPermAdmin(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{Title: "G", MemberIDs: []string{"member"}})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := svc.CreateInvite(group.GroupID, "member", CreateInviteRequest{}); err == nil {
		t.Fatal("member should not create invite")
	}
}

func TestInviteJoinIncrementsMembershipVersion(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{Title: "Invites"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	invite, err := svc.CreateInvite(group.GroupID, "owner", CreateInviteRequest{ExpiresInHours: 1})
	if err != nil {
		t.Fatalf("invite: %v", err)
	}
	change, err := svc.JoinByInvite(invite.Token, "new-user")
	if err != nil {
		t.Fatalf("join: %v", err)
	}
	if change.MembershipVersion < 2 {
		t.Fatalf("expected bumped version, got %d", change.MembershipVersion)
	}
}

func TestCreatePublicChannelAndJoinBySlug(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})

	ch, err := svc.CreateChannel("owner", CreateChannelRequest{
		Title:       "Glagolitsa News",
		Description: "Product updates",
		Visibility:  "public",
		Slug:        "glag_news",
	})
	if err != nil {
		t.Fatalf("create channel: %v", err)
	}
	if ch.Type != "channel" {
		t.Fatalf("type=%q", ch.Type)
	}
	if ch.Visibility != "public" || ch.Slug != "glag_news" {
		t.Fatalf("visibility/slug = %s/%s", ch.Visibility, ch.Slug)
	}
	if ch.Encryption != "none" {
		t.Fatalf("public channel must be non-e2e, got %q", ch.Encryption)
	}
	if ch.Settings.PermSendMessages != store.GroupPermAdmin {
		t.Fatalf("channel write ACL should be admin-only")
	}

	ok, err := svc.CheckChannelSlug("glag_news")
	if err != nil {
		t.Fatalf("check slug: %v", err)
	}
	if ok {
		t.Fatal("slug should be taken")
	}

	joined, err := svc.JoinChannelBySlug("glag_news", "subscriber-1")
	if err != nil {
		t.Fatalf("join: %v", err)
	}
	if joined.ID != ch.ID {
		t.Fatalf("joined wrong channel")
	}
	found := false
	for _, m := range joined.Members {
		if m.UserID == "subscriber-1" {
			found = true
		}
	}
	if !found {
		t.Fatal("subscriber not in members")
	}
}

func TestJoinPrivateChannelBySlugLink(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	ch, err := svc.CreateChannel("owner", CreateChannelRequest{
		Title:      "Closed",
		Visibility: "private",
		Slug:       "closed_club",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if ch.Slug != "closed_club" {
		t.Fatalf("slug=%q", ch.Slug)
	}
	joined, err := svc.JoinChannelBySlug("closed_club", "guest")
	if err != nil {
		t.Fatalf("join by private slug: %v", err)
	}
	if joined.ID != ch.ID {
		t.Fatalf("wrong channel")
	}
}

func TestModerationCannotTargetOwnerSelfOrPeerAdmin(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{
		Title:     "Team",
		MemberIDs: []string{"admin-1", "admin-2", "member"},
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "admin-1", store.GroupRoleAdmin); err != nil {
		t.Fatalf("promote admin-1: %v", err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "admin-2", store.GroupRoleAdmin); err != nil {
		t.Fatalf("promote admin-2: %v", err)
	}

	if _, err := svc.BanMember(group.GroupID, "admin-1", "owner", ""); !errors.Is(err, ErrForbidden) {
		t.Fatalf("admin ban owner err=%v want forbidden", err)
	}
	if err := svc.MuteMember(group.GroupID, "admin-1", "admin-2", 60); !errors.Is(err, ErrForbidden) {
		t.Fatalf("admin mute peer admin err=%v want forbidden", err)
	}
	if err := svc.MuteMember(group.GroupID, "admin-1", "admin-1", 60); err == nil {
		t.Fatal("admin must not mute self")
	}
	if err := svc.MuteMember(group.GroupID, "owner", "admin-1", 60); err != nil {
		t.Fatalf("owner mute admin: %v", err)
	}
}

func TestGetGroup_bootstrapsLegacyChatAsOwnedGroup(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	created, err := mem.CreateChat(model.Chat{
		ID:        "legacy-group",
		Title:     "GroupTest",
		Type:      model.ChatTypeGroup,
		MemberIDs: []string{"creator", "friend"},
	})
	if err != nil {
		t.Fatalf("create chat: %v", err)
	}
	got, err := svc.GetGroup(created.ID, "creator")
	if err != nil {
		t.Fatalf("get group: %v", err)
	}
	var owner string
	for _, member := range got.Members {
		if member.Role == store.GroupRoleOwner {
			owner = member.UserID
		}
	}
	if owner != "creator" {
		t.Fatalf("viewer should become owner of a legacy group, got %q members=%+v", owner, got.Members)
	}
	if got.Settings.Visibility != "private" {
		t.Fatalf("visibility=%q", got.Settings.Visibility)
	}
}

func TestUpdateMemberRole_promoteDemoteAndTransferOwner(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{
		Title:     "Team",
		MemberIDs: []string{"member"},
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "member", store.GroupRoleAdmin); err != nil {
		t.Fatalf("promote: %v", err)
	}
	role, err := mem.GetGroupMemberRole(group.GroupID, "member")
	if err != nil || role != store.GroupRoleAdmin {
		t.Fatalf("after promote role=%q err=%v", role, err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "member", store.GroupRoleMember); err != nil {
		t.Fatalf("demote: %v", err)
	}
	role, err = mem.GetGroupMemberRole(group.GroupID, "member")
	if err != nil || role != store.GroupRoleMember {
		t.Fatalf("after demote role=%q err=%v", role, err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "member", store.GroupRoleOwner); err != nil {
		t.Fatalf("transfer: %v", err)
	}
	newOwner, err := mem.GetGroupMemberRole(group.GroupID, "member")
	if err != nil || newOwner != store.GroupRoleOwner {
		t.Fatalf("new owner role=%q err=%v", newOwner, err)
	}
	oldOwner, err := mem.GetGroupMemberRole(group.GroupID, "owner")
	if err != nil || oldOwner != store.GroupRoleAdmin {
		t.Fatalf("old owner role=%q err=%v", oldOwner, err)
	}
	if err := svc.UpdateMemberRole(group.GroupID, "owner", "member", store.GroupRoleAdmin); !errors.Is(err, ErrForbidden) {
		t.Fatalf("former owner must not transfer or change new owner, err=%v", err)
	}
}

func TestCreatePrivateChannelRequiresNoSlug(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	ch, err := svc.CreateChannel("owner", CreateChannelRequest{
		Title:      "Private Announcements",
		Visibility: "private",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if ch.Slug != "" {
		t.Fatalf("private channel must not require slug, got %q", ch.Slug)
	}
	if ch.Visibility != "private" {
		t.Fatalf("visibility=%q", ch.Visibility)
	}
}

func TestSearchPublicChannels_anyUserCanFindBySlugOrTitle(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 50, DefaultInviteHours: 24})

	_, err := svc.CreateChannel("owner-a", CreateChannelRequest{
		Title: "City News", Visibility: "public", Slug: "city_news", Description: "local updates",
	})
	if err != nil {
		t.Fatalf("create public: %v", err)
	}
	_, err = svc.CreateChannel("owner-b", CreateChannelRequest{
		Title: "Secret Club", Visibility: "private",
	})
	if err != nil {
		t.Fatalf("create private: %v", err)
	}

	// Non-member "stranger" discovers public by slug fragment.
	bySlug, err := svc.SearchPublicChannels("city_n")
	if err != nil {
		t.Fatalf("search slug: %v", err)
	}
	if len(bySlug) != 1 || bySlug[0].Slug != "city_news" {
		t.Fatalf("expected city_news, got %+v", bySlug)
	}

	// By title substring.
	byTitle, err := svc.SearchPublicChannels("news")
	if err != nil {
		t.Fatalf("search title: %v", err)
	}
	if len(byTitle) != 1 || byTitle[0].Title != "City News" {
		t.Fatalf("expected City News, got %+v", byTitle)
	}

	// Private must never appear in public discover for other users.
	secretHits, err := svc.SearchPublicChannels("secret")
	if err != nil {
		t.Fatalf("search secret: %v", err)
	}
	if len(secretHits) != 0 {
		t.Fatalf("private channel leaked into public search: %+v", secretHits)
	}

	// Second unrelated user can still find the same public channel (multi-user guarantee).
	again, err := svc.SearchPublicChannels("city_news")
	if err != nil || len(again) != 1 {
		t.Fatalf("second user search failed: %v %+v", err, again)
	}
}

func TestCreateChannelStoresAvatar(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	icon := "data:image/png;base64,YWJj"
	channel, err := svc.CreateChannel("owner", CreateChannelRequest{
		Title:     "News",
		AvatarURL: icon,
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if channel.AvatarURL != icon {
		t.Fatalf("avatar = %q", channel.AvatarURL)
	}
	chat, err := mem.GetChat(channel.ID, "owner")
	if err != nil {
		t.Fatalf("get chat: %v", err)
	}
	if chat.AvatarURL != icon {
		t.Fatalf("stored avatar = %q", chat.AvatarURL)
	}
}

func TestCreateChannelRejectsInvalidAvatar(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	_, err := svc.CreateChannel("owner", CreateChannelRequest{
		Title:     "News",
		AvatarURL: "https://example.com/icon.png",
	})
	if err == nil {
		t.Fatal("expected invalid avatar error")
	}
}

func TestDeleteGroup_ownerWipesMessagesMembersAndChat(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	group, err := svc.CreateGroup("owner", CreateGroupRequest{
		Title:     "Семья",
		MemberIDs: []string{"alice"},
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := mem.AddMessage(model.Message{
		ID: "m1", ChatID: group.GroupID, SenderID: "owner", Body: "hi",
	}); err != nil {
		t.Fatalf("message: %v", err)
	}

	if err := svc.DeleteGroup(group.GroupID, "alice"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("member must not wipe, err=%v", err)
	}
	if err := svc.DeleteGroup(group.GroupID, "owner"); err != nil {
		t.Fatalf("owner delete: %v", err)
	}
	if _, err := mem.GetChat(group.GroupID, "owner"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("chat should be gone, err=%v", err)
	}
	if _, err := mem.GetGroupMemberRole(group.GroupID, "alice"); err == nil {
		t.Fatal("membership should be gone")
	}
	msgs, err := mem.ListMessages(group.GroupID, "owner")
	if err == nil && len(msgs) > 0 {
		t.Fatalf("messages leftover: %d", len(msgs))
	}
}

func TestDeleteGroup_rejectsDM(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{MaxGroupsCreated24h: 10, DefaultInviteHours: 24})
	dm, err := mem.CreateChat(model.Chat{
		ID: "dm-1", Title: "Аня", Type: model.ChatTypeDM, MemberIDs: []string{"owner", "anya"},
	})
	if err != nil {
		t.Fatalf("dm: %v", err)
	}
	if err := svc.DeleteGroup(dm.ID, "owner"); !errors.Is(err, ErrForbidden) && !errors.Is(err, ErrNotFound) {
		t.Fatalf("DM wipe must fail, err=%v", err)
	}
}
