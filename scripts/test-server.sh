#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Go server unit/integration tests (monorepo server/, no Maestro, no live network).
#
#   ./scripts/test-server.sh
#   SERVER_DIR=/path/to/glagolitsa-mobile/server ./scripts/test-server.sh
#   SKIP_SERVER_TESTS=1 ./scripts/test-server.sh   # no-op success
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"

log() { printf '[test-server] %s\n' "$*"; }
fail() { printf '[test-server] FAIL: %s\n' "$*" >&2; exit 1; }

if [[ "${SKIP_SERVER_TESTS:-}" == "1" ]]; then
  log "SKIP_SERVER_TESTS=1 — skipped"
  exit 0
fi

if [[ ! -f "$SERVER_DIR/go.mod" ]]; then
  fail "server not found at $SERVER_DIR (set SERVER_DIR or clone glagolitsa next to this repo)"
fi
if ! command -v go >/dev/null 2>&1; then
  fail "go not on PATH"
fi

log "go test ./... in $SERVER_DIR"
(
  cd "$SERVER_DIR"
  go test ./... -count=1
)
log "OK — server tests passed"
