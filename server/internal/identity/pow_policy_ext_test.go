// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity_test

import (
	"os"
	"testing"

	"glagolitsa/server/internal/identity"
)

func TestPowRequired_defaultsTrue(t *testing.T) {
	old := os.Getenv("REGISTRATION_POW_REQUIRED")
	t.Cleanup(func() {
		if old == "" {
			_ = os.Unsetenv("REGISTRATION_POW_REQUIRED")
		} else {
			_ = os.Setenv("REGISTRATION_POW_REQUIRED", old)
		}
	})
	_ = os.Unsetenv("REGISTRATION_POW_REQUIRED")
	if !identity.PowRequired() {
		t.Fatal("expected pow required by default")
	}
}
