// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) CreateGroup(input CreateGroupInput) (model.Chat, GroupSettings, error) {
	if input.Title == "" {
		return model.Chat{}, GroupSettings{}, errors.New("title is required")
	}
	if input.GroupID == "" {
		input.GroupID = uuid.NewString()
	}
	chatType := input.ChatType
	if chatType == "" {
		chatType = model.ChatTypeGroup
	}
	if chatType != model.ChatTypeGroup && chatType != model.ChatTypeChannel {
		return model.Chat{}, GroupSettings{}, errors.New("invalid chat type")
	}
	settings := defaultGroupSettings(input, NowUTC())
	createdAt := settings.UpdatedAt
	memberSet := map[string]struct{}{input.CreatorID: {}}
	for _, id := range input.MemberIDs {
		if id != "" {
			memberSet[id] = struct{}{}
		}
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return model.Chat{}, GroupSettings{}, err
	}
	defer tx.Rollback(context.Background())

	_, err = tx.Exec(context.Background(), `
		INSERT INTO chats (id, title, chat_type, created_at, updated_at, avatar_url)
		VALUES ($1, $2, $3, $4, $4, $5)
	`, input.GroupID, input.Title, chatType, createdAt, input.AvatarURL)
	if err != nil {
		return model.Chat{}, GroupSettings{}, err
	}

	memberIDs := make([]string, 0, len(memberSet))
	for memberID := range memberSet {
		role := GroupRoleMember
		if memberID == input.CreatorID {
			role = GroupRoleOwner
		}
		_, err = tx.Exec(context.Background(), `
			INSERT INTO chat_members (chat_id, user_id, joined_at, role)
			VALUES ($1, $2, $3, $4)
		`, input.GroupID, memberID, createdAt, role)
		if err != nil {
			return model.Chat{}, GroupSettings{}, err
		}
		memberIDs = append(memberIDs, memberID)
	}

	var slug any
	if settings.Slug != "" {
		slug = settings.Slug
	}
	_, err = tx.Exec(context.Background(), `
		INSERT INTO group_settings (
			group_id, membership_version, join_by_invite_only, join_requests_enabled,
			perm_invite, perm_send_messages, perm_pin, perm_moderate,
			visibility, slug, description, encryption_mode, updated_at
		) VALUES ($1, 1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
	`, input.GroupID, settings.JoinByInviteOnly, settings.JoinRequestsEnabled,
		settings.PermInvite, settings.PermSendMessages, settings.PermPin, settings.PermModerate,
		settings.Visibility, slug, settings.Description, settings.EncryptionMode, createdAt)
	if err != nil {
		return model.Chat{}, GroupSettings{}, err
	}

	if err := tx.Commit(context.Background()); err != nil {
		return model.Chat{}, GroupSettings{}, err
	}

	settings.GroupID = input.GroupID
	settings.MembershipVersion = 1
	chat := model.Chat{
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
	return chat, settings, nil
}

func defaultGroupSettings(input CreateGroupInput, now time.Time) GroupSettings {
	visibility := input.Visibility
	if visibility == "" {
		visibility = model.VisibilityPrivate
	}
	enc := input.EncryptionMode
	if enc == "" {
		if input.ChatType == model.ChatTypeChannel {
			enc = model.EncryptionNone
		} else {
			enc = model.EncryptionE2E
		}
	}
	joinInviteOnly := true
	if input.JoinByInviteOnly != nil {
		joinInviteOnly = *input.JoinByInviteOnly
	} else if visibility == model.VisibilityPublic {
		joinInviteOnly = false
	}
	joinRequests := false
	if input.JoinRequestsEnabled != nil {
		joinRequests = *input.JoinRequestsEnabled
	}
	permInvite := input.PermInvite
	if permInvite == "" {
		permInvite = GroupPermAdmin
	}
	permSend := input.PermSendMessages
	if permSend == "" {
		if input.ChatType == model.ChatTypeChannel {
			permSend = GroupPermAdmin
		} else {
			permSend = GroupPermAll
		}
	}
	permPin := input.PermPin
	if permPin == "" {
		permPin = GroupPermAdmin
	}
	permMod := input.PermModerate
	if permMod == "" {
		permMod = GroupPermAdmin
	}
	return GroupSettings{
		JoinByInviteOnly:    joinInviteOnly,
		JoinRequestsEnabled: joinRequests,
		PermInvite:          permInvite,
		PermSendMessages:    permSend,
		PermPin:             permPin,
		PermModerate:        permMod,
		PermChangeInfo:      GroupPermAdmin,
		PermComment:         GroupPermAll,
		PermReact:           GroupPermAll,
		Visibility:          visibility,
		Slug:                input.Slug,
		Description:         input.Description,
		EncryptionMode:      enc,
		UpdatedAt:           now,
	}
}

