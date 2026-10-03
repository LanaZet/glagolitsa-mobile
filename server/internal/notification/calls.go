// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "log"

// IncomingCallPayload — push без текста разговора, только ids.
type IncomingCallPayload struct {
	CallID      string `json:"call_id"`
	CallerID    string `json:"caller_id"`
	CallType    string `json:"call_type"`
	LivekitRoom string `json:"livekit_room_id"`
}

// IncomingCallNotifier — заготовка Notification Service.
type IncomingCallNotifier interface {
	NotifyIncomingCall(calleeID string, payload IncomingCallPayload) error
}

type NoopIncomingCallNotifier struct{}

func (NoopIncomingCallNotifier) NotifyIncomingCall(calleeID string, payload IncomingCallPayload) error {
	log.Printf("notification: incoming call to %s call_id=%s (noop)", calleeID, payload.CallID)
	return nil
}