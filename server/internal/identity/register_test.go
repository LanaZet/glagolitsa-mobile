// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

func TestPrepareRegister_rejectsInvalidUsername(t *testing.T) {
	_, _, err := prepareRegister(model.RegisterRequest{
		Username: "bad name",
		Password: "password123",
	}, auth.DefaultPasswordSettings())
	if err != errInvalidUsername {
		t.Fatalf("err = %v", err)
	}
}

func TestPrepareRegister_acceptsEnglishLettersUsername(t *testing.T) {
	prepared, field, err := prepareRegister(model.RegisterRequest{
		Username: "Tatiana",
		Password: "password123",
	}, auth.DefaultPasswordSettings())
	if err != nil || field != "" {
		t.Fatalf("err = %v field = %q", err, field)
	}
	if prepared.Username != "tatiana" {
		t.Fatalf("username = %q", prepared.Username)
	}
}

func TestPrepareRegister_rejectsDigitsAndSeparators(t *testing.T) {
	for _, username := range []string{"alice2", "alice-smith", "alice_smith", "alice.smith", "Татьяна"} {
		_, _, err := prepareRegister(model.RegisterRequest{
			Username: username,
			Password: "password123",
		}, auth.DefaultPasswordSettings())
		if err != errInvalidUsername {
			t.Fatalf("username %q err = %v, want %v", username, err, errInvalidUsername)
		}
	}
}