func (s *PostgresStore) GetGroupSettings(groupID string) (GroupSettings, error) {
	var settings GroupSettings
	var slug *string
	err := s.pool.QueryRow(context.Background(), `
		SELECT group_id, membership_version, join_by_invite_only, join_requests_enabled,
		       perm_invite, perm_send_messages, perm_pin, perm_moderate,
		       COALESCE(perm_change_info, 'admin'),
		       COALESCE(perm_comment, 'all'), COALESCE(perm_react, 'all'),
		       visibility, slug, COALESCE(description, ''), encryption_mode, updated_at
		FROM group_settings
		WHERE group_id = $1
	`, groupID).Scan(
		&settings.GroupID,
		&settings.MembershipVersion,
		&settings.JoinByInviteOnly,
		&settings.JoinRequestsEnabled,
		&settings.PermInvite,
		&settings.PermSendMessages,
		&settings.PermPin,
		&settings.PermModerate,
		&settings.PermChangeInfo,
		&settings.PermComment,
		&settings.PermReact,
		&settings.Visibility,
		&slug,
		&settings.Description,
		&settings.EncryptionMode,
		&settings.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return GroupSettings{}, ErrNotFound
	}
	if err != nil {
		// Fallback: pre-029 schema without perm_comment/perm_react (or pre-026).
		err = s.pool.QueryRow(context.Background(), `
			SELECT group_id, membership_version, join_by_invite_only, join_requests_enabled,
			       perm_invite, perm_send_messages, perm_pin, perm_moderate,
			       visibility, slug, COALESCE(description, ''), encryption_mode, updated_at
			FROM group_settings
			WHERE group_id = $1
		`, groupID).Scan(
			&settings.GroupID,
			&settings.MembershipVersion,
			&settings.JoinByInviteOnly,
			&settings.JoinRequestsEnabled,
			&settings.PermInvite,
			&settings.PermSendMessages,
			&settings.PermPin,
			&settings.PermModerate,
			&settings.Visibility,
			&slug,
			&settings.Description,
			&settings.EncryptionMode,
			&settings.UpdatedAt,
		)
		if err != nil {
			err = s.pool.QueryRow(context.Background(), `
				SELECT group_id, membership_version, join_by_invite_only, join_requests_enabled,
				       perm_invite, perm_send_messages, perm_pin, perm_moderate, updated_at
				FROM group_settings
				WHERE group_id = $1
			`, groupID).Scan(
				&settings.GroupID,
				&settings.MembershipVersion,
				&settings.JoinByInviteOnly,
				&settings.JoinRequestsEnabled,
				&settings.PermInvite,
				&settings.PermSendMessages,
				&settings.PermPin,
				&settings.PermModerate,
				&settings.UpdatedAt,
			)
			if errors.Is(err, pgx.ErrNoRows) {
				return GroupSettings{}, ErrNotFound
			}
			if err != nil {
				return GroupSettings{}, err
			}
			settings.Visibility = model.VisibilityPrivate
			settings.EncryptionMode = model.EncryptionE2E
		}
		settings.PermComment = GroupPermAll
		settings.PermReact = GroupPermAll
		if slug != nil {
			settings.Slug = *slug
		}
		if settings.Visibility == "" {
			settings.Visibility = model.VisibilityPrivate
		}
		if settings.EncryptionMode == "" {
			settings.EncryptionMode = model.EncryptionE2E
		}
		return settings, nil
	}
	if slug != nil {
		settings.Slug = *slug
	}
	if settings.Visibility == "" {
		settings.Visibility = model.VisibilityPrivate
	}
	if settings.EncryptionMode == "" {
		settings.EncryptionMode = model.EncryptionE2E
	}
	if settings.PermComment == "" {
		settings.PermComment = GroupPermAll
	}
	if settings.PermReact == "" {
		settings.PermReact = GroupPermAll
	}
	return settings, nil
}

