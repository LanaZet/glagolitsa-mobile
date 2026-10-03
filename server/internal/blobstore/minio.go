// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package blobstore

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"os"
	"strings"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type MinioConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Bucket    string
	UseSSL    bool
}

func MinioConfigFromEnv() MinioConfig {
	useSSL := strings.EqualFold(os.Getenv("MINIO_USE_SSL"), "true")
	return MinioConfig{
		Endpoint:  envOrDefault("MINIO_ENDPOINT", "localhost:9000"),
		AccessKey: envOrDefault("MINIO_ACCESS_KEY", "glagolitsa"),
		SecretKey: envOrDefault("MINIO_SECRET_KEY", "glagolitsa-secret"),
		Bucket:    envOrDefault("MINIO_BUCKET", "glagolitsa-attachments"),
		UseSSL:    useSSL,
	}
}

func NewMinio(cfg MinioConfig) (Store, error) {
	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.UseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}
	return &minioStore{client: client, bucket: cfg.Bucket}, nil
}

type minioStore struct {
	client *minio.Client
	bucket string
}

func (s *minioStore) Put(ctx context.Context, objectKey string, reader io.Reader, size int64, contentType string) error {
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	_, err := s.client.PutObject(ctx, s.bucket, objectKey, reader, size, minio.PutObjectOptions{
		ContentType: contentType,
	})
	return err
}

func (s *minioStore) Get(ctx context.Context, objectKey string) (io.ReadCloser, error) {
	object, err := s.client.GetObject(ctx, s.bucket, objectKey, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	if _, err := object.Stat(); err != nil {
		_ = object.Close()
		return nil, err
	}
	return object, nil
}

func (s *minioStore) Delete(ctx context.Context, objectKey string) error {
	return s.client.RemoveObject(ctx, s.bucket, objectKey, minio.RemoveObjectOptions{})
}

func ParseMinioEndpoint(raw string) (host string, secure bool, err error) {
	if strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://") {
		parsed, parseErr := url.Parse(raw)
		if parseErr != nil {
			return "", false, parseErr
		}
		return parsed.Host, parsed.Scheme == "https", nil
	}
	return raw, false, nil
}

func envOrDefault(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}