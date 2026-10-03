// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func TestCheckPasswordOrDummy_runsWithoutStoredHash(t *testing.T) {
	if CheckPasswordOrDummy("", "any-password") {
		t.Fatal("expected dummy check to fail for wrong password")
	}
}

func TestDummyPasswordHash_usesDefaultCost(t *testing.T) {
	cost, err := bcrypt.Cost([]byte(dummyPasswordHash))
	if err != nil {
		t.Fatalf("dummy hash must be valid bcrypt: %v", err)
	}
	if cost != bcrypt.DefaultCost {
		t.Fatalf("dummy hash cost = %d, want %d", cost, bcrypt.DefaultCost)
	}
}
