// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

// EnvelopeNotifier — silent push for offline recipient devices (Signal push-to-sync).
// excludeDeviceIDs are currently WS-connected devices and must not be woken.
type EnvelopeNotifier interface {
	NotifyNewEnvelope(recipientUserID string, excludeDeviceIDs []string) error
}

type NoopEnvelopeNotifier struct{}

func (NoopEnvelopeNotifier) NotifyNewEnvelope(recipientUserID string, excludeDeviceIDs []string) error {
	return nil
}