func (s *PostgresStore) UpdateGroupSettings(groupID string, input UpdateGroupSettingsInput) (GroupSettings, error) {
	current, err := s.GetGroupSettings(groupID)
	if err != nil {
		return GroupSettings{}, err
	}
	if input.JoinByInviteOnly != nil {
		current.JoinByInviteOnly = *input.JoinByInviteOnly
	}
	if input.JoinRequestsEnabled != nil {
		current.JoinRequestsEnabled = *input.JoinRequestsEnabled
	}
	if input.PermInvite != nil {
		current.PermInvite = *input.PermInvite
	}
	if input.PermSendMessages != nil {
		current.PermSendMessages = *input.PermSendMessages
	}
	if input.PermPin != nil {
		current.PermPin = *input.PermPin
	}
	if input.PermModerate != nil {
		current.PermModerate = *input.PermModerate
	}
	if input.PermChangeInfo != nil {
		current.PermChangeInfo = *input.PermChangeInfo
	}
	if input.PermComment != nil {
		current.PermComment = *input.PermComment
	}
	if input.PermReact != nil {
		current.PermReact = *input.PermReact
	}
	if input.Visibility != nil {
		current.Visibility = *input.Visibility
	}
	if input.Slug != nil {
		current.Slug = *input.Slug
	}
	if input.Description != nil {
		current.Description = *input.Description
	}
	if input.EncryptionMode != nil {
		current.EncryptionMode = *input.EncryptionMode
	}
	current.UpdatedAt = NowUTC()
	var slug any
	if current.Slug != "" {
		slug = current.Slug
	}
	_, err = s.pool.Exec(context.Background(), `
		UPDATE group_settings
		SET join_by_invite_only = $2,
		    join_requests_enabled = $3,
		    perm_invite = $4,
		    perm_send_messages = $5,
		    perm_pin = $6,
		    perm_moderate = $7,
		    perm_change_info = $8,
		    perm_comment = $9,
		    perm_react = $10,
		    visibility = $11,
		    slug = $12,
		    description = $13,
		    encryption_mode = $14,
		    updated_at = $15
		WHERE group_id = $1
	`, groupID, current.JoinByInviteOnly, current.JoinRequestsEnabled,
		current.PermInvite, current.PermSendMessages, current.PermPin, current.PermModerate,
		changeInfoOrAdmin(current.PermChangeInfo),
		nullIfEmptyPerm(current.PermComment), nullIfEmptyPerm(current.PermReact),
		current.Visibility, slug, current.Description, current.EncryptionMode, current.UpdatedAt)
	if err != nil {
		// Pre-029 fallback without comment/react columns.
		_, err = s.pool.Exec(context.Background(), `
			UPDATE group_settings
			SET join_by_invite_only = $2,
			    join_requests_enabled = $3,
			    perm_invite = $4,
			    perm_send_messages = $5,
			    perm_pin = $6,
			    perm_moderate = $7,
			    visibility = $8,
			    slug = $9,
			    description = $10,
			    encryption_mode = $11,
			    updated_at = $12
			WHERE group_id = $1
		`, groupID, current.JoinByInviteOnly, current.JoinRequestsEnabled,
			current.PermInvite, current.PermSendMessages, current.PermPin, current.PermModerate,
			current.Visibility, slug, current.Description, current.EncryptionMode, current.UpdatedAt)
	}
	return current, err
}

func nullIfEmptyPerm(value string) string {
	if value == "" {
		return GroupPermAll
	}
	return value
}

func changeInfoOrAdmin(value string) string {
	if value == "" {
		return GroupPermAdmin
	}
	return value
}

