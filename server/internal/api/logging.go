// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"bufio"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/logx"
)

type responseRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *responseRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (r *responseRecorder) Write(data []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	n, err := r.ResponseWriter.Write(data)
	r.bytes += n
	return n, err
}

func (r *responseRecorder) ReadFrom(src io.Reader) (int64, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	if readerFrom, ok := r.ResponseWriter.(io.ReaderFrom); ok {
		n, err := readerFrom.ReadFrom(src)
		r.bytes += int(n)
		return n, err
	}
	n, err := io.Copy(r.ResponseWriter, src)
	r.bytes += int(n)
	return n, err
}

func (r *responseRecorder) Flush() {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	if flusher, ok := r.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func (r *responseRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hijacker, ok := r.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, http.ErrNotSupported
	}
	r.status = http.StatusSwitchingProtocols
	return hijacker.Hijack()
}

func (r *responseRecorder) Push(target string, opts *http.PushOptions) error {
	pusher, ok := r.ResponseWriter.(http.Pusher)
	if !ok {
		return http.ErrNotSupported
	}
	return pusher.Push(target, opts)
}

func (r *responseRecorder) Unwrap() http.ResponseWriter {
	return r.ResponseWriter
}

func (r *responseRecorder) finalStatus() int {
	if r.status == 0 {
		return http.StatusOK
	}
	return r.status
}

func (h *Handler) withRequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		recorder := &responseRecorder{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.finalStatus()
		durationMs := time.Since(start).Milliseconds()

		attrs := []any{
			"method", r.Method,
			"path", r.URL.Path,
			"status", status,
			"duration_ms", durationMs,
			"response_bytes", recorder.bytes,
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			attrs = append(attrs, "user_id", claims.UserID)
		}
		if r.Context().Err() != nil {
			attrs = append(attrs, "client_gone", true)
		}
		if isSensitiveRoute(r.URL.Path) {
			attrs = append(attrs, logx.RedactedField("payload").Key, "[redacted]")
		}
		// Surface hung / slow call control requests for ops (create used to vanish before 201 log).
		if durationMs >= 5_000 && strings.HasPrefix(r.URL.Path, "/api/calls") {
			slog.Warn("http_request_slow", attrs...)
		}
		if shouldDebugLogRequest(r.URL.Path, status) {
			slog.Debug("http_request", attrs...)
			return
		}
		if status >= http.StatusInternalServerError {
			slog.Warn("http_request", attrs...)
			return
		}
		slog.Info("http_request", attrs...)
	})
}

func shouldDebugLogRequest(path string, status int) bool {
	if status >= http.StatusBadRequest {
		return false
	}
	switch path {
	case "/api/messages/queue", "/api/presence/heartbeat":
		return true
	default:
		return false
	}
}

func isSensitiveRoute(path string) bool {
	switch {
	case strings.HasPrefix(path, "/api/messages/relay"),
		strings.HasPrefix(path, "/api/messages/queue"),
		strings.HasPrefix(path, "/api/attachments/"),
		strings.HasPrefix(path, "/api/media/files/"),
		strings.HasPrefix(path, "/api/sync/events"),
		strings.HasPrefix(path, "/api/sync/delta"),
		strings.HasPrefix(path, "/api/sync/push"),
		strings.HasPrefix(path, "/api/sync/snapshot"):
		return true
	default:
		return false
	}
}
