// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"

	"glagolitsa/server/internal/model"
)

func TestMemoryStore_GetProfilesByIDsIncludesCustomStatus(t *testing.T) {
	mem := NewMemory()
	alice, err := mem.CreateUser(model.User{Username: "alice"}, "hash")
	if err != nil {
		t.Fatalf("create alice: %v", err)
	}
	bob, err := mem.CreateUser(model.User{
		Username:  "bob",
		Status:    "popik",
		Bio:       "bio text",
		AvatarURL: "data:image/png;base64,avatar",
	}, "hash")
	if err != nil {
		t.Fatalf("create bob: %v", err)
	}

	hits, err := mem.GetProfilesByIDs([]string{bob.ID}, alice.ID)
	if err != nil {
		t.Fatalf("get profiles: %v", err)
	}
	if len(hits) != 1 {
		t.Fatalf("hits=%d want 1", len(hits))
	}
	if hits[0].Status != "popik" {
		t.Fatalf("status=%q want popik", hits[0].Status)
	}
	if hits[0].Bio != "bio text" {
		t.Fatalf("bio=%q want bio text", hits[0].Bio)
	}
}