func (s *PostgresStore) ListGroupMembers(groupID string) ([]GroupMember, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT user_id, role, joined_at, muted_until, admin_rights
		FROM chat_members
		WHERE chat_id = $1
		ORDER BY joined_at ASC
	`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var members []GroupMember
	for rows.Next() {
		var member GroupMember
		var rawRights []byte
		if err := rows.Scan(&member.UserID, &member.Role, &member.JoinedAt, &member.MutedUntil, &rawRights); err != nil {
			return nil, err
		}
		if rights, err := decodeAdminRights(rawRights); err != nil {
			return nil, err
		} else {
			member.AdminRights = rights
		}
		members = append(members, member)
	}
	return members, rows.Err()
}

func (s *PostgresStore) GetGroupMember(groupID, userID string) (GroupMember, error) {
	var member GroupMember
	var rawRights []byte
	err := s.pool.QueryRow(context.Background(), `
		SELECT user_id, role, joined_at, muted_until, admin_rights
		FROM chat_members
		WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID).Scan(
		&member.UserID, &member.Role, &member.JoinedAt, &member.MutedUntil, &rawRights,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return GroupMember{}, ErrNotFound
	}
	if err != nil {
		return GroupMember{}, err
	}
	rights, err := decodeAdminRights(rawRights)
	if err != nil {
		return GroupMember{}, err
	}
	member.AdminRights = rights
	return member, nil
}

func decodeAdminRights(raw []byte) (*AdminRights, error) {
	if len(raw) == 0 {
		return nil, nil
	}
	var rights AdminRights
	if err := json.Unmarshal(raw, &rights); err != nil {
		return nil, err
	}
	return &rights, nil
}

func (s *PostgresStore) GetGroupMemberRole(groupID, userID string) (string, error) {
	var role string
	err := s.pool.QueryRow(context.Background(), `
		SELECT role FROM chat_members WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID).Scan(&role)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return role, err
}

func (s *PostgresStore) AddGroupMember(groupID, userID, role string) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO chat_members (chat_id, user_id, joined_at, role)
		VALUES ($1, $2, $3, $4)
	`, groupID, userID, NowUTC(), role)
	return err
}

func (s *PostgresStore) RemoveGroupMember(groupID, userID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM chat_members WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) SetGroupMemberRole(groupID, userID, role string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE chat_members SET role = $3 WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID, role)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) TransferGroupOwnership(groupID, fromUserID, toUserID string) error {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	tag, err := tx.Exec(context.Background(), `
		UPDATE chat_members SET role = $3 WHERE chat_id = $1 AND user_id = $2
	`, groupID, toUserID, GroupRoleOwner)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	tag, err = tx.Exec(context.Background(), `
		UPDATE chat_members SET role = $3 WHERE chat_id = $1 AND user_id = $2
	`, groupID, fromUserID, GroupRoleAdmin)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(context.Background())
}

func (s *PostgresStore) BumpMembershipVersion(groupID string) (int, error) {
	var version int
	err := s.pool.QueryRow(context.Background(), `
		UPDATE group_settings
		SET membership_version = membership_version + 1,
		    updated_at = NOW()
		WHERE group_id = $1
		RETURNING membership_version
	`, groupID).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrNotFound
	}
	return version, err
}

func (s *PostgresStore) IsGroupBanned(groupID, userID string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM group_bans WHERE group_id = $1 AND user_id = $2
		)
	`, groupID, userID).Scan(&exists)
	return exists, err
}

func (s *PostgresStore) BanGroupMember(groupID, userID, bannedBy, reason string) error {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())

	_, _ = tx.Exec(context.Background(), `
		DELETE FROM chat_members WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID)
	_, err = tx.Exec(context.Background(), `
		INSERT INTO group_bans (group_id, user_id, banned_by, reason, banned_at)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (group_id, user_id) DO UPDATE
		SET banned_by = EXCLUDED.banned_by, reason = EXCLUDED.reason, banned_at = EXCLUDED.banned_at
	`, groupID, userID, bannedBy, nullableString(reason), NowUTC())
	if err != nil {
		return err
	}
	return tx.Commit(context.Background())
}

