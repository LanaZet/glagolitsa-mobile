// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"strings"
	"testing"
)

// Порт Mattermost TestIsPasswordValidWithSettings (channels/app/users/password_test.go).

func TestValidatePassword_mattermostCases(t *testing.T) {
	for name, tc := range map[string]struct {
		password      string
		settings      PasswordSettings
		expectError   bool
	}{
		"Short": {
			password: strings.Repeat("x", 5),
			settings: PasswordSettings{
				MinimumLength: PasswordMinimumLength,
			},
		},
		"Long": {
			password: strings.Repeat("x", PasswordMaximumLength),
			settings: PasswordSettings{},
		},
		"TooShort": {
			password:    strings.Repeat("x", 4),
			settings:    PasswordSettings{MinimumLength: PasswordMinimumLength},
			expectError: true,
		},
		"TooLong": {
			password:    strings.Repeat("x", PasswordMaximumLength+1),
			settings:    PasswordSettings{},
			expectError: true,
		},
		"MissingLower": {
			password: "AAAAAAAAAAASD123!@#",
			settings: PasswordSettings{
				MinimumLength: 8,
				RequireLower:  true,
			},
			expectError: true,
		},
		"MissingUpper": {
			password: "aaaaaaaaaaaaasd123!@#",
			settings: PasswordSettings{
				MinimumLength: 8,
				RequireUpper:  true,
			},
			expectError: true,
		},
		"MissingNumber": {
			password: "asasdasdsadASD!@#",
			settings: PasswordSettings{
				MinimumLength: 8,
				RequireNumber: true,
			},
			expectError: true,
		},
		"MissingSymbol": {
			password: "asdasdasdasdasdASD123",
			settings: PasswordSettings{
				MinimumLength: 8,
				RequireSymbol: true,
			},
			expectError: true,
		},
		"Everything": {
			password: "asdASD!@#123",
			settings: PasswordSettings{
				MinimumLength: 8,
				RequireLower:  true,
				RequireUpper:  true,
				RequireNumber: true,
				RequireSymbol: true,
			},
		},
	} {
		t.Run(name, func(t *testing.T) {
			err := ValidatePassword(tc.password, tc.settings)
			if tc.expectError && err == nil {
				t.Fatalf("expected error")
			}
			if !tc.expectError && err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
		})
	}
}