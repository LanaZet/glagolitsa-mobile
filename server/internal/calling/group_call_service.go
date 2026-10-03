// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"errors"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

const maxGroupCallParticipants = 8

var (
	errCallEnded             = errors.New("call already ended")
	errCallNotJoinable       = errors.New("join is only for group chat calls")
	errChatNotGroup          = errors.New("group calls are only available in group chats")
	errGroupParticipantLimit = errors.New("group call participant limit reached")
)

type GroupCallService struct {
	Store Store
}

func NewGroupCallService(store Store) GroupCallService {
	return GroupCallService{Store: store}
}

func (s GroupCallService) AuthorizeStart(chatID, userID string) error {
	if err := s.RequireChatMember(chatID, userID); err != nil {
		return err
	}
	chat, err := s.Store.PeekChat(chatID)
	if err != nil {
		return err
	}
	if chat.Type != model.ChatTypeGroup {
		return errChatNotGroup
	}
	return nil
}

func (s GroupCallService) ActiveCallForChat(chatID, userID string) (model.CallSession, error) {
	if err := s.RequireChatMember(chatID, userID); err != nil {
		return model.CallSession{}, err
	}
	return s.Store.FindLiveCallForChat(chatID)
}

func (s GroupCallService) Join(callID, userID, deviceID string, now time.Time) (model.CallSession, error) {
	call, err := s.Store.GetCall(callID)
	if err != nil {
		return model.CallSession{}, err
	}
	if !isGroupChatCall(call) {
		return model.CallSession{}, errCallNotJoinable
	}
	if isTerminalCallStatus(call.Status) {
		return model.CallSession{}, errCallEnded
	}
	if err := s.RequireChatMember(call.ChatID, userID); err != nil {
		return model.CallSession{}, err
	}
	if err := s.EnsureCapacity(call.ID, userID); err != nil {
		return model.CallSession{}, err
	}

	updated := call
	if call.Status == model.CallStatusRinging && userID != call.CallerID && userID != call.StartedByUserID {
		updated, err = s.Store.UpdateCallStatus(call.ID, model.CallStatusConnecting, now)
		if err != nil {
			return model.CallSession{}, err
		}
	}
	if err := s.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      call.ID,
		UserID:      userID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleJoined,
		InviteState: model.CallInviteStateJoined,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    &now,
	}); err != nil {
		return model.CallSession{}, err
	}
	return updated, nil
}

func (s GroupCallService) RequireChatMember(chatID, userID string) error {
	member, err := s.Store.IsGroupMember(chatID, userID)
	if err != nil {
		return err
	}
	if !member {
		return store.ErrForbidden
	}
	return nil
}

func (s GroupCallService) EnsureCapacity(callID, joiningUserID string) error {
	parts, err := s.Store.ListCallParticipants(callID)
	if err != nil {
		return err
	}
	active := 0
	alreadyActive := false
	for _, p := range parts {
		if p.LeftAt != nil {
			continue
		}
		active++
		if p.UserID == joiningUserID {
			alreadyActive = true
		}
	}
	if !alreadyActive && active >= maxGroupCallParticipants {
		return errGroupParticipantLimit
	}
	return nil
}

func isGroupChatCall(call model.CallSession) bool {
	return call.ChatID != "" && (call.CallScope == model.CallScopeGroup || call.CallScope == model.CallScopeLink)
}

func isLiveCallStatus(status string) bool {
	return status == model.CallStatusRinging ||
		status == model.CallStatusConnecting ||
		status == model.CallStatusActive
}

func isTerminalCallStatus(status string) bool {
	return status == model.CallStatusEnded ||
		status == model.CallStatusRejected ||
		status == model.CallStatusMissed
}
