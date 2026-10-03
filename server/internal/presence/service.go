// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"strings"
	"time"

	"glagolitsa/server/internal/model"
)

// Service — эфемерный presence + privacy filter + fanout.
type Service struct {
	ephemeral EphemeralStore
	privacy   PrivacyStore
	graph     SocialGraph
	pub       FanoutPublisher
	cfg       Config
	limiter   *actionLimiter
}

func NewService(
	ephemeral EphemeralStore,
	privacy PrivacyStore,
	graph SocialGraph,
	pub FanoutPublisher,
	cfg Config,
) *Service {
	if cfg.MaxUsersPerQuery <= 0 {
		cfg.MaxUsersPerQuery = 50
	}
	return &Service{
		ephemeral: ephemeral,
		privacy:   privacy,
		graph:     graph,
		pub:       pub,
		cfg:       cfg,
		limiter:   newActionLimiter(),
	}
}

func (s *Service) Close() error {
	if s.ephemeral != nil {
		return s.ephemeral.Close()
	}
	return nil
}

func (s *Service) Heartbeat(userID, deviceID string) error {
	if !s.limiter.allow("hb:"+userID, s.cfg.HeartbeatMinInterval) {
		return nil
	}
	wasOffline, err := s.ephemeral.SetUserOnline(userID, deviceID, s.cfg.OnlineTTL)
	if err != nil {
		return err
	}
	if wasOffline {
		s.fanoutPresenceOnline(userID)
	}
	return nil
}

func (s *Service) OnSocketConnected(userID string) {
	_, _ = s.ephemeral.SetUserOnline(userID, "", s.cfg.OnlineTTL)
	s.fanoutPresenceOnline(userID)
}

func (s *Service) OnSocketDisconnected(userID string) {
	_, _ = s.ephemeral.RemoveDevice(userID, "")
	status, _ := s.ephemeral.GetUserStatus(userID)
	if status == model.PresenceOffline {
		_ = s.privacy.SetLastSeenAt(userID, time.Now().UTC())
		s.fanoutPresenceOffline(userID)
	}
}

func (s *Service) TypingStart(userID, chatID string) error {
	if !s.limiter.allow("typing:"+userID+":"+chatID, s.cfg.TypingMinInterval) {
		return nil
	}
	members, err := s.graph.GetChatMemberIDs(chatID, userID)
	if err != nil {
		return err
	}
	if err := s.ephemeral.SetTyping(chatID, userID, s.cfg.TypingTTL); err != nil {
		return err
	}
	recipients := filterOtherMembers(members, userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "typing.started",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
		},
	})
	// legacy
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "typing",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
		},
	})
	return nil
}

func (s *Service) TypingStop(userID, chatID string) error {
	members, err := s.graph.GetChatMemberIDs(chatID, userID)
	if err != nil {
		return err
	}
	_ = s.ephemeral.ClearTyping(chatID, userID)
	recipients := filterOtherMembers(members, userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "typing.stopped",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
		},
	})
	return nil
}

func (s *Service) RecordingStart(userID, chatID, kind string) error {
	if !s.limiter.allow("rec:"+userID+":"+chatID, s.cfg.RecordingMinInterval) {
		return nil
	}
	kind = strings.TrimSpace(kind)
	if kind != model.RecordingKindVideo {
		kind = model.RecordingKindVoice
	}
	members, err := s.graph.GetChatMemberIDs(chatID, userID)
	if err != nil {
		return err
	}
	if err := s.ephemeral.SetRecording(chatID, userID, kind, s.cfg.RecordingTTL); err != nil {
		return err
	}
	recipients := filterOtherMembers(members, userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "recording.started",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
			"kind":    kind,
		},
	})
	return nil
}

func (s *Service) RecordingStop(userID, chatID string) error {
	members, err := s.graph.GetChatMemberIDs(chatID, userID)
	if err != nil {
		return err
	}
	_ = s.ephemeral.ClearRecording(chatID, userID)
	recipients := filterOtherMembers(members, userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "recording.stopped",
		Data: map[string]string{
			"chat_id": chatID,
			"user_id": userID,
		},
	})
	return nil
}

func (s *Service) SetInCall(userID, callID string, participantIDs []string) error {
	if err := s.ephemeral.SetInCall(userID, callID, s.cfg.CallTTL); err != nil {
		return err
	}
	s.fanoutCallStarted(userID, callID, participantIDs)
	return nil
}

func (s *Service) ClearInCall(userID string, participantIDs []string) error {
	callID, wasInCall, _ := s.ephemeral.GetInCall(userID)
	_ = s.ephemeral.ClearInCall(userID)
	if wasInCall {
		s.fanoutCallEnded(userID, callID, participantIDs)
	}
	return nil
}

