// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import "glagolitsa/server/internal/model"

// Call event names (WebSocket). Payloads are id-only — no names, tokens, keys.
const (
	EventCallCreated           = "call.created"
	EventCallRinging           = "call.ringing"
	EventCallAccepted          = "call.accepted"
	EventCallConnected         = "call.connected"
	EventCallRejected          = "call.rejected"
	EventCallEnded             = "call.ended"
	EventCallParticipantJoined = "call.participant_joined"
	EventCallParticipantLeft   = "call.participant_left"
	EventCallLowBandwidth      = "call.low_bandwidth"
	EventCallRouteDegraded     = "call.route_degraded"
)

// EventPublisher fans out call control events to user sockets.
type EventPublisher interface {
	BroadcastToUsers(memberIDs []string, event model.WSEvent)
}

// CallEventData is privacy-safe call control payload.
type CallEventData struct {
	CallID     string `json:"call_id"`
	Status     string `json:"status,omitempty"`
	CallType   string `json:"call_type,omitempty"`
	CallScope  string `json:"call_scope,omitempty"`
	UserID     string `json:"user_id,omitempty"` // participant user id only when needed for UI fetch
	ChatID     string `json:"chat_id,omitempty"`
	RouteClass string `json:"route_class,omitempty"`
}

func (h *Handler) publishCallEvent(event string, call model.CallSession, extraUser string) {
	if h.Events == nil {
		return
	}
	recipients := h.callParticipantUserIDs(call)
	if extraUser != "" {
		found := false
		for _, id := range recipients {
			if id == extraUser {
				found = true
				break
			}
		}
		if !found {
			recipients = append(recipients, extraUser)
		}
	}
	if len(recipients) == 0 {
		return
	}
	h.Events.BroadcastToUsers(recipients, model.WSEvent{
		Event: event,
		Data: CallEventData{
			CallID:     call.ID,
			Status:     call.Status,
			CallType:   call.CallType,
			CallScope:  call.CallScope,
			ChatID:     call.ChatID,
			RouteClass: call.RouteClass,
			UserID:     extraUser,
		},
	})
}
