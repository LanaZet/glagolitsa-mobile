// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import "glagolitsa/server/internal/model"

// KeyOfferEligible reports whether a participant may receive a media key offer.
// Invited-but-not-joined must not get usable media keys (Signal-like rekey rule).
func KeyOfferEligible(p model.CallParticipant) bool {
	switch p.InviteState {
	case model.CallInviteStateJoined, model.CallInviteStateAccepted:
		return p.LeftAt == nil
	default:
		return false
	}
}

// FilterKeyOfferTargets drops offers whose targets are not eligible devices.
func FilterKeyOfferTargets(
	offers []model.CallKeyOfferInput,
	participants []model.CallParticipant,
) []model.CallKeyOfferInput {
	eligible := map[string]struct{}{}
	for _, p := range participants {
		if !KeyOfferEligible(p) {
			continue
		}
		// Device-scoped: empty device means any device of that user is ok for
		// legacy rows; prefer exact device match when set.
		if p.DeviceID == "" {
			eligible[p.UserID+"#"] = struct{}{}
		}
		eligible[p.UserID+"#"+p.DeviceID] = struct{}{}
	}
	out := make([]model.CallKeyOfferInput, 0, len(offers))
	for _, o := range offers {
		uid := o.TargetUserID
		did := o.TargetDeviceID
		if _, ok := eligible[uid+"#"+did]; ok {
			out = append(out, o)
			continue
		}
		if _, ok := eligible[uid+"#"]; ok {
			out = append(out, o)
		}
	}
	return out
}