func (s *PostgresStore) UnbanGroupMember(groupID, userID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM group_bans WHERE group_id = $1 AND user_id = $2
	`, groupID, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) MuteGroupMember(groupID, userID string, until time.Time) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE chat_members SET muted_until = $3 WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID, until)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) UnmuteGroupMember(groupID, userID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE chat_members SET muted_until = NULL WHERE chat_id = $1 AND user_id = $2
	`, groupID, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) CreateGroupInvite(input CreateInviteInput) (GroupInvite, error) {
	token, err := randomInviteToken()
	if err != nil {
		return GroupInvite{}, err
	}
	invite := GroupInvite{
		ID:               uuid.NewString(),
		GroupID:          input.GroupID,
		Token:            token,
		CreatedBy:        input.CreatedBy,
		Title:            input.Title,
		ExpiresAt:        input.ExpiresAt,
		MaxUses:          input.MaxUses,
		RequiresApproval: input.RequiresApproval,
		CreatedAt:        NowUTC(),
	}
	_, err = s.pool.Exec(context.Background(), `
		INSERT INTO group_invites (id, group_id, token, created_by, title, expires_at, max_uses, requires_approval, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`, invite.ID, invite.GroupID, invite.Token, invite.CreatedBy, invite.Title, invite.ExpiresAt, input.MaxUses, invite.RequiresApproval, invite.CreatedAt)
	return invite, err
}

func (s *PostgresStore) GetGroupInviteByToken(token string) (GroupInvite, error) {
	var invite GroupInvite
	var maxUses *int
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, group_id, token, created_by, COALESCE(title, ''), expires_at, max_uses, use_count,
		       COALESCE(requires_approval, false), revoked_at, created_at
		FROM group_invites WHERE token = $1
	`, token).Scan(
		&invite.ID, &invite.GroupID, &invite.Token, &invite.CreatedBy, &invite.Title,
		&invite.ExpiresAt, &maxUses, &invite.UseCount, &invite.RequiresApproval,
		&invite.RevokedAt, &invite.CreatedAt,
	)
	invite.MaxUses = maxUses
	if errors.Is(err, pgx.ErrNoRows) {
		return GroupInvite{}, ErrNotFound
	}
	return invite, err
}

func (s *PostgresStore) ListGroupInvites(groupID string) ([]GroupInvite, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, group_id, token, created_by, COALESCE(title, ''), expires_at, max_uses, use_count,
		       COALESCE(requires_approval, false), revoked_at, created_at
		FROM group_invites
		WHERE group_id = $1 AND revoked_at IS NULL
		ORDER BY created_at DESC
	`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupInvite
	for rows.Next() {
		var invite GroupInvite
		var maxUses *int
		if err := rows.Scan(
			&invite.ID, &invite.GroupID, &invite.Token, &invite.CreatedBy, &invite.Title,
			&invite.ExpiresAt, &maxUses, &invite.UseCount, &invite.RequiresApproval,
			&invite.RevokedAt, &invite.CreatedAt,
		); err != nil {
			return nil, err
		}
		invite.MaxUses = maxUses
		out = append(out, invite)
	}
	return out, rows.Err()
}

func (s *PostgresStore) RevokeGroupInvite(groupID, inviteID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE group_invites SET revoked_at = NOW()
		WHERE id = $1 AND group_id = $2 AND revoked_at IS NULL
	`, inviteID, groupID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) PeekChat(chatID string) (model.Chat, error) {
	return s.getChatByID(chatID)
}

func (s *PostgresStore) ConsumeGroupInvite(inviteID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE group_invites
		SET use_count = use_count + 1
		WHERE id = $1 AND revoked_at IS NULL AND expires_at > NOW()
		  AND (max_uses IS NULL OR use_count < max_uses)
	`, inviteID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrGone
	}
	return nil
}

