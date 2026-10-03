// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import "time"

type SignedPreKeyRecord struct {
	ID        int
	PublicKey []byte
	Signature []byte
	CreatedAt int64
}

type PqPreKeyRecord struct {
	ID             int
	PublicMaterial []byte
	Signature      []byte
	CreatedAt      int64
}

type OneTimePreKeyRecord struct {
	ID        int
	PublicKey []byte
}

type DeviceRegistration struct {
	DeviceID          string
	AccountID         string
	RegistrationID    int
	IdentityPublicKey []byte
	SignedPreKey      SignedPreKeyRecord
	PqPreKey          PqPreKeyRecord
	OneTimePrekeys    []OneTimePreKeyRecord
	Attestation       *DeviceAttestationRecord
}

type DeviceAttestationRecord struct {
	ConfirmingDeviceID string
	Signature          []byte
}

type SignedPreKeyRotation struct {
	SignedPreKey SignedPreKeyRecord
	PqPreKey     PqPreKeyRecord
}

type KeyChangeEventRecord struct {
	ID              string
	AccountID       string
	DeviceID        string
	EventType       string
	IdentityKeyHash []byte
	SignedPrekeyID  int
	PrevEventHash   []byte
	EventHash       []byte
	CreatedAt       time.Time
}

type DeviceKeyBundleRecord struct {
	DeviceID          string
	AccountID         string
	RegistrationID    int
	IdentityPublicKey []byte
	SignedPreKey      SignedPreKeyRecord
	PqPreKey          PqPreKeyRecord
	OneTimePreKey     *OneTimePreKeyRecord
}
