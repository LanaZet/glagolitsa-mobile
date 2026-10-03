// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type socketAccessGate struct {
	store MonolithStore
}

func (g *socketAccessGate) AllowSocket(claims auth.Claims) error {
	if claims.SessionID != "" {
		if _, err := g.store.FindSessionByID(claims.SessionID); err != nil {
			return err
		}
	}
	account, err := g.store.GetAccountByID(claims.UserID)
	if err != nil {
		return err
	}
	if account.AccountStatus == model.AccountStatusInactive {
		return store.ErrForbidden
	}
	count, err := g.store.CountActiveDevices(claims.UserID)
	if err != nil || count == 0 {
		return store.ErrForbidden
	}
	return nil
}