func (s *PostgresStore) CreateGroupJoinRequest(groupID, userID string) (GroupJoinRequest, error) {
	req := GroupJoinRequest{
		ID:        uuid.NewString(),
		GroupID:   groupID,
		UserID:    userID,
		Status:    JoinRequestPending,
		CreatedAt: NowUTC(),
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO group_join_requests (id, group_id, user_id, status, created_at)
		VALUES ($1, $2, $3, $4, $5)
	`, req.ID, req.GroupID, req.UserID, req.Status, req.CreatedAt)
	return req, err
}

func (s *PostgresStore) ListGroupJoinRequests(groupID string) ([]GroupJoinRequest, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, group_id, user_id, status, created_at, resolved_at, resolved_by
		FROM group_join_requests
		WHERE group_id = $1 AND status = 'pending'
		ORDER BY created_at ASC
	`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupJoinRequest
	for rows.Next() {
		var req GroupJoinRequest
		var resolvedBy *string
		if err := rows.Scan(&req.ID, &req.GroupID, &req.UserID, &req.Status, &req.CreatedAt, &req.ResolvedAt, &resolvedBy); err != nil {
			return nil, err
		}
		if resolvedBy != nil {
			req.ResolvedBy = *resolvedBy
		}
		out = append(out, req)
	}
	return out, rows.Err()
}

func (s *PostgresStore) ResolveGroupJoinRequest(requestID, resolverID, status string) (GroupJoinRequest, error) {
	var req GroupJoinRequest
	var resolvedBy *string
	err := s.pool.QueryRow(context.Background(), `
		UPDATE group_join_requests
		SET status = $2, resolved_at = NOW(), resolved_by = $3
		WHERE id = $1 AND status = 'pending'
		RETURNING id, group_id, user_id, status, created_at, resolved_at, resolved_by
	`, requestID, status, resolverID).Scan(
		&req.ID, &req.GroupID, &req.UserID, &req.Status, &req.CreatedAt, &req.ResolvedAt, &resolvedBy,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return GroupJoinRequest{}, ErrNotFound
	}
	if resolvedBy != nil {
		req.ResolvedBy = *resolvedBy
	}
	return req, err
}

func (s *PostgresStore) PinGroupItem(groupID, itemRef, pinnedBy string) (GroupPinnedItem, error) {
	item := GroupPinnedItem{
		ID:       uuid.NewString(),
		GroupID:  groupID,
		ItemRef:  itemRef,
		PinnedBy: pinnedBy,
		PinnedAt: NowUTC(),
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO group_pinned_items (id, group_id, item_ref, pinned_by, pinned_at)
		VALUES ($1, $2, $3, $4, $5)
	`, item.ID, item.GroupID, item.ItemRef, item.PinnedBy, item.PinnedAt)
	return item, err
}

func (s *PostgresStore) UnpinGroupItem(groupID, itemID string) error {
	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM group_pinned_items WHERE group_id = $1 AND id = $2
	`, groupID, itemID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *PostgresStore) ListGroupPinnedItems(groupID string) ([]GroupPinnedItem, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, group_id, item_ref, pinned_by, pinned_at
		FROM group_pinned_items
		WHERE group_id = $1
		ORDER BY pinned_at DESC
	`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupPinnedItem
	for rows.Next() {
		var item GroupPinnedItem
		if err := rows.Scan(&item.ID, &item.GroupID, &item.ItemRef, &item.PinnedBy, &item.PinnedAt); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *PostgresStore) AppendGroupAudit(event GroupAuditEvent) (GroupAuditEvent, error) {
	if event.ID == "" {
		event.ID = uuid.NewString()
	}
	if event.CreatedAt.IsZero() {
		event.CreatedAt = NowUTC()
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO group_audit_events (id, group_id, actor_id, action, target_user_id, metadata, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`, event.ID, event.GroupID, nullableUUID(event.ActorID), event.Action,
		nullableUUID(event.TargetUserID), metadataJSON(event.Metadata), event.CreatedAt)
	return event, err
}

func (s *PostgresStore) ListGroupAuditEvents(groupID string, limit int) ([]GroupAuditEvent, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT id, group_id, actor_id, action, target_user_id, metadata, created_at
		FROM group_audit_events
		WHERE group_id = $1
		ORDER BY created_at DESC
		LIMIT $2
	`, groupID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanGroupAuditRows(rows)
}

func (s *PostgresStore) IsGroupChat(groupID string) (bool, error) {
	var chatType string
	err := s.pool.QueryRow(context.Background(), `
		SELECT chat_type FROM chats WHERE id = $1
	`, groupID).Scan(&chatType)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, ErrNotFound
	}
	// Managed multi-party conversations: private groups + channels share membership/ACL shell.
	return chatType == model.ChatTypeGroup || chatType == model.ChatTypeChannel, err
}

func (s *PostgresStore) EnsureManagedGroup(chatID, preferredOwnerID string) error {
	ok, err := s.IsGroupChat(chatID)
	if err != nil {
		return err
	}
	if !ok {
		return nil
	}
	if _, err := s.GetGroupSettings(chatID); errors.Is(err, ErrNotFound) {
		now := NowUTC()
		enc := model.EncryptionE2E
		var chatType string
		_ = s.pool.QueryRow(context.Background(), `SELECT chat_type FROM chats WHERE id = $1`, chatID).Scan(&chatType)
		if chatType == model.ChatTypeChannel {
			enc = model.EncryptionNone
		}
		_, err = s.pool.Exec(context.Background(), `
			INSERT INTO group_settings (
				group_id, membership_version, join_by_invite_only, join_requests_enabled,
				perm_invite, perm_send_messages, perm_pin, perm_moderate,
				visibility, description, encryption_mode, updated_at
			) VALUES ($1, 1, TRUE, FALSE, 'admin', 'all', 'admin', 'admin', 'private', '', $2, $3)
			ON CONFLICT (group_id) DO NOTHING
		`, chatID, enc, now)
		if err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	members, err := s.ListGroupMembers(chatID)
	if err != nil {
		return err
	}
	for _, member := range members {
		if member.Role == GroupRoleOwner {
			return nil
		}
	}
	ownerID := ""
	if preferredOwnerID != "" {
		for _, member := range members {
			if member.UserID == preferredOwnerID {
				ownerID = preferredOwnerID
				break
			}
		}
	}
	if ownerID == "" && len(members) > 0 {
		ownerID = members[0].UserID
	}
	if ownerID == "" {
		return nil
	}
	return s.SetGroupMemberRole(chatID, ownerID, GroupRoleOwner)
}

func (s *PostgresStore) FindChannelBySlug(slug string) (model.Chat, GroupSettings, error) {
	slug = normalizeChannelSlug(slug)
	if slug == "" {
		return model.Chat{}, GroupSettings{}, ErrNotFound
	}
	var chat model.Chat
	var settings GroupSettings
	var slugVal *string
	err := s.pool.QueryRow(context.Background(), `
		SELECT c.id, c.title, c.chat_type, c.created_at, COALESCE(c.avatar_url, ''),
		       gs.group_id, gs.membership_version, gs.join_by_invite_only, gs.join_requests_enabled,
		       gs.perm_invite, gs.perm_send_messages, gs.perm_pin, gs.perm_moderate,
		       gs.visibility, gs.slug, COALESCE(gs.description, ''), gs.encryption_mode, gs.updated_at
		FROM group_settings gs
		INNER JOIN chats c ON c.id = gs.group_id
		WHERE c.chat_type = $1
		  AND lower(gs.slug) = lower($2)
	`, model.ChatTypeChannel, slug).Scan(
		&chat.ID, &chat.Title, &chat.Type, &chat.CreatedAt, &chat.AvatarURL,
		&settings.GroupID, &settings.MembershipVersion, &settings.JoinByInviteOnly, &settings.JoinRequestsEnabled,
		&settings.PermInvite, &settings.PermSendMessages, &settings.PermPin, &settings.PermModerate,
		&settings.Visibility, &slugVal, &settings.Description, &settings.EncryptionMode, &settings.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Chat{}, GroupSettings{}, ErrNotFound
	}
	if err != nil {
		return model.Chat{}, GroupSettings{}, err
	}
	if slugVal != nil {
		settings.Slug = *slugVal
	}
	chat.Description = settings.Description
	chat.Visibility = settings.Visibility
	chat.Slug = settings.Slug
	chat.Encryption = settings.EncryptionMode
	return chat, settings, nil
}

func (s *PostgresStore) IsChannelSlugAvailable(slug string) (bool, error) {
	slug = normalizeChannelSlug(slug)
	if slug == "" {
		return false, nil
	}
	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM group_settings WHERE slug IS NOT NULL AND lower(slug) = lower($1)
		)
	`, slug).Scan(&exists)
	if err != nil {
		return false, err
	}
	return !exists, nil
}

