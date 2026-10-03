#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Messaging proof without Maestro UI e2e.
#
# Default (deterministic PR-style):
#   ./scripts/test-messaging.sh
#     → unit (shared) + server (go test)
#
# Optional live API (no UI — login/devices/relay/queue/ack):
#   ./scripts/test-messaging.sh --live-local
#   ./scripts/test-messaging.sh --live-public
#   ./scripts/test-messaging.sh --all          # unit + server + local + public
#
# Env:
#   SERVER_DIR, SKIP_SERVER_TESTS, BASE_URL, SENDER_*/RECIPIENT_* (see e2e-defaults.sh)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

RUN_UNIT=1
RUN_SERVER=1
RUN_LIVE_LOCAL=0
RUN_LIVE_PUBLIC=0

usage() {
  cat <<'EOF'
Usage: ./scripts/test-messaging.sh [flags]

  (default)         unit + server
  --unit-only       only ./scripts/test-unit.sh
  --server-only     only ./scripts/test-server.sh
  --live-local      also live API vs local docker defaults (Marco→Polo)
  --live-public     also live API vs public accounts from env
  --all             unit + server + live-local + live-public
  --skip-unit       skip client unit
  --skip-server     skip go server tests
  -h, --help        this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --unit-only) RUN_UNIT=1; RUN_SERVER=0; RUN_LIVE_LOCAL=0; RUN_LIVE_PUBLIC=0; shift ;;
    --server-only) RUN_UNIT=0; RUN_SERVER=1; RUN_LIVE_LOCAL=0; RUN_LIVE_PUBLIC=0; shift ;;
    --live-local) RUN_LIVE_LOCAL=1; shift ;;
    --live-public) RUN_LIVE_PUBLIC=1; shift ;;
    --all) RUN_UNIT=1; RUN_SERVER=1; RUN_LIVE_LOCAL=1; RUN_LIVE_PUBLIC=1; shift ;;
    --skip-unit) RUN_UNIT=0; shift ;;
    --skip-server) RUN_SERVER=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      printf 'unknown flag: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

log() { printf '[test-messaging] %s\n' "$*"; }
fail() { printf '[test-messaging] FAIL: %s\n' "$*" >&2; exit 1; }

run_live() {
  local label="$1"
  shift
  log "live API path ($label)..."
  env "$@" bash "$ROOT/scripts/verify-public-messaging-path.sh" \
    || fail "live API path failed ($label)"
}

log "start (unit=$RUN_UNIT server=$RUN_SERVER live_local=$RUN_LIVE_LOCAL live_public=$RUN_LIVE_PUBLIC)"

if [[ "$RUN_UNIT" == "1" ]]; then
  log "1) client unit"
  bash "$ROOT/scripts/test-unit.sh"
fi

if [[ "$RUN_SERVER" == "1" ]]; then
  log "2) server go test"
  bash "$ROOT/scripts/test-server.sh"
fi

if [[ "$RUN_LIVE_LOCAL" == "1" ]]; then
  run_live "local" \
    BASE_URL="${BASE_URL:-$E2E_LOCAL_BASE_URL}" \
    SENDER_USERNAME="${SENDER_USERNAME:-$E2E_LOCAL_SENDER_USERNAME}" \
    SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_LOCAL_SENDER_PASSWORD}" \
    RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_LOCAL_RECIPIENT_USERNAME}" \
    RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_LOCAL_RECIPIENT_PASSWORD}"
fi

if [[ "$RUN_LIVE_PUBLIC" == "1" ]]; then
  # Explicit public defaults; do not inherit accidental local BASE_URL from env unless set.
  run_live "public" \
    BASE_URL="${PUBLIC_BASE_URL:-${BASE_URL:-$E2E_DEFAULT_BASE_URL}}" \
    SENDER_USERNAME="${PUBLIC_SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}" \
    SENDER_PASSWORD="${PUBLIC_SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}" \
    RECIPIENT_USERNAME="${PUBLIC_RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}" \
    RECIPIENT_PASSWORD="${PUBLIC_RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
fi

log "PASS — messaging checks complete (no Maestro)"
