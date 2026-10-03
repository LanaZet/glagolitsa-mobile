// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"strings"
	"testing"
)

func TestOpaqueParticipantID_stableAndDoesNotEmbedUser(t *testing.T) {
	a := OpaqueParticipantID("secret", "call-1", "user-alice", "dev-1")
	b := OpaqueParticipantID("secret", "call-1", "user-alice", "dev-1")
	if a != b {
		t.Fatal("expected stable id")
	}
	if strings.Contains(a, "alice") || strings.Contains(a, "user-") {
		t.Fatalf("must not embed user id: %s", a)
	}
	if !strings.HasPrefix(a, "cp_") {
		t.Fatalf("prefix: %s", a)
	}
	other := OpaqueParticipantID("secret", "call-1", "user-bob", "dev-1")
	if other == a {
		t.Fatal("different users must differ")
	}
	otherDevice := OpaqueParticipantID("secret", "call-1", "user-alice", "dev-2")
	if otherDevice == a {
		t.Fatal("different devices must differ")
	}
}
