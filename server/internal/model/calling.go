// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

const (
	CallTypeAudio = "audio"
	CallTypeVideo = "video"

	CallStatusRinging    = "ringing"
	CallStatusConnecting = "connecting"
	CallStatusActive     = "active"
	CallStatusEnded      = "ended"
	CallStatusRejected   = "rejected"
	CallStatusMissed     = "missed"

	CallScopeDM    = "dm"
	CallScopeGroup = "group"
	CallScopeAdHoc = "ad_hoc"
	CallScopeLink  = "link"

	CallParticipantRoleStarter  = "starter"
	CallParticipantRoleInvited  = "invited"
	CallParticipantRoleJoined   = "joined"
	CallParticipantRoleListener = "listener"
	CallParticipantRoleMod      = "moderator"

	CallInviteStateInvited  = "invited"
	CallInviteStateRinging  = "ringing"
	CallInviteStateAccepted = "accepted"
	CallInviteStateRejected = "rejected"
	CallInviteStateMissed   = "missed"
	CallInviteStateJoined   = "joined"
	CallInviteStateLeft     = "left"
	CallInviteStateRemoved  = "removed"

	CallInvitePending  = "pending"
	CallInviteAccepted = "accepted"
	CallInviteDeclined = "declined"
	CallInviteExpired  = "expired"
	CallInviteRevoked  = "revoked"

	CallMediaAudioOnly   = "audio_only"
	CallMediaVideoLow    = "video_low"
	CallMediaVideoNormal = "video_normal"
	CallMediaMuted       = "muted"
)

// CreateCallRequest supports legacy 1:1 (callee_id) and group-ready
// (chat_id + initial_invitee_ids). Server maps callee_id to a DM call.
type CreateCallRequest struct {
	CalleeID          string   `json:"callee_id,omitempty"`
	ChatID            string   `json:"chat_id,omitempty"`
	InitialInviteeIDs []string `json:"initial_invitee_ids,omitempty"`
	CallType          string   `json:"call_type,omitempty"`
	DeviceID          string   `json:"device_id,omitempty"`
	CallScope         string   `json:"call_scope,omitempty"`
}

type CallSession struct {
	ID               string     `json:"id"`
	CallerID         string     `json:"caller_id"`
	CalleeID         string     `json:"callee_id,omitempty"`
	ChatID           string     `json:"chat_id,omitempty"`
	StartedByUserID  string     `json:"started_by_user_id,omitempty"`
	CallScope        string     `json:"call_scope,omitempty"`
	CallType         string     `json:"call_type"`
	Status           string     `json:"status"`
	LivekitRoomID    string     `json:"livekit_room_id"`
	CallerDeviceID   string     `json:"caller_device_id,omitempty"`
	SelectedRegion   string     `json:"selected_region,omitempty"`
	RouteClass       string     `json:"route_class,omitempty"`
	PolicyVersion    int        `json:"policy_version,omitempty"`
	LowBandwidthMode bool       `json:"low_bandwidth_mode"`
	CreatedAt        time.Time  `json:"created_at"`
	AcceptedAt       *time.Time `json:"accepted_at,omitempty"`
	ConnectedAt      *time.Time `json:"connected_at,omitempty"`
	EndedAt          *time.Time `json:"ended_at,omitempty"`
	DurationSec      int        `json:"duration_sec"`
	// Participants is optional; populated when group-ready clients request detail.
	Participants []CallParticipant `json:"participants,omitempty"`
}

type CallParticipant struct {
	CallID      string     `json:"call_id,omitempty"`
	UserID      string     `json:"user_id"`
	DeviceID    string     `json:"device_id,omitempty"`
	Role        string     `json:"role,omitempty"`
	InviteState string     `json:"invite_state,omitempty"`
	MediaState  string     `json:"media_state,omitempty"`
	JoinedAt    *time.Time `json:"joined_at,omitempty"`
	LeftAt      *time.Time `json:"left_at,omitempty"`
}

type CallInvite struct {
	ID              string     `json:"id"`
	CallID          string     `json:"call_id"`
	InvitedUserID   string     `json:"invited_user_id"`
	InvitedByUserID string     `json:"invited_by_user_id"`
	State           string     `json:"state"`
	CreatedAt       time.Time  `json:"created_at"`
	ExpiresAt       *time.Time `json:"expires_at,omitempty"`
}

type CallTokenResponse struct {
	Token         string          `json:"token"`
	LivekitURL    string          `json:"livekit_url"`
	RoomName      string          `json:"room_name"`
	ParticipantID string          `json:"participant_id"`
	MediaConfig   CallMediaConfig `json:"media_config"`
	// Route metadata for quality policy (region ids only, no location PII).
	SelectedRegion string `json:"selected_region,omitempty"`
	RouteClass     string `json:"route_class,omitempty"`
}

type CallMediaConfig struct {
	E2EE             bool        `json:"e2ee"`
	AdaptiveStream   bool        `json:"adaptive_stream"`
	Dynacast         bool        `json:"dynacast"`
	Simulcast        bool        `json:"simulcast"`
	LowBandwidthAuto bool        `json:"low_bandwidth_auto"`
	LowBandwidthMode bool        `json:"low_bandwidth_mode"`
	AudioFirst       bool        `json:"audio_first,omitempty"`
	PolicyVersion    int         `json:"policy_version,omitempty"`
	Audio            AudioConfig `json:"audio"`
	Video            VideoConfig `json:"video"`
	NetworkHint      string      `json:"network_hint,omitempty"`
}

type AudioConfig struct {
	Codec            string `json:"codec"`
	Mono             bool   `json:"mono"`
	BitrateKbps      int    `json:"bitrate_kbps"`
	Dtx              bool   `json:"dtx"`
	NoiseSuppression bool   `json:"noise_suppression"`
	EchoCancellation bool   `json:"echo_cancellation"`
	AutoGainControl  bool   `json:"auto_gain_control"`
}

type VideoConfig struct {
	Enabled   bool `json:"enabled"`
	MaxWidth  int  `json:"max_width,omitempty"`
	MaxHeight int  `json:"max_height,omitempty"`
	MaxFps    int  `json:"max_fps,omitempty"`
	Simulcast bool `json:"simulcast"`
}

type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

type ICEServersResponse struct {
	Servers []ICEServer `json:"servers"`
	TTL     int         `json:"ttl_seconds"`
}

type SubmitCallKeysRequest struct {
	Offers []CallKeyOfferInput `json:"offers"`
}

type CallKeyOfferInput struct {
	TargetUserID   string `json:"target_user_id"`
	TargetDeviceID string `json:"target_device_id"`
	EnvelopeType   int    `json:"envelope_type"`
	EncryptedKey   string `json:"encrypted_key"`
}

type CallKeyOffer struct {
	SourceUserID   string    `json:"source_user_id"`
	SourceDeviceID string    `json:"source_device_id"`
	TargetUserID   string    `json:"target_user_id"`
	TargetDeviceID string    `json:"target_device_id"`
	EnvelopeType   int       `json:"envelope_type"`
	EncryptedKey   string    `json:"encrypted_key"`
	CreatedAt      time.Time `json:"created_at"`
}

type CallActionResponse struct {
	Call CallSession `json:"call"`
}
