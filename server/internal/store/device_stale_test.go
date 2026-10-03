// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"
	"time"

	"glagolitsa/server/internal/model"
)

func TestFilterStalePurgeTargets_keepsLastActiveAfterWeek(t *testing.T) {
	now := time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC)
	weekAgo := now.Add(-8 * 24 * time.Hour)
	candidates := []stalePurgeCandidate{{
		DeviceID:       "only",
		AccountID:      "acct",
		Status:         model.DeviceStatusActive,
		SeenAt:         weekAgo,
		HasIdentityKey: true,
	}}
	got := filterStalePurgeTargets(candidates, now)
	if len(got) != 0 {
		t.Fatalf("last active must stay reachable, purged=%v", got)
	}
}

func TestFilterStalePurgeTargets_purgesExtraStaleActives(t *testing.T) {
	now := time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC)
	older := now.Add(-10 * 24 * time.Hour)
	newer := now.Add(-8 * 24 * time.Hour)
	candidates := []stalePurgeCandidate{
		{DeviceID: "old", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: older, HasIdentityKey: true},
		{DeviceID: "new", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: newer, HasIdentityKey: true},
	}
	got := filterStalePurgeTargets(candidates, now)
	if len(got) != 1 || got[0].DeviceID != "old" {
		t.Fatalf("want only older extra purged, got=%v", got)
	}
}

func TestFilterStalePurgeTargets_freshActiveAllowsPurgingStaleTwin(t *testing.T) {
	now := time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC)
	candidates := []stalePurgeCandidate{
		{DeviceID: "live", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: now.Add(-time.Hour), HasIdentityKey: true},
		{DeviceID: "zombie", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: now.Add(-8 * 24 * time.Hour), HasIdentityKey: true},
	}
	got := filterStalePurgeTargets(candidates, now)
	if len(got) != 1 || got[0].DeviceID != "zombie" {
		t.Fatalf("want zombie purged while live remains, got=%v", got)
	}
}

func TestFilterStalePurgeTargets_pendingStill24h(t *testing.T) {
	now := time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC)
	candidates := []stalePurgeCandidate{
		{DeviceID: "pending-old", AccountID: "acct", Status: model.DeviceStatusPending, SeenAt: now.Add(-25 * time.Hour), HasIdentityKey: true},
		{DeviceID: "pending-new", AccountID: "acct", Status: model.DeviceStatusPending, SeenAt: now.Add(-23 * time.Hour), HasIdentityKey: true},
	}
	got := filterStalePurgeTargets(candidates, now)
	if len(got) != 1 || got[0].DeviceID != "pending-old" {
		t.Fatalf("pending TTL is 24h, got=%v", got)
	}
}

func TestFilterStalePurgeTargets_prefersIdentityKeyWhenKeepingLast(t *testing.T) {
	now := time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC)
	stale := now.Add(-8 * 24 * time.Hour)
	candidates := []stalePurgeCandidate{
		{DeviceID: "no-key", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: stale.Add(time.Hour), HasIdentityKey: false},
		{DeviceID: "with-key", AccountID: "acct", Status: model.DeviceStatusActive, SeenAt: stale, HasIdentityKey: true},
	}
	got := filterStalePurgeTargets(candidates, now)
	if len(got) != 1 || got[0].DeviceID != "no-key" {
		t.Fatalf("keep the sendable last device, got=%v", got)
	}
}
