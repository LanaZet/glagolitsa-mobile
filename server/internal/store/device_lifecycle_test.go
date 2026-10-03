// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"
	"time"

	"glagolitsa/server/internal/model"
)

func TestMemoryStore_PurgeStaleDevices_revokesPendingAndActiveZombies(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-1"
	mem.users = map[string]*userRecord{
		accountID: {User: model.User{ID: accountID, Username: "u"}, AccountStatus: model.AccountStatusActive},
	}
	now := NowUTC()
	mem.devices = map[string]*deviceRecord{
		"live": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
			MailboxToken: "mb-live",
			CreatedAt:    now.Add(-time.Hour),
			LastSeenAt:   now,
		},
		"zombie-active": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusActive,
			MailboxToken: "mb-zombie",
			CreatedAt:    now.Add(-10 * 24 * time.Hour),
			LastSeenAt:   now.Add(-8 * 24 * time.Hour), // past 7d active stale TTL
		},
		"zombie-pending": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mb-pending",
			CreatedAt:    now.Add(-48 * time.Hour),
			LastSeenAt:   now.Add(-48 * time.Hour),
		},
	}
	mem.messageQueue = map[string][]queuedEnvelopeRecord{
		"mb-zombie":  {{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "e1"}}},
		"mb-pending": {{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "e2"}}},
		"mb-live":    {{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "e3"}}},
	}

	purged, err := mem.PurgeStaleDevices()
	if err != nil {
		t.Fatalf("PurgeStaleDevices: %v", err)
	}
	if purged != 2 {
		t.Fatalf("purged=%d want 2", purged)
	}
	if mem.devices["live"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("live device status = %q", mem.devices["live"].DeviceStatus)
	}
	if mem.devices["zombie-active"].DeviceStatus != model.DeviceStatusRevoked {
		t.Fatalf("zombie-active status = %q", mem.devices["zombie-active"].DeviceStatus)
	}
	if mem.devices["zombie-pending"].DeviceStatus != model.DeviceStatusRevoked {
		t.Fatalf("zombie-pending status = %q", mem.devices["zombie-pending"].DeviceStatus)
	}
	if _, ok := mem.messageQueue["mb-zombie"]; ok {
		t.Fatal("zombie mailbox should be purged")
	}
	if _, ok := mem.messageQueue["mb-live"]; !ok {
		t.Fatal("live mailbox must remain")
	}
}

func TestMemoryStore_PurgeStaleDevices_keepsLastActiveMailbox(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-last"
	mem.users = map[string]*userRecord{
		accountID: {User: model.User{ID: accountID, Username: "offline"}, AccountStatus: model.AccountStatusActive},
	}
	now := NowUTC()
	mem.devices = map[string]*deviceRecord{
		"only-phone": {
			AccountID:         accountID,
			DeviceStatus:      model.DeviceStatusActive,
			MailboxToken:      "mb-only",
			IdentityPublicKey: []byte{1, 2, 3},
			CreatedAt:         now.Add(-14 * 24 * time.Hour),
			LastSeenAt:        now.Add(-8 * 24 * time.Hour),
		},
	}
	mem.messageQueue = map[string][]queuedEnvelopeRecord{
		"mb-only": {{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "waiting"}}},
	}

	purged, err := mem.PurgeStaleDevices()
	if err != nil {
		t.Fatalf("PurgeStaleDevices: %v", err)
	}
	if purged != 0 {
		t.Fatalf("purged=%d want 0 (last active must stay)", purged)
	}
	if mem.devices["only-phone"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("status=%q want active", mem.devices["only-phone"].DeviceStatus)
	}
	if _, ok := mem.messageQueue["mb-only"]; !ok {
		t.Fatal("queued mail for the last device must survive")
	}

	devices, err := mem.ListUserDevices(accountID, true)
	if err != nil {
		t.Fatalf("ListUserDevices: %v", err)
	}
	if len(devices) != 1 || devices[0].DeviceID != "only-phone" {
		t.Fatalf("peer list after purge = %+v, want last active", devices)
	}
}

func TestMemoryStore_PurgeStaleDevices_keepsNewestOfTwoStaleActives(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-two"
	mem.users = map[string]*userRecord{
		accountID: {User: model.User{ID: accountID, Username: "two"}, AccountStatus: model.AccountStatusActive},
	}
	now := NowUTC()
	mem.devices = map[string]*deviceRecord{
		"older": {
			AccountID:         accountID,
			DeviceStatus:      model.DeviceStatusActive,
			MailboxToken:      "mb-old",
			IdentityPublicKey: []byte{1},
			CreatedAt:         now.Add(-20 * 24 * time.Hour),
			LastSeenAt:        now.Add(-10 * 24 * time.Hour),
		},
		"newer": {
			AccountID:         accountID,
			DeviceStatus:      model.DeviceStatusActive,
			MailboxToken:      "mb-new",
			IdentityPublicKey: []byte{2},
			CreatedAt:         now.Add(-14 * 24 * time.Hour),
			LastSeenAt:        now.Add(-8 * 24 * time.Hour),
		},
	}

	purged, err := mem.PurgeStaleDevices()
	if err != nil {
		t.Fatalf("PurgeStaleDevices: %v", err)
	}
	if purged != 1 {
		t.Fatalf("purged=%d want 1", purged)
	}
	if mem.devices["newer"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("newer status=%q want active", mem.devices["newer"].DeviceStatus)
	}
	if mem.devices["older"].DeviceStatus != model.DeviceStatusRevoked {
		t.Fatalf("older status=%q want revoked", mem.devices["older"].DeviceStatus)
	}
}

func TestMemoryStore_RegisterDevice_reenrollsRevoked(t *testing.T) {
	mem := NewMemory()
	accountID := "acct-2"
	mem.users = map[string]*userRecord{
		accountID: {User: model.User{ID: accountID, Username: "u2"}, AccountStatus: model.AccountStatusActive},
	}
	mem.devices = map[string]*deviceRecord{
		"dev-1": {
			AccountID:         accountID,
			DeviceStatus:      model.DeviceStatusRevoked,
			MailboxToken:      "mb-1",
			IdentityPublicKey: []byte{1},
			CreatedAt:         NowUTC().Add(-time.Hour),
		},
	}

	_, err := mem.RegisterDevice(DeviceRegistration{
		DeviceID:          "dev-1",
		AccountID:         accountID,
		RegistrationID:    7,
		IdentityPublicKey: []byte{2},
		SignedPreKey:      SignedPreKeyRecord{ID: 1, PublicKey: []byte{3}, Signature: []byte{4}, CreatedAt: 1},
		PqPreKey:          PqPreKeyRecord{ID: 2, PublicMaterial: []byte{5}, Signature: []byte{6}, CreatedAt: 1},
	})
	if err != nil {
		t.Fatalf("RegisterDevice: %v", err)
	}
	if mem.devices["dev-1"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("status=%q want active after sole re-enroll", mem.devices["dev-1"].DeviceStatus)
	}
}
