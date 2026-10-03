// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"log/slog"

	"github.com/go-webauthn/webauthn/webauthn"

	"glagolitsa/server/internal/profile"
)

type Handler struct {
	Store         Store
	Profiles      profile.Store
	Passkeys      *webauthn.WebAuthn
	PasskeyOrigin string
}

func NewHandler(store Store, profiles profile.Store) *Handler {
	passkeys, origin, err := newWebAuthn()
	if err != nil {
		slog.Error("webauthn init failed", "error", err.Error())
	}
	return &Handler{
		Store:         store,
		Profiles:      profiles,
		Passkeys:      passkeys,
		PasskeyOrigin: origin,
	}
}
