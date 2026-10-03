#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Sync Service: device registration, delta events, ack.
#
# Usage:
#   ./scripts/test-sync.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"
SYNC_DEVICE_ID="${SYNC_DEVICE_ID:-${GLAGOLITSA_DEV_PRIMARY_USERNAME}-phone}"

log() { printf '[test-sync] %s\n' "$*"; }
fail() { printf '[test-sync] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

TEST_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")"
TEST_TOKEN="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

log "register sync device..."
curl -sf -X POST "$BASE_URL/api/sync/device" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"device_id\":\"$SYNC_DEVICE_ID\",\"sync_profile\":\"balanced\",\"battery_pct\":80}" >/dev/null

log "create group to emit events..."
curl -sf -X POST "$BASE_URL/api/groups" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Sync Test Group"}' >/dev/null

log "fetch delta events..."
EVENTS_JSON="$(curl -sf "$BASE_URL/api/sync/events?after_event_id=0&limit=50" \
  -H "Authorization: Bearer $TEST_TOKEN")"
COUNT="$(echo "$EVENTS_JSON" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['events']))")"
[[ "$COUNT" -ge 1 ]] || fail "expected sync events, got $COUNT"

LATEST="$(echo "$EVENTS_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['latest_event_id'])")"

log "ack events..."
curl -sf -X POST "$BASE_URL/api/sync/ack" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"device_id\":\"$SYNC_DEVICE_ID\",\"last_event_id\":$LATEST}" -o /dev/null -w '%{http_code}' | grep -q '204' \
  || fail "ack failed"

log "legacy sync endpoint..."
curl -sf "$BASE_URL/api/sync" -H "Authorization: Bearer $TEST_TOKEN" >/dev/null

log "PASS: sync service E2E completed"
