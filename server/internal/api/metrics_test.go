// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMetricsRouteLabelUsesServeMuxPattern(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/health", nil)
	req.Pattern = "GET /api/health"

	if got := metricsRouteLabel(req, http.StatusOK); got != "GET /api/health" {
		t.Fatalf("metricsRouteLabel() = %q, want route pattern", got)
	}
}

func TestMetricsRouteLabelCollapsesUnknownPaths(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/.env", nil)

	if got := metricsRouteLabel(req, http.StatusNotFound); got != "unmatched" {
		t.Fatalf("metricsRouteLabel() = %q, want unmatched", got)
	}
}
