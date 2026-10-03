// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type Store interface {
	AppendSyncEvent(input store.AppendSyncEventInput) (store.SyncEventRecord, error)
	ListSyncEvents(userID string, afterEventID int64, limit int) ([]store.SyncEventRecord, error)
	GetLatestSyncEventID(userID string) (int64, error)
	RegisterSyncDevice(userID string, input store.RegisterSyncDeviceInput) (store.SyncDeviceRecord, error)
	GetSyncDevice(userID, deviceID string) (store.SyncDeviceRecord, error)
	AckSyncEvents(userID, deviceID string, lastEventID int64) error
	SaveSyncSnapshot(userID string, eventID int64, data []byte) (store.SyncSnapshotRecord, error)
	GetLatestSyncSnapshot(userID string) (store.SyncSnapshotRecord, error)
	EnqueueSyncOffline(userID string, input store.EnqueueSyncEventInput) (store.SyncOfflineQueueRecord, error)
	ListSyncOfflineDue(limit int, before time.Time) ([]store.SyncOfflineQueueRecord, error)
	MarkSyncOfflineApplied(id string, eventID int64) error
	MarkSyncOfflineRetry(id, lastError string, attempts int, nextRetry time.Time) error
	GetSyncEventVersion(chatID, messageID, operation string) (int, error)
	ListChatsForUser(userID string) ([]model.Chat, error)
	ListChatsUpdatedSince(userID string, since time.Time) ([]model.Chat, error)
	ListChatEvents(userID string, since time.Time, limit int) ([]model.ChatEvent, error)
}