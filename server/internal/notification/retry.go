// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"log/slog"
	"time"
)

// RetryWorker — backoff queue for failed pushes.
type RetryWorker struct {
	service  *Service
	interval time.Duration
}

func NewRetryWorker(service *Service) *RetryWorker {
	return &RetryWorker{service: service, interval: 15 * time.Second}
}

func (w *RetryWorker) Run(ctx context.Context) {
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.flush(ctx)
		}
	}
}

func (w *RetryWorker) flush(ctx context.Context) {
	jobs, err := w.service.Store.ListDueRetries(20, time.Now().UTC())
	if err != nil {
		slog.Warn("notification_retry_list_failed", "error", err)
		return
	}
	for _, job := range jobs {
		err := w.service.Send(ctx, SendRequest{
			UserID:   job.UserID,
			Type:     job.NotificationType,
			Payload:  job.Payload,
			Priority: job.Priority,
		})
		if err != nil {
			attempts := job.Attempts + 1
			if attempts >= job.MaxAttempts {
				_ = w.service.Store.CompleteRetry(job.ID)
				slog.Warn("notification_retry_exhausted", "job_id", job.ID, "user_id", job.UserID)
				continue
			}
			delay := time.Duration(attempts*attempts) * 30 * time.Second
			_ = w.service.Store.UpdateRetryAttempt(job.ID, attempts, time.Now().UTC().Add(delay), err.Error())
			continue
		}
		_ = w.service.Store.CompleteRetry(job.ID)
	}
}