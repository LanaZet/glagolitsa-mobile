// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"bufio"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

var errHijackCalled = errors.New("hijack called")

type hijackableResponseWriter struct {
	http.ResponseWriter
}

func (w hijackableResponseWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	return nil, nil, errHijackCalled
}

func TestResponseRecorderSupportsHijacker(t *testing.T) {
	recorder := &responseRecorder{
		ResponseWriter: hijackableResponseWriter{ResponseWriter: httptest.NewRecorder()},
	}

	_, _, err := recorder.Hijack()
	if !errors.Is(err, errHijackCalled) {
		t.Fatalf("expected wrapped hijacker to be called, got %v", err)
	}
	if got := recorder.finalStatus(); got != http.StatusSwitchingProtocols {
		t.Fatalf("expected switching protocols status, got %d", got)
	}
}

func TestShouldDebugLogRequest(t *testing.T) {
	tests := []struct {
		name   string
		path   string
		status int
		want   bool
	}{
		{
			name:   "queue success is debug only",
			path:   "/api/messages/queue",
			status: http.StatusOK,
			want:   true,
		},
		{
			name:   "heartbeat success is debug only",
			path:   "/api/presence/heartbeat",
			status: http.StatusOK,
			want:   true,
		},
		{
			name:   "queue client error stays visible",
			path:   "/api/messages/queue",
			status: http.StatusUnauthorized,
			want:   false,
		},
		{
			name:   "other success stays visible",
			path:   "/api/users/profile",
			status: http.StatusOK,
			want:   false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := shouldDebugLogRequest(tt.path, tt.status); got != tt.want {
				t.Fatalf("shouldDebugLogRequest() = %v, want %v", got, tt.want)
			}
		})
	}
}
