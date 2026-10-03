// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"context"
	"io"
	"log/slog"
)

// ScanSandbox inspects encrypted blobs only (opaque bytes). MVP marks as skipped.
type ScanSandbox struct {
	Enabled bool
}

func NewScanSandbox(cfg Config) ScanSandbox {
	return ScanSandbox{Enabled: cfg.ScanEnabled}
}

func (s ScanSandbox) Inspect(ctx context.Context, fileID string, reader io.Reader, size int64) string {
	_ = ctx
	if !s.Enabled {
		return ScanSkipped
	}
	// Future: stream ciphertext to isolated scanner without decryption.
	n, err := io.Copy(io.Discard, reader)
	if err != nil {
		slog.Warn("media_scan_read_failed", "file_id", fileID, "error", err)
		return ScanPending
	}
	if n != size && size > 0 {
		slog.Warn("media_scan_size_mismatch", "file_id", fileID, "expected", size, "read", n)
	}
	slog.Info("media_scan_complete", "file_id", fileID, "bytes", n, "mode", "encrypted_blob")
	return ScanClean
}