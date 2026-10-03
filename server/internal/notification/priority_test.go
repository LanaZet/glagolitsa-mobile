// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "testing"

func TestPriorityForType_Firebase2025(t *testing.T) {
	// High only when client will show user-visible UI (or VoIP).
	if got := PriorityForType(TypeNewMessage); got != PriorityHigh {
		t.Fatalf("new_message: got %q want high (local UI after wake)", got)
	}
	if got := PriorityForType(TypeMessageSync); got != PriorityHigh {
		t.Fatalf("message_sync: got %q want high", got)
	}
	if got := PriorityForType(TypeIncomingCall); got != PriorityVoIP {
		t.Fatalf("incoming_call: got %q want voip", got)
	}
	if got := PriorityForType(TypeMissedCall); got != PriorityHigh {
		t.Fatalf("missed_call: got %q want high", got)
	}
	// Silent housekeeping must not burn high-priority quota.
	if got := PriorityForType(TypePrekeysLow); got != PriorityNormal {
		t.Fatalf("prekeys.low: got %q want normal (no user-visible UI)", got)
	}
}

func TestCollapseKeyForType(t *testing.T) {
	if got := CollapseKeyForType(TypeNewMessage); got != "glag_msg_wake" {
		t.Fatalf("collapse: got %q", got)
	}
	if got := CollapseKeyForType(TypePrekeysLow); got != "" {
		t.Fatalf("prekeys should not collapse: %q", got)
	}
}
