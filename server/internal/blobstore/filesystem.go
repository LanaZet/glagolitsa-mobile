// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package blobstore

import (
	"context"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

type FileSystemConfig struct {
	RootDir string
}

func FileSystemConfigFromEnv() FileSystemConfig {
	return FileSystemConfig{
		RootDir: envOrDefault("BLOBSTORE_DIR", "data/blobs"),
	}
}

func NewFileSystem(cfg FileSystemConfig) (Store, error) {
	root := strings.TrimSpace(cfg.RootDir)
	if root == "" {
		return nil, fmt.Errorf("blobstore root dir is required")
	}
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(absRoot, 0o750); err != nil {
		return nil, err
	}
	return &fileSystemStore{root: absRoot}, nil
}

type fileSystemStore struct {
	root string
}

func (s *fileSystemStore) Put(ctx context.Context, objectKey string, reader io.Reader, size int64, contentType string) error {
	_ = contentType
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	target, err := s.pathFor(objectKey)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		return err
	}

	tmp, err := os.CreateTemp(filepath.Dir(target), ".upload-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpName)
		}
	}()

	written, copyErr := io.Copy(tmp, reader)
	closeErr := tmp.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if size >= 0 && written != size {
		return fmt.Errorf("blob size mismatch: wrote %d bytes, expected %d", written, size)
	}
	if err := os.Chmod(tmpName, 0o640); err != nil {
		return err
	}
	if err := os.Rename(tmpName, target); err != nil {
		return err
	}
	cleanup = false
	return nil
}

func (s *fileSystemStore) Get(ctx context.Context, objectKey string) (io.ReadCloser, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}
	target, err := s.pathFor(objectKey)
	if err != nil {
		return nil, err
	}
	return os.Open(target)
}

func (s *fileSystemStore) Delete(ctx context.Context, objectKey string) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}
	target, err := s.pathFor(objectKey)
	if err != nil {
		return err
	}
	if err := os.Remove(target); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (s *fileSystemStore) pathFor(objectKey string) (string, error) {
	key := strings.TrimSpace(objectKey)
	for _, part := range strings.Split(key, "/") {
		if part == ".." {
			return "", fmt.Errorf("invalid blob object key")
		}
	}
	clean := path.Clean("/" + key)
	if clean == "/" || strings.HasPrefix(clean, "/../") || clean == "/.." {
		return "", fmt.Errorf("invalid blob object key")
	}
	rel := strings.TrimPrefix(clean, "/")
	target := filepath.Join(s.root, filepath.FromSlash(rel))
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return "", err
	}
	rootWithSep := s.root + string(os.PathSeparator)
	if absTarget != s.root && !strings.HasPrefix(absTarget, rootWithSep) {
		return "", fmt.Errorf("invalid blob object key")
	}
	return absTarget, nil
}
