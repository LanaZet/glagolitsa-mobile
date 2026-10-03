// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"context"
	"log/slog"
	"time"

	"glagolitsa/server/internal/blobstore"
)

type RetentionWorker struct {
	Store  Store
	Blobs  blobstore.Store
	Config Config
}

func NewRetentionWorker(store Store, blobs blobstore.Store, cfg Config) *RetentionWorker {
	return &RetentionWorker{Store: store, Blobs: blobs, Config: cfg}
}

func (w *RetentionWorker) Run(ctx context.Context) {
	if w.Store == nil || w.Blobs == nil {
		return
	}
	interval := w.Config.RetentionEvery
	if interval <= 0 {
		interval = 15 * time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	w.purgeOnce(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.purgeOnce(ctx)
		}
	}
}

func (w *RetentionWorker) purgeOnce(ctx context.Context) {
	records, err := w.Store.ListExpiredFiles(time.Now().UTC(), w.Config.RetentionBatch)
	if err != nil {
		slog.Warn("media_retention_list_failed", "error", err)
		return
	}
	for _, record := range records {
		if record.StoragePath != "" {
			if err := w.Blobs.Delete(ctx, record.StoragePath); err != nil {
				slog.Warn("media_retention_blob_delete_failed", "file_id", record.FileID, "error", err)
			}
		}
		if err := w.Store.PurgeFile(record.FileID); err != nil {
			slog.Warn("media_retention_metadata_delete_failed", "file_id", record.FileID, "error", err)
			continue
		}
		slog.Info("media_retention_purged", "file_id", record.FileID, "kind", record.Kind)
	}
}