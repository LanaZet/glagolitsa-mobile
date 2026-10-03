// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"context"
	"log/slog"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// Default timeouts for call state machine sweep.
// Goal: live statuses (ringing/connecting/active) cannot pin accounts forever.
const (
	// Unanswered outbound/inbound ring → missed.
	RingingTimeout = 45 * time.Second
	// Accepted but media never connected → ended.
	ConnectingTimeout = 90 * time.Second
	// Active with zero joined participants (ghost room) → ended.
	ActiveEmptyTimeout = 2 * time.Minute
	// Hard cap for any active call even with joined participants (client crash /
	// lost endCall / missing LiveKit webhook). Prevents permanent live-guard lock.
	ActiveMaxDuration = 4 * time.Hour
)

// TimeoutStore lists and closes stale calls.
type TimeoutStore interface {
	ListStaleCalls(ringingBefore, connectingBefore, activeBefore time.Time, limit int) ([]model.CallSession, error)
	UpdateCallStatus(callID, status string, at time.Time) (model.CallSession, error)
	EndCall(callID string, at time.Time) (model.CallSession, error)
	ListCallParticipants(callID string) ([]model.CallParticipant, error)
}

// MissedCallNotifier wakes callees after ring timeout (opaque call_id only).
type MissedCallNotifier interface {
	NotifyMissedCall(calleeID, callID string) error
}

// SweepStaleCalls transitions ringing→missed, stale connecting→ended,
// active empty / over-max → ended. Safe to run frequently from jobs.
func SweepStaleCalls(ctx context.Context, s TimeoutStore, events EventPublisher, presence PresenceHook) (int, error) {
	return SweepStaleCallsWithNotify(ctx, s, events, presence, nil)
}

// SweepStaleCallsWithNotify is SweepStaleCalls plus optional missed-call push.
func SweepStaleCallsWithNotify(ctx context.Context, s TimeoutStore, events EventPublisher, presence PresenceHook, missed MissedCallNotifier) (int, error) {
	_ = ctx
	now := store.NowUTC()
	// Include active candidates early enough for empty-room cleanup; hard max
	// is enforced inside the active branch even when participants still look joined.
	stale, err := s.ListStaleCalls(
		now.Add(-RingingTimeout),
		now.Add(-ConnectingTimeout),
		now.Add(-ActiveEmptyTimeout),
		100,
	)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, call := range stale {
		wasRinging := call.Status == model.CallStatusRinging
		var updated model.CallSession
		var event string
		switch call.Status {
		case model.CallStatusRinging:
			updated, err = s.UpdateCallStatus(call.ID, model.CallStatusMissed, now)
			event = EventCallEnded
		case model.CallStatusConnecting:
			updated, err = s.EndCall(call.ID, now)
			event = EventCallEnded
		case model.CallStatusActive:
			if !shouldEndActiveCall(call, s, now) {
				continue
			}
			updated, err = s.EndCall(call.ID, now)
			event = EventCallEnded
		default:
			continue
		}
		if err != nil {
			slog.Debug("call timeout sweep skip", "call_id", call.ID, "err", err.Error())
			continue
		}
		n++
		participantIDs := staleCallParticipantUserIDs(call, s)
		if presence != nil {
			for _, userID := range participantIDs {
				_ = presence.ClearInCall(userID, participantIDs)
			}
		}
		if events != nil {
			// Recipients from pre-update snapshot; ids only.
			events.BroadcastToUsers(participantIDs, model.WSEvent{
				Event: event,
				Data: CallEventData{
					CallID: updated.ID,
					Status: updated.Status,
				},
			})
		}
		if wasRinging && missed != nil {
			// Notify invitees (not starter) about missed ring.
			starter := call.StartedByUserID
			if starter == "" {
				starter = call.CallerID
			}
			for _, userID := range participantIDs {
				if userID == "" || userID == starter {
					continue
				}
				_ = missed.NotifyMissedCall(userID, call.ID)
			}
		}
	}
	return n, nil
}

func shouldEndActiveCall(call model.CallSession, s TimeoutStore, now time.Time) bool {
	ageBase := call.CreatedAt
	if call.ConnectedAt != nil {
		ageBase = *call.ConnectedAt
	} else if call.AcceptedAt != nil {
		ageBase = *call.AcceptedAt
	}
	age := now.Sub(ageBase)
	if age >= ActiveMaxDuration {
		return true
	}
	if age < ActiveEmptyTimeout {
		return false
	}
	// Empty room: no remaining joined/accepted participant.
	parts, listErr := s.ListCallParticipants(call.ID)
	if listErr != nil {
		// Fail closed for empty-room path only after max age would already force end.
		return false
	}
	for _, p := range parts {
		if p.LeftAt != nil {
			continue
		}
		if p.InviteState == model.CallInviteStateJoined ||
			p.InviteState == model.CallInviteStateAccepted {
			return false
		}
	}
	return true
}

func staleCallParticipantUserIDs(call model.CallSession, s TimeoutStore) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, 4)
	add := func(id string) {
		if id == "" {
			return
		}
		if _, ok := seen[id]; ok {
			return
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	add(call.CallerID)
	add(call.CalleeID)
	add(call.StartedByUserID)
	parts, err := s.ListCallParticipants(call.ID)
	if err != nil {
		return out
	}
	for _, p := range parts {
		add(p.UserID)
	}
	return out
}
