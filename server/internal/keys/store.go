// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

import (
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// MailboxLookup — минимальный контракт для Messaging (mailbox → account).
type MailboxLookup interface {
	MailboxOwnerAccountID(mailboxToken string) (string, error)
	ResolveMailboxTokens(deviceID, accountID string) ([]string, error)
}

// Store — контракт Key Service (public keys, prekeys, mailbox).
type Store interface {
	MailboxLookup

	RegisterDevice(registration store.DeviceRegistration) (storedPrekeyCount int, err error)
	ReplenishPrekeys(accountID, deviceID string, prekeys []store.OneTimePreKeyRecord) (storedCount int, err error)
	GetDeviceBundle(deviceID string) (store.DeviceKeyBundleRecord, error)
	ListUserDevices(accountID string, activeOnly bool) ([]model.UserDevice, error)
	GetDeviceMailboxToken(deviceID, accountID string) (string, error)
	RotateDeviceMailbox(deviceID, accountID string) (model.RotateMailboxResponse, error)
	RotateSignedPreKey(accountID, deviceID string, rotation store.SignedPreKeyRotation) error
	ListKeyChangeEvents(accountID string, limit int) ([]store.KeyChangeEventRecord, error)
	GetSafetyNumberMaterial(deviceID string) (model.SafetyNumberResponse, error)
	CountRemainingPrekeys(accountID, deviceID string) (int, error)
	DeviceOwnerAccountID(deviceID string) (string, error)
	PurgeDeviceKeys(deviceID, accountID string) error
	RecordAudit(event model.AuditEventInput) error
}
