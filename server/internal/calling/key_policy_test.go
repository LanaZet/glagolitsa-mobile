// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"testing"
	"time"

	"glagolitsa/server/internal/model"
)

func TestKeyOfferEligible_joinedOnly(t *testing.T) {
	joined := model.CallParticipant{InviteState: model.CallInviteStateJoined}
	if !KeyOfferEligible(joined) {
		t.Fatal("joined")
	}
	ringing := model.CallParticipant{InviteState: model.CallInviteStateRinging}
	if KeyOfferEligible(ringing) {
		t.Fatal("ringing must not get keys")
	}
	ts := storeNow()
	left := model.CallParticipant{InviteState: model.CallInviteStateJoined, LeftAt: &ts}
	if KeyOfferEligible(left) {
		t.Fatal("left participant must not get keys")
	}
}

func storeNow() (t time.Time) {
	return time.Now().UTC()
}

func TestFilterKeyOfferTargets(t *testing.T) {
	parts := []model.CallParticipant{
		{UserID: "a", DeviceID: "d1", InviteState: model.CallInviteStateJoined},
		{UserID: "b", DeviceID: "d2", InviteState: model.CallInviteStateRinging},
	}
	offers := []model.CallKeyOfferInput{
		{TargetUserID: "a", TargetDeviceID: "d1", EncryptedKey: "x"},
		{TargetUserID: "b", TargetDeviceID: "d2", EncryptedKey: "y"},
	}
	got := FilterKeyOfferTargets(offers, parts)
	if len(got) != 1 || got[0].TargetUserID != "a" {
		t.Fatalf("got=%+v", got)
	}
}
