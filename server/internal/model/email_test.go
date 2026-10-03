// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "testing"

// Порт Mattermost TestNormalizeEmail / IsValidEmail.

func TestNormalizeEmail_mattermostCases(t *testing.T) {
	cases := map[string]string{
		"TEST@EXAMPLE.COM":  "test@example.com",
		"TEST2@example.com": "test2@example.com",
		"test3@example.com": "test3@example.com",
	}
	for input, want := range cases {
		if got := NormalizeEmail(input); got != want {
			t.Errorf("NormalizeEmail(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestIsValidEmail(t *testing.T) {
	if !IsValidEmail("alice@example.com") {
		t.Fatal("expected valid email")
	}
	if IsValidEmail("NOT-LOWER@EXAMPLE.COM") {
		t.Fatal("expected uppercase email to be invalid before normalization")
	}
	if IsValidEmail("not-an-email") {
		t.Fatal("expected invalid email")
	}
	if IsValidEmail("<alice@example.com>") {
		t.Fatal("expected angle-bracket email to be invalid")
	}
}