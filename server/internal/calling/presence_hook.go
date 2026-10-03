// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"strings"

	"glagolitsa/server/internal/model"
)

// PresenceHook — in_call состояние (Presence Service).
type PresenceHook interface {
	SetInCall(userID, callID string, participantIDs []string) error
	ClearInCall(userID string, participantIDs []string) error
}

func (h *Handler) applyCallPresence(call model.CallSession, active bool) {
	if h.Presence == nil {
		return
	}
	participants := h.callParticipantUserIDs(call)
	for _, userID := range participants {
		if active {
			_ = h.Presence.SetInCall(userID, call.ID, participants)
		} else {
			_ = h.Presence.ClearInCall(userID, participants)
		}
	}
}

func (h *Handler) callParticipantUserIDs(call model.CallSession) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, 4)
	add := func(id string) {
		id = strings.TrimSpace(id)
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
	if rows, err := h.Store.ListCallParticipants(call.ID); err == nil {
		for _, p := range rows {
			add(p.UserID)
		}
	}
	return out
}