// SearchPublicChannels returns public channels matching title/slug/description for any user.
func (s *PostgresStore) SearchPublicChannels(query string, limit int) ([]model.Chat, error) {
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return nil, nil
	}
	if limit <= 0 {
		limit = 20
	}
	pattern := "%" + q + "%"
	rows, err := s.pool.Query(context.Background(), `
		SELECT c.id, c.title, c.chat_type, c.created_at, COALESCE(c.avatar_url, ''),
		       COALESCE(gs.description, ''), gs.visibility, COALESCE(gs.slug, ''), gs.encryption_mode
		FROM chats c
		INNER JOIN group_settings gs ON gs.group_id = c.id
		WHERE c.chat_type = $1
		  AND gs.visibility = $2
		  AND (
		    lower(c.title) LIKE $3
		    OR lower(COALESCE(gs.slug, '')) LIKE $3
		    OR lower(COALESCE(gs.description, '')) LIKE $3
		  )
		ORDER BY c.title ASC
		LIMIT $4
	`, model.ChatTypeChannel, model.VisibilityPublic, pattern, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]model.Chat, 0)
	for rows.Next() {
		var chat model.Chat
		if err := rows.Scan(
			&chat.ID, &chat.Title, &chat.Type, &chat.CreatedAt, &chat.AvatarURL,
			&chat.Description, &chat.Visibility, &chat.Slug, &chat.Encryption,
		); err != nil {
			return nil, err
		}
		out = append(out, chat)
	}
	return out, rows.Err()
}

