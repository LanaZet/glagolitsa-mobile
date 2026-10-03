// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package blobstore

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"testing"
)

func TestFileSystemStorePutGetDelete(t *testing.T) {
	store, err := NewFileSystem(FileSystemConfig{RootDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	payload := []byte("encrypted payload")
	if err := store.Put(ctx, "media/photo/file-1", bytes.NewReader(payload), int64(len(payload)), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}

	reader, err := store.Get(ctx, "media/photo/file-1")
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(reader)
	_ = reader.Close()
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("unexpected payload: %q", got)
	}

	if err := store.Delete(ctx, "media/photo/file-1"); err != nil {
		t.Fatal(err)
	}
	_, err = store.Get(ctx, "media/photo/file-1")
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected missing blob, got %v", err)
	}
}

func TestFileSystemStoreRejectsTraversal(t *testing.T) {
	store, err := NewFileSystem(FileSystemConfig{RootDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}

	err = store.Put(context.Background(), "../outside", bytes.NewReader([]byte("x")), 1, "")
	if err == nil {
		t.Fatal("expected traversal key to fail")
	}
}
