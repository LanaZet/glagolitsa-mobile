// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"os"
	"testing"
)

func TestRegistrationPowDifficulty_default(t *testing.T) {
	old := os.Getenv("REGISTRATION_POW_DIFFICULTY")
	t.Cleanup(func() {
		if old == "" {
			_ = os.Unsetenv("REGISTRATION_POW_DIFFICULTY")
		} else {
			_ = os.Setenv("REGISTRATION_POW_DIFFICULTY", old)
		}
	})
	_ = os.Unsetenv("REGISTRATION_POW_DIFFICULTY")
	if got := registrationPowDifficulty(); got != defaultRegistrationPowDifficulty {
		t.Fatalf("difficulty = %d, want %d", got, defaultRegistrationPowDifficulty)
	}
}

func TestRegistrationPowDifficulty_envOverride(t *testing.T) {
	old := os.Getenv("REGISTRATION_POW_DIFFICULTY")
	t.Cleanup(func() {
		if old == "" {
			_ = os.Unsetenv("REGISTRATION_POW_DIFFICULTY")
		} else {
			_ = os.Setenv("REGISTRATION_POW_DIFFICULTY", old)
		}
	})
	_ = os.Setenv("REGISTRATION_POW_DIFFICULTY", "16")
	if got := registrationPowDifficulty(); got != 16 {
		t.Fatalf("difficulty = %d, want 16", got)
	}
}

func TestRegistrationPowDifficulty_invalidFallsBackToDefault(t *testing.T) {
	old := os.Getenv("REGISTRATION_POW_DIFFICULTY")
	t.Cleanup(func() {
		if old == "" {
			_ = os.Unsetenv("REGISTRATION_POW_DIFFICULTY")
		} else {
			_ = os.Setenv("REGISTRATION_POW_DIFFICULTY", old)
		}
	})
	_ = os.Setenv("REGISTRATION_POW_DIFFICULTY", "99")
	if got := registrationPowDifficulty(); got != defaultRegistrationPowDifficulty {
		t.Fatalf("difficulty = %d, want default %d", got, defaultRegistrationPowDifficulty)
	}
}
