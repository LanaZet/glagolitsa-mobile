// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"

	"glagolitsa/server/internal/model"
)

func TestMemoryStore_RegisterDevice_activatesWhenOnlyPendingDevicesExist(t *testing.T) {
	mem := NewMemory()
	accountID := "user-bob"

	mem.mu.Lock()
	mem.devices = map[string]*deviceRecord{
		"stale-pending": {
			AccountID:    accountID,
			DeviceStatus: model.DeviceStatusPending,
			MailboxToken: "mailbox-stale",
		},
	}
	mem.mu.Unlock()

	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          "fresh-device",
		RegistrationID:    42,
		IdentityPublicKey: []byte("identity"),
		SignedPreKey: SignedPreKeyRecord{
			ID:        1,
			PublicKey: []byte("signed-public"),
			Signature: []byte("signed-signature"),
			CreatedAt: 1,
		},
		PqPreKey: PqPreKeyRecord{
			ID:             1,
			PublicMaterial: []byte("pq-public"),
			Signature:      []byte("pq-signature"),
			CreatedAt:      1,
		},
	})
	if err != nil {
		t.Fatalf("register fresh device: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()
	if mem.devices["fresh-device"].DeviceStatus != model.DeviceStatusActive {
		t.Fatalf("fresh status = %q, want active", mem.devices["fresh-device"].DeviceStatus)
	}
}

func TestMemoryStore_RegisterDevice_replacesOneTimePrekeysOnReregister(t *testing.T) {
	mem := NewMemory()
	accountID := "user-frick"
	deviceID := "phone-stable-id"

	stored, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    1,
		IdentityPublicKey: []byte("identity-v1"),
		SignedPreKey:      SignedPreKeyRecord{ID: 1, PublicKey: []byte("spk1"), Signature: []byte("sig1"), CreatedAt: 1},
		PqPreKey:          PqPreKeyRecord{ID: 1, PublicMaterial: []byte("pq1"), Signature: []byte("pqs1"), CreatedAt: 1},
		OneTimePrekeys: []OneTimePreKeyRecord{
			{ID: 101, PublicKey: []byte("otp-old-a")},
			{ID: 102, PublicKey: []byte("otp-old-b")},
		},
	})
	if err != nil {
		t.Fatalf("first register: %v", err)
	}
	if stored != 2 {
		t.Fatalf("first stored=%d want 2", stored)
	}

	// Reinstall with same stable device_id: new identity + new OTP private keys.
	stored, err = mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    2,
		IdentityPublicKey: []byte("identity-v2"),
		SignedPreKey:      SignedPreKeyRecord{ID: 2, PublicKey: []byte("spk2"), Signature: []byte("sig2"), CreatedAt: 2},
		PqPreKey:          PqPreKeyRecord{ID: 2, PublicMaterial: []byte("pq2"), Signature: []byte("pqs2"), CreatedAt: 2},
		OneTimePrekeys: []OneTimePreKeyRecord{
			{ID: 201, PublicKey: []byte("otp-new-a")},
			{ID: 202, PublicKey: []byte("otp-new-b")},
		},
	})
	if err != nil {
		t.Fatalf("reregister: %v", err)
	}
	if stored != 2 {
		t.Fatalf("reregister stored=%d want 2", stored)
	}

	bundle, err := mem.GetDeviceBundle(deviceID)
	if err != nil {
		t.Fatalf("GetDeviceBundle: %v", err)
	}
	if string(bundle.IdentityPublicKey) != "identity-v2" {
		t.Fatalf("identity=%q want identity-v2", bundle.IdentityPublicKey)
	}
	if bundle.OneTimePreKey == nil {
		t.Fatal("expected one-time prekey after reregister")
	}
	if bundle.OneTimePreKey.ID != 201 && bundle.OneTimePreKey.ID != 202 {
		t.Fatalf("got stale OTP id=%d, want only new batch 201/202", bundle.OneTimePreKey.ID)
	}
	if string(bundle.OneTimePreKey.PublicKey) != "otp-new-a" && string(bundle.OneTimePreKey.PublicKey) != "otp-new-b" {
		t.Fatalf("got stale OTP public key %q", bundle.OneTimePreKey.PublicKey)
	}

	// Second consume should still be from the new batch, never old 101/102.
	bundle2, err := mem.GetDeviceBundle(deviceID)
	if err != nil {
		t.Fatalf("GetDeviceBundle 2: %v", err)
	}
	if bundle2.OneTimePreKey == nil {
		t.Fatal("expected second new OTP")
	}
	if bundle2.OneTimePreKey.ID == 101 || bundle2.OneTimePreKey.ID == 102 {
		t.Fatalf("server served zombie OTP id=%d from pre-reinstall install", bundle2.OneTimePreKey.ID)
	}
}

