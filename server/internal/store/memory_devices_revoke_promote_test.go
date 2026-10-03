// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"

	"glagolitsa/server/internal/model"
)

func TestMemoryStore_RevokeDevice_promotesOldestPendingWhenLastActiveGone(t *testing.T) {
	mem := NewMemory()
	accountID := "user-alice"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		"active-old": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
			MailboxToken: "mailbox-active",
		},
		"pending-a": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-pending-a",
		},
		"pending-b": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-pending-b",
		},
	}
	mem.mu.Unlock()

	if err := mem.RevokeDevice("active-old", accountID); err != nil {
		t.Fatalf("revoke last active: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()

	if mem.devices["active-old"].DeviceStatus != model.DeviceStatusRevoked {
		t.Fatalf("revoked device status = %q", mem.devices["active-old"].DeviceStatus)
	}

	// Lexicographically oldest pending id is promoted (memory store has no created_at).
	promoted := mem.devices["pending-a"].DeviceStatus
	stayed := mem.devices["pending-b"].DeviceStatus
	if promoted != model.DeviceStatusActive {
		t.Fatalf("pending-a status = %q, want active", promoted)
	}
	if stayed != model.DeviceStatusPending {
		t.Fatalf("pending-b status = %q, want pending", stayed)
	}
}

func TestMemoryStore_RevokeDevice_keepsOtherActiveWithoutPromote(t *testing.T) {
	mem := NewMemory()
	accountID := "user-alice"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		"active-1": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
		},
		"active-2": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
		},
		"pending-1": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
		},
	}
	mem.mu.Unlock()

	if err := mem.RevokeDevice("active-1", accountID); err != nil {
		t.Fatalf("revoke one active: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()
	if mem.devices["active-2"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("remaining active status = %q", mem.devices["active-2"].DeviceStatus)
	}
	if mem.devices["pending-1"].DeviceStatus != model.DeviceStatusPending {
		t.Fatalf("pending should stay pending when another active remains, got %q",
			mem.devices["pending-1"].DeviceStatus)
	}
}

func TestMemoryStore_CountActiveDevices_ignoresPending(t *testing.T) {
	mem := NewMemory()
	accountID := "user-pending"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		"pending-1": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
		},
		"revoked-1": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusRevoked,
		},
	}
	mem.mu.Unlock()

	count, err := mem.CountActiveDevices(accountID)
	if err != nil {
		t.Fatalf("CountActiveDevices: %v", err)
	}
	if count != 0 {
		t.Fatalf("count=%d want 0", count)
	}
}

func TestMemoryStore_RegisterDevice_reregisterPromotesWhenNoActive(t *testing.T) {
	mem := NewMemory()
	accountID := "user-bob"
	deviceID := "pending-device"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		deviceID: {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-keep",
		},
		"other-pending": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-other",
		},
	}
	mem.mu.Unlock()

	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    7,
		IdentityPublicKey: []byte("identity"),
		SignedPreKey: SignedPreKeyRecord{
			ID: 1, PublicKey: []byte("spk"), Signature: []byte("sig"), CreatedAt: 1,
		},
		PqPreKey: PqPreKeyRecord{
			ID: 1, PublicMaterial: []byte("pq"), Signature: []byte("pqsig"), CreatedAt: 1,
		},
	})
	if err != nil {
		t.Fatalf("re-register pending: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()
	if mem.devices[deviceID].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("status after re-register = %q, want active", mem.devices[deviceID].DeviceStatus)
	}
	if mem.devices[deviceID].MailboxToken != "mailbox-keep" {
		t.Fatalf("mailbox token should be preserved on re-register, got %q",
			mem.devices[deviceID].MailboxToken)
	}
	if mem.devices["other-pending"].DeviceStatus != model.DeviceStatusPending {
		t.Fatalf("other pending should stay pending, got %q",
			mem.devices["other-pending"].DeviceStatus)
	}
}

func TestMemoryStore_RegisterDevice_reregisterKeepsPendingWhenActiveExists(t *testing.T) {
	mem := NewMemory()
	accountID := "user-bob"
	deviceID := "pending-device"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		"active-device": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
		},
		deviceID: {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-pending",
		},
	}
	mem.mu.Unlock()

	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    7,
		IdentityPublicKey: []byte("identity"),
		SignedPreKey: SignedPreKeyRecord{
			ID: 1, PublicKey: []byte("spk"), Signature: []byte("sig"), CreatedAt: 1,
		},
		PqPreKey: PqPreKeyRecord{
			ID: 1, PublicMaterial: []byte("pq"), Signature: []byte("pqsig"), CreatedAt: 1,
		},
	})
	if err != nil {
		t.Fatalf("re-register pending with active present: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()
	if mem.devices[deviceID].DeviceStatus != model.DeviceStatusPending {
		t.Fatalf("status = %q, want pending (confirm still required)", mem.devices[deviceID].DeviceStatus)
	}
}
