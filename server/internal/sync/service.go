// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type Service struct {
	Store  Store
	Config Config
}

func NewService(syncStore Store, cfg Config) *Service {
	return &Service{Store: syncStore, Config: cfg}
}

func (s *Service) Start(ctx context.Context) {
	// Retry loop is driven by jobs.Server (Mattermost-style job watcher).
	_ = ctx
}

// FlushOfflineQueue applies due sync offline records (jobs worker entrypoint).
func (s *Service) FlushOfflineQueue(ctx context.Context) error {
	NewRetryWorker(s, s.Config).flushOnce(ctx)
	return nil
}

func (s *Service) ListEvents(userID string, afterEventID int64, limit int) (EventsResponse, error) {
	if limit <= 0 {
		limit = s.Config.DefaultEventLimit
	}
	if limit > s.Config.MaxEventLimit {
		limit = s.Config.MaxEventLimit
	}
	records, err := s.Store.ListSyncEvents(userID, afterEventID, limit)
	if err != nil {
		return EventsResponse{}, err
	}
	hasMore := len(records) > limit
	if hasMore {
		records = records[:limit]
	}
	events := make([]EventResponse, 0, len(records))
	for _, record := range records {
		events = append(events, eventFromRecord(record))
	}
	latest, _ := s.Store.GetLatestSyncEventID(userID)
	return EventsResponse{
		Events: events, HasMore: hasMore, LatestID: latest, ServerTime: store.NowUTC(),
	}, nil
}

func (s *Service) LegacySync(userID string, since time.Time, limit int) (LegacySyncResponse, error) {
	if limit <= 0 {
		limit = s.Config.DefaultEventLimit
	}
	var chats []model.Chat
	var err error
	if since.IsZero() {
		chats, err = s.Store.ListChatsForUser(userID)
	} else {
		chats, err = s.Store.ListChatsUpdatedSince(userID, since)
	}
	if err != nil {
		return LegacySyncResponse{}, err
	}
	if chats == nil {
		chats = []model.Chat{}
	}
	chatEvents, err := s.Store.ListChatEvents(userID, since, limit)
	if err != nil {
		return LegacySyncResponse{}, err
	}
	if chatEvents == nil {
		chatEvents = []model.ChatEvent{}
	}
	latest, _ := s.Store.GetLatestSyncEventID(userID)
	return LegacySyncResponse{
		ServerTime: store.NowUTC(),
		Chats:      chats,
		Events:     chatEvents,
		HasMore:    len(chatEvents) >= limit,
		LatestID:   latest,
	}, nil
}

func (s *Service) RegisterDevice(userID string, req RegisterDeviceRequest) (DeviceResponse, error) {
	deviceID := strings.TrimSpace(req.DeviceID)
	if deviceID == "" {
		return DeviceResponse{}, fmt.Errorf("device_id is required")
	}
	profile := RecommendProfile(req, req.SyncProfile)
	record, err := s.Store.RegisterSyncDevice(userID, store.RegisterSyncDeviceInput{
		DeviceID: deviceID, Label: req.Label, SyncProfile: profile,
	})
	if err != nil {
		return DeviceResponse{}, err
	}
	return DeviceResponse{
		DeviceID: record.DeviceID, Label: record.Label,
		LastAckedEventID: record.LastAckedEventID, SnapshotEventID: record.SnapshotEventID,
		SyncProfile: record.SyncProfile, RecommendedProfile: profile,
	}, nil
}

func (s *Service) Ack(userID string, req AckRequest) error {
	if strings.TrimSpace(req.DeviceID) == "" {
		return fmt.Errorf("device_id is required")
	}
	if req.LastEventID <= 0 {
		return fmt.Errorf("last_event_id is required")
	}
	return s.Store.AckSyncEvents(userID, req.DeviceID, req.LastEventID)
}

func (s *Service) GetSnapshot(userID string) (SnapshotResponse, error) {
	record, err := s.Store.GetLatestSyncSnapshot(userID)
	if err != nil {
		return SnapshotResponse{}, mapStoreErr(err)
	}
	return SnapshotResponse{
		EventID: record.EventID, SnapshotData: encodeB64(record.SnapshotData), CreatedAt: record.CreatedAt,
	}, nil
}

func (s *Service) SaveSnapshot(userID string, eventID int64, data []byte) (SnapshotResponse, error) {
	if eventID <= 0 {
		return SnapshotResponse{}, fmt.Errorf("event_id is required")
	}
	if len(data) == 0 {
		return SnapshotResponse{}, fmt.Errorf("snapshot_data is required")
	}
	record, err := s.Store.SaveSyncSnapshot(userID, eventID, data)
	if err != nil {
		return SnapshotResponse{}, err
	}
	return SnapshotResponse{
		EventID: record.EventID, SnapshotData: encodeB64(record.SnapshotData), CreatedAt: record.CreatedAt,
	}, nil
}

