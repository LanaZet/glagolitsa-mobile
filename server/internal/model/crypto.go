// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

type SignedPreKeyMaterial struct {
	ID        int    `json:"id"`
	PublicKey string `json:"public_key"`
	Signature string `json:"signature"`
	CreatedAt int64  `json:"created_at"`
}

type PqPreKeyMaterial struct {
	ID             int    `json:"id"`
	PublicMaterial string `json:"public_material"`
	Signature      string `json:"signature"`
	CreatedAt      int64  `json:"created_at"`
}

type OneTimePreKeyMaterial struct {
	ID        int    `json:"id"`
	PublicKey string `json:"public_key"`
}

type RegisterDeviceRequest struct {
	DeviceID          string                  `json:"device_id"`
	RegistrationID    int                     `json:"registration_id"`
	IdentityPublicKey string                  `json:"identity_public_key"`
	SignedPreKey      SignedPreKeyMaterial    `json:"signed_prekey"`
	PqPreKey          PqPreKeyMaterial        `json:"pq_prekey"`
	OneTimePrekeys    []OneTimePreKeyMaterial `json:"one_time_prekeys"`
	Attestation       *DeviceAttestation      `json:"attestation,omitempty"`
}

const (
	KeyEventRegistered      = "device_registered"
	KeyEventIdentityUpdated = "identity_updated"
	KeyEventSignedPrekeyRot = "signed_prekey_rotated"
	KeyEventDeviceRevoked   = "device_revoked"
)

type ReplenishPrekeysRequest struct {
	OneTimePrekeys []OneTimePreKeyMaterial `json:"one_time_prekeys"`
}

type DeviceKeyBundle struct {
	DeviceID          string                 `json:"device_id"`
	AccountID         string                 `json:"account_id"`
	RegistrationID    int                    `json:"registration_id"`
	IdentityPublicKey string                 `json:"identity_public_key"`
	SignedPreKey      SignedPreKeyMaterial   `json:"signed_prekey"`
	PqPreKey          PqPreKeyMaterial       `json:"pq_prekey"`
	OneTimePreKey     *OneTimePreKeyMaterial `json:"one_time_prekey,omitempty"`
}

type UserDevice struct {
	DeviceID          string `json:"device_id"`
	MailboxToken      string `json:"mailbox_token"`
	DeliveryToken     string `json:"delivery_token,omitempty"`
	RegistrationID    int    `json:"registration_id"`
	IdentityPublicKey string `json:"identity_public_key"`
	DeviceStatus      string `json:"device_status,omitempty"`
}

type DeviceAttestation struct {
	ConfirmingDeviceID string `json:"confirming_device_id"`
	Signature          string `json:"signature,omitempty"`
	Provider           string `json:"provider,omitempty"`
	Token              string `json:"token,omitempty"`
}

type RotateSignedPreKeyRequest struct {
	SignedPreKey SignedPreKeyMaterial `json:"signed_prekey"`
	PqPreKey     PqPreKeyMaterial     `json:"pq_prekey"`
}

type SafetyNumberResponse struct {
	DeviceID          string `json:"device_id"`
	AccountID         string `json:"account_id"`
	RegistrationID    int    `json:"registration_id"`
	IdentityPublicKey string `json:"identity_public_key"`
}

type KeyChangeEvent struct {
	ID              string `json:"id"`
	DeviceID        string `json:"device_id"`
	EventType       string `json:"event_type"`
	IdentityKeyHash string `json:"identity_key_hash"`
	SignedPrekeyID  int    `json:"signed_prekey_id,omitempty"`
	PrevEventHash   string `json:"prev_event_hash,omitempty"`
	EventHash       string `json:"event_hash"`
	CreatedAt       string `json:"created_at"`
}

type PrekeyCountResponse struct {
	DeviceID         string `json:"device_id"`
	RemainingPrekeys int    `json:"remaining_prekeys"`
}

type RegisterDeviceResponse struct {
	DeviceID         string `json:"device_id"`
	PrekeysStored    int    `json:"prekeys_stored"`
	OneTimePrekeyIDs []int  `json:"one_time_prekey_ids"`
}

type ReplenishPrekeysResponse struct {
	DeviceID      string `json:"device_id"`
	PrekeysStored int    `json:"prekeys_stored"`
}
