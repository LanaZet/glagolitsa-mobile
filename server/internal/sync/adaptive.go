// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import "glagolitsa/server/internal/store"

// RecommendProfile suggests client sync aggressiveness from device hints.
func RecommendProfile(req RegisterDeviceRequest, current string) string {
	if req.PowerSaver != nil && *req.PowerSaver {
		return store.SyncProfileMinimal
	}
	if req.BatteryPct != nil && *req.BatteryPct <= 15 {
		return store.SyncProfileSaver
	}
	if req.NetworkMbps != nil {
		if *req.NetworkMbps < 0.5 {
			return store.SyncProfileMinimal
		}
		if *req.NetworkMbps < 2 {
			return store.SyncProfileSaver
		}
	}
	if current != "" {
		return current
	}
	return store.SyncProfileBalanced
}

func AllowedOperations(profile string) map[string]bool {
	switch profile {
	case store.SyncProfileMinimal:
		return map[string]bool{
			"message.created": true,
			"message.deleted": true,
		}
	case store.SyncProfileSaver:
		return map[string]bool{
			"message.created": true, "message.edited": true, "message.deleted": true,
			"message.read": true, "group.member_joined": true, "group.member_removed": true,
		}
	default:
		return nil // all operations
	}
}

func OperationAllowed(profile, operation string) bool {
	allowed := AllowedOperations(profile)
	if allowed == nil {
		return true
	}
	return allowed[operation]
}