// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"sync"
	"testing"
	"time"
)

type memStore struct {
	mu     sync.Mutex
	tokens map[string]PushToken
	prefs  map[string]Preferences
	logs   []string
}

func newMemStore() *memStore {
	return &memStore{
		tokens: map[string]PushToken{},
		prefs:  map[string]Preferences{},
	}
}

func (m *memStore) UpsertPushToken(userID, deviceID, platform, token string) (PushToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	rec := PushToken{
		ID: userID + ":" + deviceID + ":" + platform, UserID: userID, DeviceID: deviceID,
		Platform: platform, Token: token, TokenStatus: TokenStatusActive, UpdatedAt: time.Now().UTC(),
	}
	m.tokens[rec.ID] = rec
	return rec, nil
}
func (m *memStore) RevokePushToken(userID, tokenID string) error { return nil }
func (m *memStore) RevokePushTokensForDevice(userID, deviceID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, t := range m.tokens {
		if t.UserID == userID && t.DeviceID == deviceID {
			t.TokenStatus = TokenStatusRevoked
			now := time.Now().UTC()
			t.RevokedAt = &now
			m.tokens[id] = t
		}
	}
	return nil
}
func (m *memStore) ListActivePushTokens(userID string) ([]PushToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []PushToken
	for _, t := range m.tokens {
		if t.UserID == userID && t.TokenStatus == TokenStatusActive {
			out = append(out, t)
		}
	}
	return out, nil
}
func (m *memStore) MarkTokenSuccess(tokenID string, at time.Time) error { return nil }
func (m *memStore) MarkTokenFailure(tokenID string, at time.Time, invalidate bool) error {
	return nil
}
func (m *memStore) GetPreferences(userID string) (Preferences, error) {
	return DefaultPreferences(userID), nil
}
func (m *memStore) UpsertPreferences(userID string, update func(*Preferences) error) (Preferences, error) {
	p := DefaultPreferences(userID)
	_ = update(&p)
	return p, nil
}
func (m *memStore) AppendDeliveryLog(userID, tokenID, notificationType, platform, status, errMsg string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs = append(m.logs, status+":"+errMsg)
	return nil
}
func (m *memStore) PurgeDeliveryLogsBefore(before time.Time) (int64, error) { return 0, nil }
func (m *memStore) EnqueueRetry(job RetryJob) (string, error)               { return "j1", nil }
func (m *memStore) ListDueRetries(limit int, now time.Time) ([]RetryJob, error) {
	return nil, nil
}
func (m *memStore) UpdateRetryAttempt(id string, attempts int, nextAttemptAt time.Time, lastError string) error {
	return nil
}
func (m *memStore) CompleteRetry(id string) error { return nil }

type recordingAdapter struct {
	platform string
	mu       sync.Mutex
	sends    []map[string]string
}

func (a *recordingAdapter) Platform() string { return a.platform }
func (a *recordingAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	cp := map[string]string{}
	for k, v := range data {
		cp[k] = v
	}
	cp["_priority"] = priority
	cp["_device"] = token.DeviceID
	a.sends = append(a.sends, cp)
	return nil
}

type blockingAdapter struct {
	platform string
}

func (a blockingAdapter) Platform() string { return a.platform }
func (a blockingAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	<-ctx.Done()
	return ctx.Err()
}

func TestNotifyNewEnvelope_MinimalPokeNoContent(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "dev-phone", PlatformAndroid, "tok-1")
	ad := &recordingAdapter{platform: PlatformAndroid}
	svc := NewService(store, map[string]PushAdapter{PlatformAndroid: ad})
	svc.debounceWindow = 0

	if err := svc.NotifyNewEnvelope("user-1", nil); err != nil {
		t.Fatal(err)
	}
	if len(ad.sends) != 1 {
		t.Fatalf("sends=%d", len(ad.sends))
	}
	data := ad.sends[0]
	if data["type"] != TypeNewMessage {
		t.Fatalf("type=%q", data["type"])
	}
	if data["schema"] != PayloadSchemaV1 {
		t.Fatalf("schema=%q", data["schema"])
	}
	for _, banned := range []string{"body", "text", "sender_name", "ciphertext", "envelope_id"} {
		if _, ok := data[banned]; ok {
			t.Fatalf("payload must not contain %q (arXiv leak class)", banned)
		}
	}
	if data["_priority"] != PriorityHigh {
		t.Fatalf("message wake priority=%q want high (local UI path)", data["_priority"])
	}
}

func TestNotifyNewEnvelope_ExcludesOnlineDevices(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "phone", PlatformAndroid, "t-phone")
	_, _ = store.UpsertPushToken("user-1", "desktop", PlatformAndroid, "t-desk")
	ad := &recordingAdapter{platform: PlatformAndroid}
	svc := NewService(store, map[string]PushAdapter{PlatformAndroid: ad})
	svc.debounceWindow = 0

	if err := svc.NotifyNewEnvelope("user-1", []string{"desktop"}); err != nil {
		t.Fatal(err)
	}
	if len(ad.sends) != 1 {
		t.Fatalf("expected 1 send (phone only), got %d", len(ad.sends))
	}
	if ad.sends[0]["_device"] != "phone" {
		t.Fatalf("device=%q", ad.sends[0]["_device"])
	}
}

func TestNotifyNewEnvelope_TimesOutBlockingAdapter(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "phone", PlatformAndroid, "t-phone")
	svc := NewService(store, map[string]PushAdapter{
		PlatformAndroid: blockingAdapter{platform: PlatformAndroid},
	})
	svc.debounceWindow = 0
	svc.newEnvelopeTimeout = 20 * time.Millisecond

	start := time.Now()
	err := svc.NotifyNewEnvelope("user-1", nil)
	if err == nil {
		t.Fatal("expected timeout error")
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("NotifyNewEnvelope blocked too long: %s", elapsed)
	}
}

func TestNotifyPrekeysLow_NormalPriority(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "dev-1", PlatformAndroid, "tok")
	ad := &recordingAdapter{platform: PlatformAndroid}
	svc := NewService(store, map[string]PushAdapter{PlatformAndroid: ad})

	if err := svc.NotifyPrekeysLow("user-1", "dev-1", 2); err != nil {
		t.Fatal(err)
	}
	if ad.sends[0]["_priority"] != PriorityNormal {
		t.Fatalf("prekeys priority=%q want normal (Firebase 2025)", ad.sends[0]["_priority"])
	}
}

func TestDebounceMessageWakes(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "dev-1", PlatformAndroid, "tok")
	ad := &recordingAdapter{platform: PlatformAndroid}
	svc := NewService(store, map[string]PushAdapter{PlatformAndroid: ad})
	svc.debounceWindow = time.Minute

	_ = svc.NotifyNewEnvelope("user-1", nil)
	_ = svc.NotifyNewEnvelope("user-1", nil)
	if len(ad.sends) != 1 {
		t.Fatalf("debounced sends=%d want 1", len(ad.sends))
	}
}

func TestRevokeTokensForDevice(t *testing.T) {
	store := newMemStore()
	_, _ = store.UpsertPushToken("user-1", "dev-zombie", PlatformAndroid, "tok")
	svc := NewService(store, map[string]PushAdapter{})
	if err := svc.RevokeTokensForDevice("user-1", "dev-zombie"); err != nil {
		t.Fatal(err)
	}
	tokens, _ := store.ListActivePushTokens("user-1")
	if len(tokens) != 0 {
		t.Fatalf("expected revoked, still %d active", len(tokens))
	}
}
