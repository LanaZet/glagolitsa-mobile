// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"errors"
	"os"
	"strconv"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

const (
	defaultRelayLimitNewTier     = 2000
	defaultRelayLimitTrustedTier = 20000
	defaultMaxEnvelopesPerRelay  = 100
	defaultRelayLimitAgedNewTier = 5000
	defaultRelayLimitEstablished = 10000
)

func (h *Handler) checkRelayLimits(userID, trustTier string, envelopeCount int) error {
	if envelopeCount > relayMaxEnvelopesPerRequest() {
		return errors.New("too many envelopes in one request")
	}

	limit := h.effectiveRelayLimit(userID, trustTier)

	current, err := h.Store.RelayEnvelopeCount24h(userID)
	if err != nil {
		return err
	}
	if current+envelopeCount > limit {
		return store.ErrForbidden
	}
	return nil
}

func (h *Handler) effectiveRelayLimit(userID, trustTier string) int {
	baseNew := relayLimitNewTier()
	baseTrusted := relayLimitTrustedTier()
	limit := baseNew
	score := 0

	if trustTier == model.TrustTierTrusted {
		limit = baseTrusted
		score += 80
	}

	account, err := h.Store.GetAccountByID(userID)
	if err == nil {
		if account.TrustTier == model.TrustTierTrusted {
			limit = baseTrusted
			score += 80
		}
		if !account.CreatedAt.IsZero() {
			age := time.Since(account.CreatedAt)
			switch {
			case age >= 7*24*time.Hour:
				score += 25
			case age >= 24*time.Hour:
				score += 10
			}
		}
	}

	if activeDevices, err := h.Store.CountActiveDevices(userID); err == nil && activeDevices >= 2 {
		score += 10
	}

	switch {
	case score >= 80:
		limit = maxInt(limit, baseTrusted)
	case score >= 35:
		limit = maxInt(limit, relayLimitEstablishedTier())
	case score >= 10:
		limit = maxInt(limit, relayLimitAgedNewTier())
	}
	return limit
}

func relayMaxEnvelopesPerRequest() int {
	return envInt("RELAY_MAX_ENVELOPES_PER_REQUEST", defaultMaxEnvelopesPerRelay)
}

func relayLimitNewTier() int {
	return envInt("RELAY_LIMIT_NEW_TIER", defaultRelayLimitNewTier)
}

func relayLimitTrustedTier() int {
	return envInt("RELAY_LIMIT_TRUSTED_TIER", defaultRelayLimitTrustedTier)
}

func relayLimitAgedNewTier() int {
	return envInt("RELAY_LIMIT_AGED_NEW_TIER", defaultRelayLimitAgedNewTier)
}

func relayLimitEstablishedTier() int {
	return envInt("RELAY_LIMIT_ESTABLISHED_TIER", defaultRelayLimitEstablished)
}

func envInt(name string, fallback int) int {
	value := os.Getenv(name)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
