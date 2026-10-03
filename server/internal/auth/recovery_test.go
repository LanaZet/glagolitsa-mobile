// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import "testing"

func TestNormalizeRecoveryKey_stripsGrouping(t *testing.T) {
	got := NormalizeRecoveryKey("  ABCD-ef01 2345 ")
	if got != "abcdef012345" {
		t.Fatalf("normalize = %q", got)
	}
}

func TestHashRecoveryKey_ignoresDashesAndCase(t *testing.T) {
	a := HashRecoveryKey("abcd-ef01-2345-6789")
	b := HashRecoveryKey("ABCDEF0123456789")
	if a != b {
		t.Fatalf("hashes differ: %s vs %s", a, b)
	}
	if a == DummyRecoveryKeyHash {
		t.Fatal("real key hashed to dummy")
	}
}
