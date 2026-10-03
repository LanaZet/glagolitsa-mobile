// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"errors"
	"testing"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestCheckRelayLimits_rejectsTooManyEnvelopesPerRequest(t *testing.T) {
	t.Setenv("RELAY_MAX_ENVELOPES_PER_REQUEST", "50")
	h := &Handler{Store: store.NewMemory()}
	err := h.checkRelayLimits("user-1", model.TrustTierNew, 51)
	if err == nil || err.Error() != "too many envelopes in one request" {
		t.Fatalf("err = %v", err)
	}
}

func TestCheckRelayLimits_newTierQuota(t *testing.T) {
	t.Setenv("RELAY_LIMIT_NEW_TIER", "200")
	mem := store.NewMemory()
	h := &Handler{Store: mem}
	if err := mem.IncrementRelayEnvelopeCount("user-1", 199); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := h.checkRelayLimits("user-1", model.TrustTierNew, 1); err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if err := mem.IncrementRelayEnvelopeCount("user-1", 1); err != nil {
		t.Fatalf("seed: %v", err)
	}
	err := h.checkRelayLimits("user-1", model.TrustTierNew, 1)
	if !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("err = %v, want forbidden", err)
	}
}

func TestCheckRelayLimits_agedAccountGetsAdaptiveQuota(t *testing.T) {
	t.Setenv("RELAY_LIMIT_NEW_TIER", "200")
	t.Setenv("RELAY_LIMIT_AGED_NEW_TIER", "500")
	mem := store.NewMemory()
	account, err := mem.CreateAccount(model.User{
		Username:  "aged-user",
		CreatedAt: store.NowUTC().Add(-25 * time.Hour),
	}, "hash")
	if err != nil {
		t.Fatalf("create account: %v", err)
	}
	h := &Handler{Store: mem}
	if err := mem.IncrementRelayEnvelopeCount(account.ID, 499); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := h.checkRelayLimits(account.ID, model.TrustTierNew, 1); err != nil {
		t.Fatalf("aged new account should allow 500th envelope: %v", err)
	}
	if err := mem.IncrementRelayEnvelopeCount(account.ID, 1); err != nil {
		t.Fatalf("seed: %v", err)
	}
	err = h.checkRelayLimits(account.ID, model.TrustTierNew, 1)
	if !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("err = %v, want forbidden", err)
	}
}

func TestCheckRelayLimits_trustedTierHigherQuota(t *testing.T) {
	t.Setenv("RELAY_LIMIT_TRUSTED_TIER", "5000")
	mem := store.NewMemory()
	h := &Handler{Store: mem}
	if err := mem.IncrementRelayEnvelopeCount("user-1", 4999); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := h.checkRelayLimits("user-1", model.TrustTierTrusted, 1); err != nil {
		t.Fatalf("trusted tier should allow 5000th envelope: %v", err)
	}
	if err := mem.IncrementRelayEnvelopeCount("user-1", 1); err != nil {
		t.Fatalf("seed: %v", err)
	}
	err := h.checkRelayLimits("user-1", model.TrustTierTrusted, 1)
	if !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("err = %v, want forbidden", err)
	}
}
