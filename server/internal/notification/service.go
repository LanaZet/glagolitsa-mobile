// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"
)

// MetricsSink — optional counters (P6).
type MetricsSink interface {
	IncrementPushSent(platform, notificationType string)
	IncrementPushFailed(platform, notificationType string)
	IncrementPushSkipped(reason string)
	IncrementPushTokenInvalidated()
}

// NoopMetrics — default.
type NoopMetrics struct{}

func (NoopMetrics) IncrementPushSent(string, string)   {}
func (NoopMetrics) IncrementPushFailed(string, string) {}
func (NoopMetrics) IncrementPushSkipped(string)        {}
func (NoopMetrics) IncrementPushTokenInvalidated()     {}

const (
	defaultDebounceWindow     = 10 * time.Second
	defaultNewEnvelopeTimeout = 2 * time.Second
)

// Service — Push Router + Privacy Guard + adapters + retry + debounce.
type Service struct {
	Store    Store
	Adapters map[string]PushAdapter
	Privacy  PrivacyGuard
	Retry    *RetryWorker
	Metrics  MetricsSink

	// Debounce — optional Redis cluster coalesce (Mattermost HA); else in-memory.
	Debounce Debouncer

	debounceWindow     time.Duration
	newEnvelopeTimeout time.Duration
	lastWakeMu         sync.Mutex
	lastWake           map[string]time.Time // userID|deviceID|type
}

func NewService(store Store, adapters map[string]PushAdapter) *Service {
	if adapters == nil {
		adapters = AdaptersFromEnv()
	}
	svc := &Service{
		Store:              store,
		Adapters:           adapters,
		Metrics:            NoopMetrics{},
		debounceWindow:     defaultDebounceWindow,
		newEnvelopeTimeout: defaultNewEnvelopeTimeout,
		lastWake:           make(map[string]time.Time),
	}
	svc.Retry = NewRetryWorker(svc)
	return svc
}

func (s *Service) SetMetrics(m MetricsSink) {
	if m != nil {
		s.Metrics = m
	}
}

// SetDebouncer wires cluster-safe wake coalesce (optional Redis).
func (s *Service) SetDebouncer(d Debouncer) {
	s.Debounce = d
}

func (s *Service) Start(ctx context.Context) {
	_ = ctx
}

// FlushRetries processes due notification retries (jobs worker entrypoint).
func (s *Service) FlushRetries(ctx context.Context) error {
	if s.Retry != nil {
		s.Retry.flush(ctx)
	}
	return nil
}

// PurgeDeliveryLogs trims old delivery log rows (P6 retention job).
func (s *Service) PurgeDeliveryLogs(ctx context.Context) error {
	_ = ctx
	// Keep 14 days of delivery diagnostics.
	cutoff := time.Now().UTC().Add(-14 * 24 * time.Hour)
	n, err := s.Store.PurgeDeliveryLogsBefore(cutoff)
	if err != nil {
		return err
	}
	if n > 0 {
		slog.Info("notification_delivery_log_purged", "rows", n, "before", cutoff)
	}
	return nil
}

