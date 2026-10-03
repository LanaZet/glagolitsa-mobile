// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"strings"
	"testing"
)

func newTestFCMAdapter(capture func(context.Context, string, map[string]any) error) *FCMAdapter {
	return &FCMAdapter{
		projectID: "test-proj",
		send:      capture,
	}
}

func TestFCMAdapter_DoesNotForceHighOnSilent(t *testing.T) {
	var captured map[string]any
	ad := newTestFCMAdapter(func(ctx context.Context, token string, message map[string]any) error {
		captured = message
		return nil
	})
	err := ad.Send(context.Background(), PushToken{Token: "t"}, map[string]string{
		"type":   TypePrekeysLow,
		"schema": "1",
	}, PriorityNormal, true)
	if err != nil {
		t.Fatal(err)
	}
	android := captured["android"].(map[string]any)
	if android["priority"] != "NORMAL" {
		t.Fatalf("priority=%v want NORMAL (silent must not force HIGH)", android["priority"])
	}
}

func TestFCMAdapter_CollapseKeyOnMessage(t *testing.T) {
	var captured map[string]any
	ad := newTestFCMAdapter(func(ctx context.Context, token string, message map[string]any) error {
		captured = message
		return nil
	})
	_ = ad.Send(context.Background(), PushToken{Token: "t"}, map[string]string{
		"type":   TypeNewMessage,
		"schema": "1",
	}, PriorityHigh, true)
	android := captured["android"].(map[string]any)
	if android["collapse_key"] != "glag_msg_wake" {
		t.Fatalf("collapse_key=%v", android["collapse_key"])
	}
	if android["priority"] != "HIGH" {
		t.Fatalf("priority=%v", android["priority"])
	}
	// data-only: no notification block
	if _, ok := captured["notification"]; ok {
		t.Fatal("must not set FCM notification block (content leak risk)")
	}
}

func TestFCMAdapterRequiresCredentialsWhenProjectConfigured(t *testing.T) {
	_, err := NewFCMAdapter(FCMConfig{ProjectID: "test-proj"})
	if err == nil {
		t.Fatal("expected missing credentials to fail closed")
	}
	if !strings.Contains(err.Error(), "credentials") {
		t.Fatalf("error=%q, want credentials", err.Error())
	}
}

func TestAdaptersFromEnvFailsAndroidPushClosedWhenFCMIncomplete(t *testing.T) {
	t.Setenv("FCM_PROJECT_ID", "test-proj")
	t.Setenv("FCM_CREDENTIALS_FILE", "")
	t.Setenv("FCM_CREDENTIALS_JSON", "")

	adapters := AdaptersFromEnv()
	adapter, ok := adapters[PlatformAndroid].(ErrorPushAdapter)
	if !ok {
		t.Fatalf("android adapter type=%T, want ErrorPushAdapter", adapters[PlatformAndroid])
	}
	err := adapter.Send(context.Background(), PushToken{Token: "t"}, map[string]string{
		"type": TypeIncomingCall,
	}, PriorityVoIP, true)
	if err == nil {
		t.Fatal("expected incomplete FCM adapter to fail send")
	}
}

func TestAdaptersFromEnvReportsUnreadableFCMCredentialsFile(t *testing.T) {
	t.Setenv("FCM_PROJECT_ID", "test-proj")
	t.Setenv("FCM_CREDENTIALS_FILE", "/path/that/does/not/exist/fcm.json")
	t.Setenv("FCM_CREDENTIALS_JSON", "")

	adapters := AdaptersFromEnv()
	adapter, ok := adapters[PlatformAndroid].(ErrorPushAdapter)
	if !ok {
		t.Fatalf("android adapter type=%T, want ErrorPushAdapter", adapters[PlatformAndroid])
	}
	err := adapter.Send(context.Background(), PushToken{Token: "t"}, map[string]string{
		"type": TypeIncomingCall,
	}, PriorityVoIP, true)
	if err == nil {
		t.Fatal("expected unreadable FCM credentials to fail send")
	}
	if !strings.Contains(err.Error(), "read FCM credentials file") {
		t.Fatalf("error=%q, want read FCM credentials file", err.Error())
	}
}
