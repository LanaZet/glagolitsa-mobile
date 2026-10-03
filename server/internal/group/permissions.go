// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"fmt"

	"glagolitsa/server/internal/store"
)

func canInvite(settings store.GroupSettings, role string, rights store.AdminRights) bool {
	if role == store.GroupRoleOwner {
		return true
	}
	if role == store.GroupRoleAdmin {
		return rights.InviteUsers
	}
	return settings.PermInvite == store.GroupPermAll
}

func canModerate(settings store.GroupSettings, role string, rights store.AdminRights) bool {
	if role == store.GroupRoleOwner {
		return true
	}
	if role == store.GroupRoleAdmin {
		return rights.BanUsers || rights.DeleteMessages
	}
	return settings.PermModerate == store.GroupPermAll
}

func canPin(settings store.GroupSettings, role string, rights store.AdminRights) bool {
	if role == store.GroupRoleOwner {
		return true
	}
	if role == store.GroupRoleAdmin {
		return rights.PinMessages
	}
	return settings.PermPin == store.GroupPermAll
}

func canChangeInfo(settings store.GroupSettings, role string, rights store.AdminRights) bool {
	if role == store.GroupRoleOwner {
		return true
	}
	if role == store.GroupRoleAdmin {
		return rights.ChangeInfo
	}
	return settings.PermChangeInfo == store.GroupPermAll
}

func canAddAdmins(role string, rights store.AdminRights) bool {
	if role == store.GroupRoleOwner {
		return true
	}
	return role == store.GroupRoleAdmin && rights.AddAdmins
}

func resolveAdminRights(role string, stored *store.AdminRights) store.AdminRights {
	return store.ResolveAdminRights(role, stored)
}

func canComment(settings store.GroupSettings, role string) bool {
	switch settings.PermComment {
	case store.GroupPermNone, "":
		if settings.PermComment == store.GroupPermNone {
			return false
		}
		// empty → treat as all (default)
		return true
	case store.GroupPermAll:
		return true
	case store.GroupPermAdmin:
		return store.IsPrivilegedRole(role)
	default:
		return store.IsPrivilegedRole(role)
	}
}

func canReact(settings store.GroupSettings, role string) bool {
	switch settings.PermReact {
	case store.GroupPermNone:
		return false
	case store.GroupPermAll, "":
		return true
	case store.GroupPermAdmin:
		return store.IsPrivilegedRole(role)
	default:
		return store.IsPrivilegedRole(role)
	}
}

func (s *Service) requireMember(groupID, userID string) (string, store.GroupSettings, error) {
	role, err := s.Store.GetGroupMemberRole(groupID, userID)
	if err != nil {
		return "", store.GroupSettings{}, mapStoreErr(err)
	}
	settings, err := s.Store.GetGroupSettings(groupID)
	if err != nil {
		return "", store.GroupSettings{}, mapStoreErr(err)
	}
	return role, settings, nil
}

func (s *Service) requireAdmin(groupID, userID string) (store.GroupSettings, error) {
	role, settings, err := s.requireMember(groupID, userID)
	if err != nil {
		return store.GroupSettings{}, err
	}
	if !store.IsPrivilegedRole(role) {
		return store.GroupSettings{}, ErrForbidden
	}
	return settings, nil
}

func (s *Service) requireInviteRight(groupID, userID string) (store.GroupSettings, error) {
	role, settings, rights, err := s.memberContext(groupID, userID)
	if err != nil {
		return store.GroupSettings{}, err
	}
	if !canInvite(settings, role, rights) {
		return store.GroupSettings{}, ErrForbidden
	}
	return settings, nil
}

func (s *Service) requireModerateRight(groupID, userID string) (store.GroupSettings, error) {
	role, settings, rights, err := s.memberContext(groupID, userID)
	if err != nil {
		return store.GroupSettings{}, err
	}
	if !canModerate(settings, role, rights) {
		return store.GroupSettings{}, ErrForbidden
	}
	return settings, nil
}

func (s *Service) requireModeratableTarget(groupID, actorID, targetUserID string) (store.GroupMember, error) {
	actorRole, settings, rights, err := s.memberContext(groupID, actorID)
	if err != nil {
		return store.GroupMember{}, err
	}
	if !canModerate(settings, actorRole, rights) {
		return store.GroupMember{}, ErrForbidden
	}
	if actorID == targetUserID {
		return store.GroupMember{}, fmt.Errorf("use leave endpoint to exit group")
	}
	target, err := s.Store.GetGroupMember(groupID, targetUserID)
	if err != nil {
		return store.GroupMember{}, mapStoreErr(err)
	}
	if target.Role == store.GroupRoleOwner {
		return store.GroupMember{}, ErrForbidden
	}
	if target.Role == store.GroupRoleAdmin && actorRole != store.GroupRoleOwner {
		return store.GroupMember{}, ErrForbidden
	}
	return target, nil
}

func (s *Service) requirePinRight(groupID, userID string) (store.GroupSettings, error) {
	role, settings, rights, err := s.memberContext(groupID, userID)
	if err != nil {
		return store.GroupSettings{}, err
	}
	if !canPin(settings, role, rights) {
		return store.GroupSettings{}, ErrForbidden
	}
	return settings, nil
}

func (s *Service) requireChangeInfoRight(groupID, userID string) (store.GroupSettings, error) {
	role, settings, rights, err := s.memberContext(groupID, userID)
	if err != nil {
		return store.GroupSettings{}, err
	}
	if !canChangeInfo(settings, role, rights) {
		return store.GroupSettings{}, ErrForbidden
	}
	return settings, nil
}

func (s *Service) memberContext(groupID, userID string) (string, store.GroupSettings, store.AdminRights, error) {
	member, err := s.Store.GetGroupMember(groupID, userID)
	if err != nil {
		return "", store.GroupSettings{}, store.AdminRights{}, mapStoreErr(err)
	}
	settings, err := s.Store.GetGroupSettings(groupID)
	if err != nil {
		return "", store.GroupSettings{}, store.AdminRights{}, mapStoreErr(err)
	}
	return member.Role, settings, resolveAdminRights(member.Role, member.AdminRights), nil
}

func validatePermValue(value string) error {
	switch value {
	case store.GroupPermAdmin, store.GroupPermAll:
		return nil
	default:
		return fmt.Errorf("permission must be admin or all")
	}
}

func validateEngagementPermValue(value string) error {
	switch value {
	case store.GroupPermAdmin, store.GroupPermAll, store.GroupPermNone:
		return nil
	default:
		return fmt.Errorf("permission must be admin, all, or none")
	}
}