func TestMemoryStore_RegisterDevice_identityChangePurgesMailbox(t *testing.T) {
	mem := NewMemory()
	accountID := "user-frick"
	deviceID := "phone-stable-id"

	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    1,
		IdentityPublicKey: []byte("identity-v1"),
		SignedPreKey:      SignedPreKeyRecord{ID: 1, PublicKey: []byte("spk1"), Signature: []byte("sig1"), CreatedAt: 1},
		PqPreKey:          PqPreKeyRecord{ID: 1, PublicMaterial: []byte("pq1"), Signature: []byte("pqs1"), CreatedAt: 1},
		OneTimePrekeys:    []OneTimePreKeyRecord{{ID: 1, PublicKey: []byte("otp1")}},
	})
	if err != nil {
		t.Fatalf("register: %v", err)
	}

	mem.mu.RLock()
	mailbox := mem.devices[deviceID].MailboxToken
	mem.mu.RUnlock()
	if mailbox == "" {
		t.Fatal("expected mailbox token")
	}

	mem.mu.Lock()
	mem.messageQueue[mailbox] = []queuedEnvelopeRecord{
		{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "poison-prekey"}},
	}
	mem.mu.Unlock()

	_, err = mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    2,
		IdentityPublicKey: []byte("identity-v2"),
		SignedPreKey:      SignedPreKeyRecord{ID: 2, PublicKey: []byte("spk2"), Signature: []byte("sig2"), CreatedAt: 2},
		PqPreKey:          PqPreKeyRecord{ID: 2, PublicMaterial: []byte("pq2"), Signature: []byte("pqs2"), CreatedAt: 2},
		OneTimePrekeys:    []OneTimePreKeyRecord{{ID: 9, PublicKey: []byte("otp9")}},
	})
	if err != nil {
		t.Fatalf("reregister: %v", err)
	}

	mem.mu.RLock()
	defer mem.mu.RUnlock()
	if _, ok := mem.messageQueue[mailbox]; ok {
		t.Fatal("poison mailbox must be purged when identity changes")
	}
}

func TestMemoryStore_RegisterDevice_sameIdentityKeepsMailboxButReplacesOTPs(t *testing.T) {
	mem := NewMemory()
	accountID := "user-frick"
	deviceID := "phone-stable-id"

	_, err := mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    1,
		IdentityPublicKey: []byte("identity-same"),
		SignedPreKey:      SignedPreKeyRecord{ID: 1, PublicKey: []byte("spk1"), Signature: []byte("sig1"), CreatedAt: 1},
		PqPreKey:          PqPreKeyRecord{ID: 1, PublicMaterial: []byte("pq1"), Signature: []byte("pqs1"), CreatedAt: 1},
		OneTimePrekeys:    []OneTimePreKeyRecord{{ID: 11, PublicKey: []byte("otp-old")}},
	})
	if err != nil {
		t.Fatalf("register: %v", err)
	}

	mem.mu.RLock()
	mailbox := mem.devices[deviceID].MailboxToken
	mem.mu.RUnlock()

	mem.mu.Lock()
	mem.messageQueue[mailbox] = []queuedEnvelopeRecord{
		{QueuedEnvelopeRecord: QueuedEnvelopeRecord{EnvelopeID: "still-readable-maybe"}},
	}
	mem.mu.Unlock()

	_, err = mem.RegisterDevice(DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    1,
		IdentityPublicKey: []byte("identity-same"),
		SignedPreKey:      SignedPreKeyRecord{ID: 3, PublicKey: []byte("spk3"), Signature: []byte("sig3"), CreatedAt: 3},
		PqPreKey:          PqPreKeyRecord{ID: 3, PublicMaterial: []byte("pq3"), Signature: []byte("pqs3"), CreatedAt: 3},
		OneTimePrekeys:    []OneTimePreKeyRecord{{ID: 33, PublicKey: []byte("otp-new")}},
	})
	if err != nil {
		t.Fatalf("reregister: %v", err)
	}

	mem.mu.RLock()
	if _, ok := mem.messageQueue[mailbox]; !ok {
		t.Fatal("same-identity re-register should not wipe mailbox")
	}
	if _, hasOld := mem.devices[deviceID].Prekeys[11]; hasOld {
		t.Fatal("old OTP must be replaced even when identity is unchanged")
	}
	if _, hasNew := mem.devices[deviceID].Prekeys[33]; !hasNew {
		t.Fatal("new OTP must be present")
	}
	mem.mu.RUnlock()
}
