// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"os"
	"testing"
)

func TestPowRequired_canDisable(t *testing.T) {
	_ = os.Setenv("REGISTRATION_POW_REQUIRED", "false")
	if PowRequired() {
		t.Fatal("expected pow disabled")
	}
}
