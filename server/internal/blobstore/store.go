// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package blobstore

import (
	"context"
	"io"
)

type Store interface {
	Put(ctx context.Context, objectKey string, reader io.Reader, size int64, contentType string) error
	Get(ctx context.Context, objectKey string) (io.ReadCloser, error)
	Delete(ctx context.Context, objectKey string) error
}