func (s *Service) PushEvent(userID string, req PushEventRequest) (PushEventResponse, error) {
	deviceID := strings.TrimSpace(req.DeviceID)
	clientEventID := strings.TrimSpace(req.ClientEventID)
	if deviceID == "" || clientEventID == "" || strings.TrimSpace(req.Operation) == "" {
		return PushEventResponse{}, fmt.Errorf("device_id, client_event_id and operation are required")
	}
	device, err := s.Store.GetSyncDevice(userID, deviceID)
	if err != nil {
		return PushEventResponse{}, mapStoreErr(err)
	}
	if !OperationAllowed(device.SyncProfile, req.Operation) {
		_, _ = s.Store.EnqueueSyncOffline(userID, store.EnqueueSyncEventInput{
			DeviceID: deviceID, ClientEventID: clientEventID, Operation: req.Operation,
			ChatID: req.ChatID, MessageID: req.MessageID, Version: req.Version,
			Ciphertext: mustDecode(req.Ciphertext), Metadata: req.Metadata,
		})
		return PushEventResponse{Queued: true, Reason: "deferred by adaptive sync profile"}, nil
	}

	ciphertext, err := decodeB64(req.Ciphertext)
	if err != nil {
		return PushEventResponse{}, fmt.Errorf("invalid ciphertext encoding")
	}

	if req.MessageID != "" && req.ChatID != "" && isMutatingOperation(req.Operation) {
		existing, _ := s.Store.GetSyncEventVersion(req.ChatID, req.MessageID, req.Operation)
		accept, reason := ResolveConflict(req.Version, existing)
		if !accept {
			return PushEventResponse{Rejected: true, Reason: reason, AcceptedVersion: existing}, nil
		}
	}

	queued, err := s.Store.EnqueueSyncOffline(userID, store.EnqueueSyncEventInput{
		DeviceID: deviceID, ClientEventID: clientEventID, Operation: req.Operation,
		ChatID: req.ChatID, MessageID: req.MessageID, Version: req.Version,
		Ciphertext: ciphertext, Metadata: req.Metadata,
	})
	if err != nil && !errors.Is(err, store.ErrAlreadyExists) {
		return PushEventResponse{}, err
	}
	if errors.Is(err, store.ErrAlreadyExists) {
		return PushEventResponse{Queued: true}, nil
	}
	applied, applyErr := s.ApplyOfflineRecord(context.Background(), queued)
	if applyErr != nil {
		return PushEventResponse{Queued: true}, nil
	}
	return PushEventResponse{
		Queued: false, EventID: applied.EventID, AcceptedVersion: applied.Version,
	}, nil
}

func (s *Service) ApplyOfflineRecord(ctx context.Context, record store.SyncOfflineQueueRecord) (store.SyncEventRecord, error) {
	_ = ctx
	if record.MessageID != "" && record.ChatID != "" && isMutatingOperation(record.Operation) {
		existing, _ := s.Store.GetSyncEventVersion(record.ChatID, record.MessageID, record.Operation)
		accept, _ := ResolveConflict(record.Version, existing)
		if !accept {
			_ = s.Store.MarkSyncOfflineApplied(record.ID, 0)
			return store.SyncEventRecord{}, nil
		}
	}
	applied, err := s.Store.AppendSyncEvent(store.AppendSyncEventInput{
		ScopeUserID: record.UserID,
		ChatID:      record.ChatID,
		MessageID:   record.MessageID,
		Operation:   record.Operation,
		Version:     record.Version,
		Ciphertext:  record.Ciphertext,
		Metadata:    record.Metadata,
		ActorID:     record.UserID,
	})
	if err != nil {
		return store.SyncEventRecord{}, err
	}
	if err := s.Store.MarkSyncOfflineApplied(record.ID, applied.EventID); err != nil {
		return store.SyncEventRecord{}, err
	}
	return applied, nil
}

func isMutatingOperation(operation string) bool {
	switch operation {
	case "message.edited", "message.deleted", "reaction.added", "reaction.removed", "settings.updated":
		return true
	default:
		return false
	}
}

func mustDecode(raw string) []byte {
	data, _ := decodeB64(raw)
	return data
}

func mapStoreErr(err error) error {
	if errors.Is(err, store.ErrNotFound) {
		return ErrNotFound
	}
	return err
}

var ErrNotFound = errors.New("not found")