// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// Store — Calling Service metadata only (no media/plaintext keys).
type Store interface {
	CreateCall(call model.CallSession) (model.CallSession, error)
	CreateCallIfAvailable(call model.CallSession, participantUserIDs []string) (model.CallSession, error)
	GetCall(callID string) (model.CallSession, error)
	ListCallsForUser(userID string, limit int) ([]model.CallSession, error)
	FindLiveCallForChat(chatID string) (model.CallSession, error)
	PeekChat(chatID string) (model.Chat, error)
	UpdateCallStatus(callID, status string, at time.Time) (model.CallSession, error)
	MarkCallConnected(callID string, at time.Time) (model.CallSession, error)
	EndCall(callID string, at time.Time) (model.CallSession, error)
	SetLowBandwidthMode(callID string, enabled bool) error

	UpsertParticipant(callID, userID, deviceID string, joinedAt *time.Time, leftAt *time.Time) error
	UpsertCallParticipant(p model.CallParticipant) error
	ListCallParticipants(callID string) ([]model.CallParticipant, error)
	IsCallParticipant(callID, userID string) (bool, error)
	IsGroupMember(chatID, userID string) (bool, error)
	CreateCallInvite(inv model.CallInvite) (model.CallInvite, error)
	ListStaleCalls(ringingBefore, connectingBefore, activeBefore time.Time, limit int) ([]model.CallSession, error)

	StoreCallKeyOffers(callID string, offers []store.CallKeyOfferRecord) error
	ListCallKeyOffersForDevice(callID, userID, deviceID string) ([]model.CallKeyOffer, error)
}