func (s *Service) ViewUser(viewerID, targetID string) (model.UserPresenceView, error) {
	view := model.UserPresenceView{UserID: targetID, Status: model.PresenceOffline}
	if !s.canViewOnline(viewerID, targetID) {
		view.Status = model.PresenceHidden
		return view, nil
	}

	status, err := s.ephemeral.GetUserStatus(targetID)
	if err != nil {
		return view, err
	}
	callID, inCall, _ := s.ephemeral.GetInCall(targetID)
	if inCall {
		view.InCall = true
		view.CallID = callID
		view.Status = model.PresenceInCall
	} else {
		view.Status = status
		if view.Status == "" {
			view.Status = model.PresenceOffline
		}
	}

	if viewerID == targetID {
		devices, _ := s.ephemeral.ListActiveDevices(targetID)
		view.ActiveDevices = devices
	}

	if view.Status == model.PresenceOffline || view.Status == model.PresenceHidden {
		if !s.canViewLastSeen(viewerID, targetID) {
			return view, nil
		}
		if at, err := s.privacy.GetLastSeenAt(targetID); err == nil && at != nil {
			view.LastSeenBucket = bucketLastSeen(*at, time.Now().UTC())
		} else {
			view.LastSeenBucket = model.LastSeenLongAgo
		}
	}
	return view, nil
}

func (s *Service) ViewUsers(viewerID string, targetIDs []string) ([]model.UserPresenceView, error) {
	if len(targetIDs) > s.cfg.MaxUsersPerQuery {
		targetIDs = targetIDs[:s.cfg.MaxUsersPerQuery]
	}
	out := make([]model.UserPresenceView, 0, len(targetIDs))
	for _, targetID := range targetIDs {
		targetID = strings.TrimSpace(targetID)
		if targetID == "" {
			continue
		}
		view, err := s.ViewUser(viewerID, targetID)
		if err != nil {
			return nil, err
		}
		out = append(out, view)
	}
	return out, nil
}

func (s *Service) ViewChat(viewerID, chatID string) (model.ChatPresenceView, error) {
	members, err := s.graph.GetChatMemberIDs(chatID, viewerID)
	if err != nil {
		return model.ChatPresenceView{}, err
	}
	typingAll, _ := s.ephemeral.ListTyping(chatID)
	recordingAll, _ := s.ephemeral.ListRecording(chatID)

	typing := make([]string, 0)
	recording := make(map[string]string)
	for _, memberID := range members {
		if memberID == viewerID {
			continue
		}
		if !s.canViewOnline(viewerID, memberID) {
			continue
		}
		for _, t := range typingAll {
			if t == memberID {
				typing = append(typing, memberID)
				break
			}
		}
		if kind, ok := recordingAll[memberID]; ok {
			recording[memberID] = kind
		}
	}
	return model.ChatPresenceView{
		ChatID:    chatID,
		Typing:    typing,
		Recording: recording,
	}, nil
}

func (s *Service) GetPrivacy(userID string) (model.PresencePrivacySettings, error) {
	return s.privacy.GetPresencePrivacy(userID)
}

func (s *Service) UpdatePrivacy(userID string, req model.UpdatePresencePrivacyRequest) (model.PresencePrivacySettings, error) {
	current, err := s.privacy.GetPresencePrivacy(userID)
	if err != nil {
		return current, err
	}
	if req.OnlineVisibility != nil {
		current.OnlineVisibility = normalizeVisibility(*req.OnlineVisibility)
	}
	if req.LastSeenVisibility != nil {
		current.LastSeenVisibility = normalizeVisibility(*req.LastSeenVisibility)
	}
	if err := s.privacy.SetPresencePrivacy(userID, current); err != nil {
		return current, err
	}
	return current, nil
}

// LegacyPresenceResponse — GET /api/users/{id}/presence.
func (s *Service) LegacyPresenceResponse(viewerID, targetID string) (model.PresenceResponse, error) {
	view, err := s.ViewUser(viewerID, targetID)
	if err != nil {
		return model.PresenceResponse{}, err
	}
	resp := model.PresenceResponse{
		UserID:       targetID,
		Status:       view.Status,
		ShowLastSeen: view.LastSeenBucket != "",
	}
	if view.Status == model.PresenceHidden {
		resp.Status = model.PresenceOffline
		resp.ShowLastSeen = false
	}
	return resp, nil
}

func (s *Service) fanoutPresenceOnline(userID string) {
	recipients := s.visibleRecipients(userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "presence.online",
		Data:  map[string]string{"user_id": userID},
	})
}

func (s *Service) fanoutPresenceOffline(userID string) {
	recipients := s.visibleRecipients(userID)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "presence.offline",
		Data:  map[string]string{"user_id": userID},
	})
}

func (s *Service) fanoutCallStarted(userID, callID string, participantIDs []string) {
	recipients := uniqueStrings(participantIDs)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "call.started",
		Data: map[string]string{
			"user_id": userID,
			"call_id": callID,
		},
	})
}

func (s *Service) fanoutCallEnded(userID, callID string, participantIDs []string) {
	recipients := uniqueStrings(participantIDs)
	s.pub.BroadcastToUsers(recipients, model.WSEvent{
		Event: "call.ended",
		Data: map[string]string{
			"user_id": userID,
			"call_id": callID,
		},
	})
}

func (s *Service) visibleRecipients(targetUserID string) []string {
	contacts, err := s.graph.ListContactUserIDs(targetUserID)
	if err != nil {
		return nil
	}
	out := make([]string, 0, len(contacts))
	for _, viewerID := range contacts {
		if s.canViewOnline(viewerID, targetUserID) {
			out = append(out, viewerID)
		}
	}
	return out
}

func filterOtherMembers(members []string, selfID string) []string {
	out := make([]string, 0, len(members))
	for _, id := range members {
		if id != selfID {
			out = append(out, id)
		}
	}
	return out
}

func uniqueStrings(ids []string) []string {
	seen := make(map[string]struct{}, len(ids))
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if id == "" {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	return out
}
