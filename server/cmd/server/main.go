// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"glagolitsa/server/internal/api"
	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/blobstore"
	"glagolitsa/server/internal/logx"
	"glagolitsa/server/internal/metrics"
	"glagolitsa/server/internal/queue"
	"glagolitsa/server/internal/store"
)

func main() {
	_ = logx.Init()
	auth.MustProductionSecret()
	addr := envOrDefault("ADDR", ":8080")
	frontendOrigin := envOrDefault("FRONTEND_ORIGIN", "http://localhost:5173")
	databaseURL := envOrDefault(
		"DATABASE_URL",
		"postgres://glagolitsa:glagolitsa@localhost:5432/glagolitsa?sslmode=disable",
	)

	ctx := context.Background()
	postgresStore, err := store.NewPostgres(ctx, databaseURL)
	if err != nil {
		slog.Error("postgres_init_failed", "error", err)
		os.Exit(1)
	}
	defer postgresStore.Close()

	var relayCloser func() error
	if redisURL := os.Getenv("REDIS_URL"); redisURL != "" {
		relayQueue, err := queue.NewRedisRelayQueue(redisURL, queue.DefaultEnvelopeTTL)
		if err != nil {
			slog.Error("redis_init_failed", "error", err)
			os.Exit(1)
		}
		postgresStore.SetRelayQueue(relayQueue)
		relayCloser = relayQueue.Close
		slog.Info("relay_queue_backend", "backend", "redis")
	} else {
		slog.Info("relay_queue_backend", "backend", "postgres")
	}
	if relayCloser != nil {
		defer func() {
			if err := relayCloser(); err != nil {
				slog.Warn("relay_queue_close_failed", "error", err)
			}
		}()
	}

	var blobStore blobstore.Store
	blobBackend := strings.ToLower(strings.TrimSpace(os.Getenv("BLOBSTORE_BACKEND")))
	minioEnabled := envOrDefault("MINIO_ENABLED", "true")
	if blobBackend == "filesystem" || blobBackend == "file" || blobBackend == "local" || strings.EqualFold(minioEnabled, "false") {
		fileStore, err := blobstore.NewFileSystem(blobstore.FileSystemConfigFromEnv())
		if err != nil {
			slog.Error("filesystem_blobstore_init_failed", "error", err)
			os.Exit(1)
		}
		blobStore = fileStore
		slog.Info("blob_store_backend", "backend", "filesystem", "dir", blobstore.FileSystemConfigFromEnv().RootDir)
	} else if os.Getenv("MINIO_ENDPOINT") != "" || minioEnabled == "true" {
		minioStore, err := blobstore.NewMinio(blobstore.MinioConfigFromEnv())
		if err != nil {
			slog.Warn("minio_init_failed", "error", err)
		} else {
			blobStore = minioStore
			slog.Info("blob_store_backend", "backend", "minio", "bucket", blobstore.MinioConfigFromEnv().Bucket)
		}
	}

	handler := api.NewHandler(api.Options{
		FrontendOrigin: frontendOrigin,
		Store:          postgresStore,
		Blobs:          blobStore,
		RateLimiter:    api.NewRateLimiterFromEnv(),
		RedisURL:       os.Getenv("REDIS_URL"),
		MetricsEnabled: metrics.EnabledFromEnv(envOrDefault("METRICS_ENABLED", "true")),
	})

	server := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		// ReadTimeout covers request body (media uploads). Hijacked WS is not affected.
		ReadTimeout: 5 * time.Minute,
		// Cap write stalls from half-open mobile clients (create used to never log 201).
		// Still long enough for modest media responses; large streams should chunk.
		WriteTimeout:   2 * time.Minute,
		IdleTimeout:    90 * time.Second,
		MaxHeaderBytes: 1 << 20,
	}

	errCh := make(chan error, 1)
	go func() {
		slog.Info("server_listening", "addr", addr, "database", "postgres")
		errCh <- server.ListenAndServe()
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return
		}
		slog.Error("server_stopped", "error", err)
		os.Exit(1)
	case sig := <-stop:
		slog.Info("server_shutdown_started", "signal", sig.String())
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			slog.Error("server_shutdown_failed", "error", err)
			os.Exit(1)
		}
		slog.Info("server_shutdown_complete")
	}
}

func envOrDefault(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
