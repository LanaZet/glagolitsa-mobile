// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"context"
	"log/slog"
	"time"
)

type RetryWorker struct {
	Service *Service
	Config  Config
}

func NewRetryWorker(service *Service, cfg Config) *RetryWorker {
	return &RetryWorker{Service: service, Config: cfg}
}

func (w *RetryWorker) Run(ctx context.Context) {
	interval := time.Duration(w.Config.RetryIntervalSec) * time.Second
	if interval <= 0 {
		interval = 15 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.flushOnce(ctx)
		}
	}
}

func (w *RetryWorker) flushOnce(ctx context.Context) {
	records, err := w.Service.Store.ListSyncOfflineDue(50, time.Now().UTC())
	if err != nil {
		slog.Warn("sync_retry_list_failed", "error", err)
		return
	}
	for _, record := range records {
		if _, err := w.Service.ApplyOfflineRecord(ctx, record); err != nil {
			attempts := record.Attempts + 1
			delay := retryDelay(attempts)
			_ = w.Service.Store.MarkSyncOfflineRetry(record.ID, err.Error(), attempts, time.Now().UTC().Add(delay))
			if attempts >= w.Config.RetryMaxAttempts {
				slog.Warn("sync_retry_gave_up", "queue_id", record.ID, "attempts", attempts)
			}
			continue
		}
	}
}

func retryDelay(attempts int) time.Duration {
	seconds := []int{1, 2, 4, 8, 16, 30, 60}
	idx := attempts - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(seconds) {
		return time.Duration(seconds[len(seconds)-1]) * time.Second
	}
	return time.Duration(seconds[idx]) * time.Second
}