// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import "time"

// EphemeralStore — Redis / memory: только TTL-состояния.
type EphemeralStore interface {
	SetUserOnline(userID, deviceID string, ttl time.Duration) (wasOffline bool, err error)
	TouchDevice(userID, deviceID string, ttl time.Duration) error
	RemoveDevice(userID, deviceID string) (devicesLeft int, err error)
	GetUserStatus(userID string) (status string, err error)
	ListActiveDevices(userID string) ([]string, error)

	SetTyping(chatID, userID string, ttl time.Duration) error
	ClearTyping(chatID, userID string) error
	ListTyping(chatID string) ([]string, error)

	SetRecording(chatID, userID, kind string, ttl time.Duration) error
	ClearRecording(chatID, userID string) error
	ListRecording(chatID string) (map[string]string, error)

	SetInCall(userID, callID string, ttl time.Duration) error
	ClearInCall(userID string) error
	GetInCall(userID string) (callID string, active bool, err error)

	Close() error
}