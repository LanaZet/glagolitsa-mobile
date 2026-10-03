// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import "testing"

func TestDefaultMediaConfigKeepsE2EEEnabledByDefault(t *testing.T) {
	cfg := DefaultMediaConfig(CallTypeAudio, false)
	if !cfg.E2EE {
		t.Fatal("default LiveKit media config must keep client frame E2EE enabled")
	}
	if !cfg.AudioFirst {
		t.Fatal("audio calls must remain audio-first")
	}
}

func TestRoomManagerMediaE2EEKillSwitchIsExplicit(t *testing.T) {
	rm := NewRoomManager(Config{MediaE2EE: false})
	cfg := rm.MediaConfig(CallTypeAudio, false)
	if cfg.E2EE {
		t.Fatal("explicit MediaE2EE=false must be reflected in media config")
	}
}
