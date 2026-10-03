// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "testing"

func TestPrivacyGuardRejectsMessageBody(t *testing.T) {
	guard := PrivacyGuard{}
	err := guard.ValidatePayload(map[string]any{
		"schema": "1",
		"body":   "secret",
	})
	if err == nil {
		t.Fatal("expected privacy error for body field")
	}
}

func TestPrivacyGuardRejectsSenderNameAndPhone(t *testing.T) {
	guard := PrivacyGuard{}
	for _, key := range []string{"sender_name", "phone", "preview", "ciphertext", "loc_args"} {
		err := guard.ValidatePayload(map[string]any{key: "x"})
		if err == nil {
			t.Fatalf("expected reject for %q", key)
		}
	}
}

func TestPrivacyGuardRejectsUnknownKeys(t *testing.T) {
	guard := PrivacyGuard{}
	// arXiv: no free-form metadata in FCM — allowlist only.
	err := guard.ValidatePayload(map[string]any{
		"user_id": "u1",
	})
	if err == nil {
		t.Fatal("expected reject for unknown user_id key")
	}
}

func TestPrivacyGuardAllowsOpaqueIDs(t *testing.T) {
	guard := PrivacyGuard{}
	err := guard.ValidatePayload(map[string]any{
		"schema":        "1",
		"envelope_id":   "env-1",
		"mailbox_token": "mb-1",
		"device_id":     "dev-1",
		"remaining":     3,
	})
	if err != nil {
		t.Fatalf("unexpected privacy error: %v", err)
	}
}

func TestPrivacyGuardCallWakeOnly(t *testing.T) {
	guard := PrivacyGuard{}
	if err := guard.ValidatePayload(map[string]any{
		"schema":      "1",
		"type":        "incoming_call",
		"call_id":     "call-1",
		"collapse_id": "call-call-1",
	}); err != nil {
		t.Fatalf("wake-only call payload: %v", err)
	}
	if err := guard.ValidatePayload(map[string]any{
		"call_id":   "call-1",
		"caller_id": "acct-1",
	}); err == nil {
		t.Fatal("caller_id must be rejected for call push")
	}
}

func TestSanitizeUserVisibleText_NoContent(t *testing.T) {
	prefs := DefaultPreferences("u1")
	title, body := SanitizeUserVisibleText(TypeNewMessage, prefs)
	if title != "Глаголица" || body != "Новое сообщение" {
		t.Fatalf("got %q / %q", title, body)
	}
	if body == "" || title == "" {
		t.Fatal("expected generic user-visible copy for high-priority path")
	}
}

func TestIsPermanentTokenError(t *testing.T) {
	if !IsPermanentTokenError(errString("fcm http 404: UNREGISTERED")) {
		t.Fatal("expected permanent")
	}
	if IsPermanentTokenError(errString("fcm http 500: internal")) {
		t.Fatal("500 should be transient")
	}
}

type errString string

func (e errString) Error() string { return string(e) }