// Send — internal entry: opaque payload, privacy-checked, device-filtered.
func (s *Service) Send(ctx context.Context, req SendRequest) error {
	req.Type = strings.TrimSpace(req.Type)
	if req.UserID == "" || req.Type == "" {
		return fmt.Errorf("user_id and type are required")
	}
	if req.Payload == nil {
		req.Payload = map[string]any{}
	}
	// Always stamp schema for allowlist clients / debugging.
	if _, ok := req.Payload["schema"]; !ok {
		req.Payload["schema"] = PayloadSchemaV1
	}
	if err := s.Privacy.ValidatePayload(req.Payload); err != nil {
		return err
	}
	if req.Priority == "" {
		req.Priority = PriorityForType(req.Type)
	}

	prefs, err := s.Store.GetPreferences(req.UserID)
	if err != nil {
		return err
	}
	if !s.allowedByPreferences(req.Type, prefs) {
		_ = s.Store.AppendDeliveryLog(req.UserID, "", req.Type, "", DeliverySkipped, "disabled by preferences")
		s.Metrics.IncrementPushSkipped("preferences")
		return nil
	}

	tokens, err := s.Store.ListActivePushTokens(req.UserID)
	if err != nil {
		return err
	}
	tokens = filterTokens(tokens, req.OnlyDeviceIDs, req.ExcludeDeviceIDs)
	if len(tokens) == 0 {
		_ = s.Store.AppendDeliveryLog(req.UserID, "", req.Type, "", DeliverySkipped, "no active tokens")
		s.Metrics.IncrementPushSkipped("no_tokens")
		return nil
	}

	data := s.payloadToStringMap(req.Type, req.Payload)
	silent := IsDataOnlyType(req.Type)

	var firstErr error
	sentAny := false
	for _, token := range tokens {
		if !req.SkipDebounce && s.shouldDebounce(req.UserID, token.DeviceID, req.Type) {
			_ = s.Store.AppendDeliveryLog(req.UserID, token.ID, req.Type, token.Platform, DeliverySkipped, "debounced")
			s.Metrics.IncrementPushSkipped("debounced")
			continue
		}
		adapter, ok := s.Adapters[token.Platform]
		if !ok {
			_ = s.Store.AppendDeliveryLog(req.UserID, token.ID, req.Type, token.Platform, DeliverySkipped, "no adapter")
			s.Metrics.IncrementPushSkipped("no_adapter")
			continue
		}
		sendErr := adapter.Send(ctx, token, data, req.Priority, silent)
		if sendErr != nil {
			permanent := IsPermanentTokenError(sendErr)
			_ = s.Store.MarkTokenFailure(token.ID, time.Now().UTC(), permanent)
			if permanent {
				s.Metrics.IncrementPushTokenInvalidated()
			}
			_ = s.Store.AppendDeliveryLog(req.UserID, token.ID, req.Type, token.Platform, DeliveryFailed, sendErr.Error())
			s.Metrics.IncrementPushFailed(token.Platform, req.Type)
			slog.Warn("notification_push",
				"status", "failed",
				"user_id", req.UserID,
				"device_id", token.DeviceID,
				"token_id", token.ID,
				"type", req.Type,
				"call_id", data["call_id"],
				"platform", token.Platform,
				"error", sendErr.Error(),
			)
			if firstErr == nil {
				firstErr = sendErr
			}
			continue
		}
		s.markDebounce(req.UserID, token.DeviceID, req.Type)
		_ = s.Store.MarkTokenSuccess(token.ID, time.Now().UTC())
		_ = s.Store.AppendDeliveryLog(req.UserID, token.ID, req.Type, token.Platform, DeliverySent, "")
		s.Metrics.IncrementPushSent(token.Platform, req.Type)
		slog.Info("notification_push",
			"status", "sent",
			"user_id", req.UserID,
			"device_id", token.DeviceID,
			"token_id", token.ID,
			"type", req.Type,
			"call_id", data["call_id"],
			"platform", token.Platform,
			"priority", req.Priority,
		)
		sentAny = true
	}

	if firstErr != nil && !sentAny {
		// Only retry transient failures (invalid tokens already marked).
		if !IsPermanentTokenError(firstErr) {
			jobID, err := s.Store.EnqueueRetry(RetryJob{
				UserID:           req.UserID,
				NotificationType: req.Type,
				Payload:          req.Payload,
				Priority:         req.Priority,
				MaxAttempts:      5,
				NextAttemptAt:    time.Now().UTC().Add(30 * time.Second),
				CreatedAt:        time.Now().UTC(),
			})
			if err == nil {
				slog.Warn("notification_retry_enqueued", "job_id", jobID, "user_id", req.UserID, "type", req.Type)
			}
		}
		return firstErr
	}
	return nil
}

func filterTokens(tokens []PushToken, only, exclude []string) []PushToken {
	if len(only) == 0 && len(exclude) == 0 {
		return tokens
	}
	onlySet := toSet(only)
	excludeSet := toSet(exclude)
	out := make([]PushToken, 0, len(tokens))
	for _, t := range tokens {
		if len(onlySet) > 0 {
			if _, ok := onlySet[t.DeviceID]; !ok {
				continue
			}
		}
		if _, skip := excludeSet[t.DeviceID]; skip {
			continue
		}
		out = append(out, t)
	}
	return out
}

func toSet(ids []string) map[string]struct{} {
	if len(ids) == 0 {
		return nil
	}
	m := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id != "" {
			m[id] = struct{}{}
		}
	}
	return m
}

func (s *Service) shouldDebounce(userID, deviceID, notifType string) bool {
	if s.debounceWindow <= 0 {
		return false
	}
	// Only coalesce message wakes.
	if notifType != TypeNewMessage && notifType != TypeMessageSync {
		return false
	}
	// Cluster path (Mattermost HA): Redis SET NX — no content in keys.
	if s.Debounce != nil {
		allowed := s.Debounce.Allow(context.Background(), userID, deviceID, notifType, s.debounceWindow)
		return !allowed
	}
	key := userID + "|" + deviceID + "|" + notifType
	s.lastWakeMu.Lock()
	defer s.lastWakeMu.Unlock()
	if last, ok := s.lastWake[key]; ok && time.Since(last) < s.debounceWindow {
		return true
	}
	return false
}

