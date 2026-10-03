// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "testing"

func TestNormalizeAvatarURL(t *testing.T) {
	got, err := NormalizeAvatarURL("  data:image/png;base64,YWJj  ")
	if err != nil {
		t.Fatalf("valid avatar: %v", err)
	}
	if got != "data:image/png;base64,YWJj" {
		t.Fatalf("got %q", got)
	}
	if _, err := NormalizeAvatarURL("https://example.com/a.png"); err == nil {
		t.Fatal("expected reject for remote url")
	}
	if _, err := NormalizeAvatarURL("data:image/svg+xml;base64,PHN2Zy8+"); err == nil {
		t.Fatal("expected reject for svg avatar")
	}
	if _, err := NormalizeAvatarURL("data:image/png;base64,not base64"); err == nil {
		t.Fatal("expected reject for invalid base64")
	}
	empty, err := NormalizeAvatarURL("   ")
	if err != nil || empty != "" {
		t.Fatalf("empty = %q err=%v", empty, err)
	}
}
