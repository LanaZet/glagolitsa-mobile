// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package relayauth

import "testing"

func TestDeliveryTokenForMailbox_validatesMatchingMailbox(t *testing.T) {
	t.Setenv("DELIVERY_TOKEN_SECRET", "test-secret")

	token := DeliveryTokenForMailbox("mailbox-1")
	if token == "" {
		t.Fatal("token is empty")
	}
	if !ValidateDeliveryToken("mailbox-1", token) {
		t.Fatal("token should validate for matching mailbox")
	}
	if ValidateDeliveryToken("mailbox-2", token) {
		t.Fatal("token should not validate for another mailbox")
	}
}