func (s *Service) markDebounce(userID, deviceID, notifType string) {
	if s.debounceWindow <= 0 || s.Debounce != nil {
		// Redis debouncer already stamped the window on Allow().
		return
	}
	if notifType != TypeNewMessage && notifType != TypeMessageSync {
		return
	}
	key := userID + "|" + deviceID + "|" + notifType
	s.lastWakeMu.Lock()
	s.lastWake[key] = time.Now()
	s.lastWakeMu.Unlock()
}

func (s *Service) allowedByPreferences(notificationType string, prefs Preferences) bool {
	switch notificationType {
	case TypeNewMessage, TypeMessageSync:
		return prefs.MessagesEnabled
	case TypeIncomingCall, TypeMissedCall:
		return prefs.CallsEnabled
	case TypeDeviceAdded:
		return prefs.NewDeviceEnabled
	case TypePrekeysLow, TypeSecurityAlert, TypeKeyChanged:
		return true
	default:
		return true
	}
}

func (s *Service) payloadToStringMap(notificationType string, payload map[string]any) map[string]string {
	out := map[string]string{"type": notificationType}
	for key, value := range payload {
		switch v := value.(type) {
		case string:
			out[key] = v
		case int:
			out[key] = strconv.Itoa(v)
		case int64:
			out[key] = strconv.FormatInt(v, 10)
		case float64:
			out[key] = strconv.FormatInt(int64(v), 10)
		case bool:
			out[key] = strconv.FormatBool(v)
		default:
			out[key] = fmt.Sprint(v)
		}
	}
	return out
}

// NotifyNewEnvelope — Signal-style minimal poke (no envelope body / optional ids).
// excludeDeviceIDs = currently WS-connected devices (P2 multi-device).
func (s *Service) NotifyNewEnvelope(recipientUserID string, excludeDeviceIDs []string) error {
	ctx := context.Background()
	cancel := func() {}
	if s.newEnvelopeTimeout > 0 {
		ctx, cancel = context.WithTimeout(ctx, s.newEnvelopeTimeout)
	}
	defer cancel()

	err := s.Send(ctx, SendRequest{
		UserID:           recipientUserID,
		Type:             TypeNewMessage,
		Payload:          map[string]any{"schema": PayloadSchemaV1},
		Priority:         PriorityForType(TypeNewMessage),
		ExcludeDeviceIDs: excludeDeviceIDs,
	})
	if err != nil {
		slog.Warn("push_notify_new_envelope_failed",
			"user_id", recipientUserID,
			"exclude_devices", len(excludeDeviceIDs),
			"error", err.Error(),
		)
	} else {
		slog.Info("push_notify_new_envelope",
			"user_id", recipientUserID,
			"exclude_devices", len(excludeDeviceIDs),
		)
	}
	return err
}

// NotifyPrekeysLow — normal priority silent wake (no user-visible UI required).
func (s *Service) NotifyPrekeysLow(accountID, deviceID string, remaining int) error {
	return s.Send(context.Background(), SendRequest{
		UserID: accountID,
		Type:   TypePrekeysLow,
		Payload: map[string]any{
			"schema":    PayloadSchemaV1,
			"device_id": deviceID,
			"remaining": remaining,
		},
		Priority:      PriorityForType(TypePrekeysLow),
		OnlyDeviceIDs: []string{deviceID},
		SkipDebounce:  true,
	})
}

// NotifyIncomingCall implements IncomingCallNotifier.
// Push is wake-only: type + opaque call_id (+ collapse). Client fetches/decrypts
// user-visible copy after wake. Never put caller name, avatar, room URL, or tokens.
func (s *Service) NotifyIncomingCall(calleeID string, payload IncomingCallPayload) error {
	return s.Send(context.Background(), SendRequest{
		UserID: calleeID,
		Type:   TypeIncomingCall,
		Payload: map[string]any{
			"schema":      PayloadSchemaV1,
			"type":        "incoming_call",
			"call_id":     payload.CallID,
			"collapse_id": "call-" + payload.CallID,
		},
		Priority:     PriorityForType(TypeIncomingCall),
		SkipDebounce: true,
	})
}

// NotifyMissedCall — user-visible high priority.
func (s *Service) NotifyMissedCall(calleeID, callID string) error {
	return s.Send(context.Background(), SendRequest{
		UserID: calleeID,
		Type:   TypeMissedCall,
		Payload: map[string]any{
			"schema":  PayloadSchemaV1,
			"call_id": callID,
		},
		Priority:     PriorityForType(TypeMissedCall),
		SkipDebounce: true,
	})
}

// RevokeTokensForDevice — lifecycle hook when device is revoked/purged.
func (s *Service) RevokeTokensForDevice(userID, deviceID string) error {
	return s.Store.RevokePushTokensForDevice(userID, deviceID)
}
