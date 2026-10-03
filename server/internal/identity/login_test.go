// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

func TestPrepareLogin_normalizesUsername(t *testing.T) {
	prepared, err := prepareLogin(model.LoginRequest{
		Username: "  Alice ",
		Password: "password123",
	})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if prepared.Username != "alice" {
		t.Fatalf("username = %q", prepared.Username)
	}
}

func TestPrepareLogin_rejectsLongPassword(t *testing.T) {
	_, err := prepareLogin(model.LoginRequest{
		Username: "alice",
		Password: string(make([]byte, auth.PasswordMaximumLength+1)),
	})
	if err != errPasswordTooLong {
		t.Fatalf("err = %v", err)
	}
}
