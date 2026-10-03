// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

// ResolveConflict applies last-write-wins using monotonic version numbers.
func ResolveConflict(incomingVersion, existingVersion int) (accept bool, reason string) {
	if incomingVersion <= 0 {
		return false, "version must be positive"
	}
	if incomingVersion <= existingVersion {
		return false, "stale version"
	}
	return true, ""
}