func normalizeChannelSlug(slug string) string {
	return strings.ToLower(strings.TrimSpace(slug))
}

func (s *PostgresStore) IncrementGroupsCreated24h(userID string) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO messaging_limits (user_id, groups_created_24h, groups_window_start, updated_at)
		VALUES ($1, 1, NOW(), NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			groups_created_24h = CASE
				WHEN messaging_limits.groups_window_start < NOW() - INTERVAL '24 hours' THEN 1
				ELSE messaging_limits.groups_created_24h + 1
			END,
			groups_window_start = CASE
				WHEN messaging_limits.groups_window_start < NOW() - INTERVAL '24 hours' THEN NOW()
				ELSE messaging_limits.groups_window_start
			END,
			updated_at = NOW()
	`, userID)
	return err
}

func (s *PostgresStore) GroupsCreated24h(userID string) (int, error) {
	var count int
	var windowStart time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT groups_created_24h, groups_window_start
		FROM messaging_limits WHERE user_id = $1
	`, userID).Scan(&count, &windowStart)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	if windowStart.Before(NowUTC().Add(-24 * time.Hour)) {
		return 0, nil
	}
	return count, nil
}

func randomInviteToken() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func nullableUUID(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func metadataJSON(metadata map[string]any) []byte {
	if metadata == nil {
		return []byte("{}")
	}
	raw, err := json.Marshal(metadata)
	if err != nil {
		return []byte("{}")
	}
	return raw
}

func scanGroupAuditRows(rows pgx.Rows) ([]GroupAuditEvent, error) {
	var out []GroupAuditEvent
	for rows.Next() {
		var event GroupAuditEvent
		var actorID, targetUserID *string
		var metadata []byte
		if err := rows.Scan(&event.ID, &event.GroupID, &actorID, &event.Action, &targetUserID, &metadata, &event.CreatedAt); err != nil {
			return nil, err
		}
		if actorID != nil {
			event.ActorID = *actorID
		}
		if targetUserID != nil {
			event.TargetUserID = *targetUserID
		}
		out = append(out, event)
	}
	return out, rows.Err()
}
