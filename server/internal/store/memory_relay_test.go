// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"
	"time"
)

func registerTestDevice(t *testing.T, mem *MemoryStore, accountID, deviceID string) string {
	t.Helper()
	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    42,
		IdentityPublicKey: bytesRepeat(32, 7),
		SignedPreKey:      SignedPreKeyRecord{ID: 1, PublicKey: bytesRepeat(32, 1), Signature: bytesRepeat(64, 2)},
		PqPreKey:          PqPreKeyRecord{ID: 1, PublicMaterial: bytesRepeat(32, 3), Signature: bytesRepeat(64, 4)},
		OneTimePrekeys:    []OneTimePreKeyRecord{{ID: 301, PublicKey: bytesRepeat(32, 5)}},
	})
	if err != nil {
		t.Fatalf("register device: %v", err)
	}
	token, err := mem.GetDeviceMailboxToken(deviceID, accountID)
	if err != nil {
		t.Fatalf("mailbox token: %v", err)
	}
	return token
}

func bytesRepeat(n, v int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(v)
	}
	return b
}

func TestSizeBucket_variousLengths(t *testing.T) {
	tests := []struct {
		length int
		want   int
	}{
		{10, 512},
		{512, 512},
		{513, 1024},
		{9000, 16384},
		{40000, 40960},
	}
	for _, tc := range tests {
		if got := SizeBucket(tc.length); got != tc.want {
			t.Fatalf("SizeBucket(%d) = %d, want %d", tc.length, got, tc.want)
		}
	}
}

func TestMemoryRelay_expiredEnvelopesFilteredFromQueue(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-1"
	deviceID := "dev-1"
	token := registerTestDevice(t, mem, accountID, deviceID)

	past := NowUTC().Add(-time.Hour)
	ids, err := mem.EnqueueRelayEnvelopes([]RelayEnvelopeInput{{
		MailboxToken: token,
		EnvelopeType: 3,
		Ciphertext:   []byte("expired"),
		SizeBucket:   SizeBucket(7),
		ExpiresAt:    past,
	}})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	if len(ids) != 1 {
		t.Fatalf("ids = %v", ids)
	}

	records, err := mem.ListQueuedEnvelopes([]string{token}, 10)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(records) != 0 {
		t.Fatalf("expired envelope must not be listed, got %d", len(records))
	}
}

func TestMemoryRelay_mailboxRotationGraceResolvesPreviousToken(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-2"
	deviceID := "dev-2"
	oldToken := registerTestDevice(t, mem, accountID, deviceID)

	rotated, err := mem.RotateDeviceMailbox(deviceID, accountID)
	if err != nil {
		t.Fatalf("rotate: %v", err)
	}
	if rotated.PreviousMailboxToken != oldToken {
		t.Fatalf("previous = %q, want %q", rotated.PreviousMailboxToken, oldToken)
	}

	owner, err := mem.MailboxOwnerAccountID(oldToken)
	if err != nil {
		t.Fatalf("grace owner lookup: %v", err)
	}
	if owner != accountID {
		t.Fatalf("owner = %q", owner)
	}

	tokens, err := mem.ResolveMailboxTokens(deviceID, accountID)
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if len(tokens) != 2 {
		t.Fatalf("tokens = %v, want current+previous", tokens)
	}
}

func TestMemoryRelay_previousMailboxExpiredNotResolved(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-3"
	deviceID := "dev-3"
	oldToken := registerTestDevice(t, mem, accountID, deviceID)

	_, err := mem.RotateDeviceMailbox(deviceID, accountID)
	if err != nil {
		t.Fatalf("rotate: %v", err)
	}
	mem.mu.Lock()
	mem.devices[deviceID].MailboxPreviousExpires = NowUTC().Add(-time.Hour)
	mem.mu.Unlock()

	if _, err := mem.MailboxOwnerAccountID(oldToken); err != ErrNotFound {
		t.Fatalf("expired previous token err = %v, want not found", err)
	}
}

func TestMemoryRelay_ackOnlyDeletesFromOwnMailboxes(t *testing.T) {
	mem := NewMemory()
	aliceID, bobID := "alice", "bob"
	aliceDev, bobDev := "alice-dev", "bob-dev"
	aliceToken := registerTestDevice(t, mem, aliceID, aliceDev)
	bobToken := registerTestDevice(t, mem, bobID, bobDev)

	aliceIDs, err := mem.EnqueueRelayEnvelopes([]RelayEnvelopeInput{{
		MailboxToken: aliceToken,
		EnvelopeType: 3,
		Ciphertext:   []byte("for-alice"),
		SizeBucket:   512,
		ExpiresAt:    NowUTC().Add(time.Hour),
	}})
	if err != nil {
		t.Fatalf("enqueue alice: %v", err)
	}
	bobIDs, err := mem.EnqueueRelayEnvelopes([]RelayEnvelopeInput{{
		MailboxToken: bobToken,
		EnvelopeType: 3,
		Ciphertext:   []byte("for-bob"),
		SizeBucket:   512,
		ExpiresAt:    NowUTC().Add(time.Hour),
	}})
	if err != nil {
		t.Fatalf("enqueue bob: %v", err)
	}

	deleted, err := mem.AckQueuedEnvelopes([]string{aliceToken}, []string{bobIDs[0]})
	if err != nil {
		t.Fatalf("ack: %v", err)
	}
	if deleted != 0 {
		t.Fatalf("foreign ack deleted = %d, want 0", deleted)
	}

	deleted, err = mem.AckQueuedEnvelopes([]string{aliceToken}, []string{aliceIDs[0]})
	if err != nil {
		t.Fatalf("ack own: %v", err)
	}
	if deleted != 1 {
		t.Fatalf("own ack deleted = %d, want 1", deleted)
	}
}
