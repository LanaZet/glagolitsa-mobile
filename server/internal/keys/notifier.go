// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

// PrekeyNotifier — push владельцу устройства при низком запасе one-time prekeys.
type PrekeyNotifier interface {
	NotifyPrekeysLow(accountID, deviceID string, remaining int) error
}

type NoopPrekeyNotifier struct{}

func (NoopPrekeyNotifier) NotifyPrekeysLow(accountID, deviceID string, remaining int) error {
	return nil
}