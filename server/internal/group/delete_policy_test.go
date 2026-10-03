// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"errors"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestCanPermanentlyDeleteChat(t *testing.T) {
	if err := canPermanentlyDeleteChat(model.ChatTypeGroup, store.GroupRoleOwner); err != nil {
		t.Fatalf("owner may delete group: %v", err)
	}
	if err := canPermanentlyDeleteChat(model.ChatTypeChannel, store.GroupRoleOwner); err != nil {
		t.Fatalf("owner may delete channel: %v", err)
	}
	if err := canPermanentlyDeleteChat(model.ChatTypeGroup, store.GroupRoleAdmin); !errors.Is(err, ErrForbidden) {
		t.Fatalf("admin must not wipe: %v", err)
	}
	if err := canPermanentlyDeleteChat(model.ChatTypeGroup, store.GroupRoleMember); !errors.Is(err, ErrForbidden) {
		t.Fatalf("member must not wipe: %v", err)
	}
	if err := canPermanentlyDeleteChat(model.ChatTypeDM, store.GroupRoleOwner); !errors.Is(err, ErrForbidden) {
		t.Fatalf("DM must not use this API: %v", err)
	}
}
