// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"errors"
	"fmt"

	"github.com/livekit/protocol/auth"
)

type RoomManager struct {
	cfg Config
}

func NewRoomManager(cfg Config) *RoomManager {
	return &RoomManager{cfg: cfg}
}

func (m *RoomManager) LiveKitURL() string {
	return m.cfg.LiveKitURL
}

func (m *RoomManager) RoomName(callID string) string {
	return "call-" + callID
}

func (m *RoomManager) IssueToken(roomName, participantIdentity string) (string, error) {
	if m.cfg.LiveKitAPIKey == "" || m.cfg.LiveKitSecret == "" {
		return "", errors.New("livekit is not configured")
	}

	at := auth.NewAccessToken(m.cfg.LiveKitAPIKey, m.cfg.LiveKitSecret).
		SetIdentity(participantIdentity).
		SetValidFor(m.cfg.TokenTTL).
		SetMetadata(fmt.Sprintf(`{"e2ee":%t}`, m.cfg.MediaE2EE))

	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	at.AddGrant(grant)

	token, err := at.ToJWT()
	if err != nil {
		return "", fmt.Errorf("livekit token: %w", err)
	}
	return token, nil
}

func DefaultMediaConfig(callType string, lowBandwidth bool) CallMediaConfigView {
	return DefaultMediaConfigWithE2EE(callType, lowBandwidth, true)
}

func (m *RoomManager) MediaConfig(callType string, lowBandwidth bool) CallMediaConfigView {
	return DefaultMediaConfigWithE2EE(callType, lowBandwidth, m.cfg.MediaE2EE)
}

func DefaultMediaConfigWithE2EE(callType string, lowBandwidth bool, e2ee bool) CallMediaConfigView {
	audioOnly := callType == CallTypeAudio || lowBandwidth
	cfg := CallMediaConfigView{
		E2EE:             e2ee,
		AdaptiveStream:   true,
		Dynacast:         true,
		Simulcast:        !audioOnly,
		LowBandwidthAuto: true,
		LowBandwidthMode: lowBandwidth,
		AudioFirst:       true,
		PolicyVersion:    1,
		Audio: AudioConfigView{
			Codec:            "opus",
			Mono:             true,
			BitrateKbps:      24,
			Dtx:              true,
			NoiseSuppression: true,
			EchoCancellation: true,
			AutoGainControl:  true,
		},
		// Even for video call type, start audio-first; client probes video later.
		Video: VideoConfigView{
			Enabled:   false,
			MaxWidth:  320,
			MaxHeight: 180,
			MaxFps:    12,
			Simulcast: !audioOnly,
		},
		VideoLadder: []VideoLadderStep{
			{Mode: "probe", Width: 160, Height: 90, Fps: 7, BitrateKbps: 80},
			{Mode: "low", Width: 320, Height: 180, Fps: 12, BitrateKbps: 180},
			{Mode: "normal", Width: 640, Height: 360, Fps: 20, BitrateKbps: 500},
		},
	}
	if lowBandwidth {
		cfg.NetworkHint = "weak_network_voice_priority"
		cfg.Audio.BitrateKbps = 16
		cfg.Video.Enabled = false
		cfg.Simulcast = false
	}
	return cfg
}

// View types avoid importing model in tests — mapped in handler.
type CallMediaConfigView struct {
	E2EE             bool
	AdaptiveStream   bool
	Dynacast         bool
	Simulcast        bool
	LowBandwidthAuto bool
	LowBandwidthMode bool
	AudioFirst       bool
	PolicyVersion    int
	Audio            AudioConfigView
	Video            VideoConfigView
	VideoLadder      []VideoLadderStep
	NetworkHint      string
}

type VideoLadderStep struct {
	Mode        string
	Width       int
	Height      int
	Fps         int
	BitrateKbps int
}

type AudioConfigView struct {
	Codec            string
	Mono             bool
	BitrateKbps      int
	Dtx              bool
	NoiseSuppression bool
	EchoCancellation bool
	AutoGainControl  bool
}

type VideoConfigView struct {
	Enabled   bool
	MaxWidth  int
	MaxHeight int
	MaxFps    int
	Simulcast bool
}

const (
	CallTypeAudio = "audio"
	CallTypeVideo = "video"
)
