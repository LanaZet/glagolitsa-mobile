// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"time"

	"glagolitsa/server/internal/model"
)

// Stale device policy:
//   - pending never confirmed → revoke after 24h
//   - extra active with no live poll → revoke after 7 days
//   - the last remaining active device is never revoked by this job
//
// Offline recipients must stay reachable: senders encrypt to the last known
// active device and envelopes wait in that mailbox until the phone returns.
// Purging that last device made peer ListUserDevices return [] and blocked
// delivery (Tanya → mockosh). Extra reinstall ghosts are still purged so
// fan-out does not target dead mailboxes forever.
const (
	stalePendingTTL = 24 * time.Hour
	staleActiveTTL  = 7 * 24 * time.Hour
)

type stalePurgeCandidate struct {
	DeviceID       string
	AccountID      string
	Status         string
	SeenAt         time.Time
	HasIdentityKey bool
}

func filterStalePurgeTargets(candidates []stalePurgeCandidate, now time.Time) []stalePurgeCandidate {
	pendingCutoff := now.Add(-stalePendingTTL)
	activeCutoff := now.Add(-staleActiveTTL)

	type acctActives struct {
		fresh []stalePurgeCandidate
		stale []stalePurgeCandidate
	}
	byAccount := map[string]*acctActives{}
	out := make([]stalePurgeCandidate, 0)

	for _, c := range candidates {
		status := c.Status
		if status == "" {
			status = model.DeviceStatusActive
		}
		switch status {
		case model.DeviceStatusPending:
			if c.SeenAt.Before(pendingCutoff) {
				out = append(out, c)
			}
		case model.DeviceStatusActive:
			bucket := byAccount[c.AccountID]
			if bucket == nil {
				bucket = &acctActives{}
				byAccount[c.AccountID] = bucket
			}
			if c.SeenAt.Before(activeCutoff) {
				bucket.stale = append(bucket.stale, c)
			} else {
				bucket.fresh = append(bucket.fresh, c)
			}
		}
	}

	for _, bucket := range byAccount {
		stale := bucket.stale
		if len(bucket.fresh) == 0 && len(stale) > 0 {
			_, stale = splitKeptLastActive(stale)
		}
		out = append(out, stale...)
	}
	return out
}

func splitKeptLastActive(stale []stalePurgeCandidate) (keep stalePurgeCandidate, rest []stalePurgeCandidate) {
	keepIdx := 0
	for i := 1; i < len(stale); i++ {
		if betterLastActive(stale[i], stale[keepIdx]) {
			keepIdx = i
		}
	}
	keep = stale[keepIdx]
	rest = make([]stalePurgeCandidate, 0, len(stale)-1)
	for i, c := range stale {
		if i == keepIdx {
			continue
		}
		rest = append(rest, c)
	}
	return keep, rest
}

func betterLastActive(a, b stalePurgeCandidate) bool {
	if a.HasIdentityKey != b.HasIdentityKey {
		return a.HasIdentityKey
	}
	if !a.SeenAt.Equal(b.SeenAt) {
		return a.SeenAt.After(b.SeenAt)
	}
	return a.DeviceID > b.DeviceID
}
