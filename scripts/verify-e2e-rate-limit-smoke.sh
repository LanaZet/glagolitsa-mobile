#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Smoke: relay queue poll не упирается в общий user rate limit (отдельный poll bucket).
# Запускать против локального API с RATE_LIMIT_RELAXED=false для строгой проверки.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

USERNAME="${E2E_RATE_LIMIT_USER:-$E2E_LOCAL_SENDER_USERNAME}"
PASSWORD="${E2E_RATE_LIMIT_PASSWORD:-$E2E_LOCAL_SENDER_PASSWORD}"
BASE_URL="${BASE_URL:-$E2E_LOCAL_BASE_URL}"
POLL_COUNT="${POLL_COUNT:-25}"

log() { printf '[verify-e2e-rate-limit] %s\n' "$*"; }
fail() { printf '[verify-e2e-rate-limit] FAIL: %s\n' "$*" >&2; exit 1; }

LOGIN_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")" || fail "login failed"
TOKEN="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
USER_ID="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"
DEVICE_ID="$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/users/$USER_ID/devices" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['device_id'] if d else '')")"
[[ -n "$DEVICE_ID" ]] || fail "no device_id"

ok=0
rate_limited=0
for _ in $(seq 1 "$POLL_COUNT"); do
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE_ID" \
    "$BASE_URL/api/messages/queue?limit=5")"
  case "$code" in
    200) ok=$((ok + 1)) ;;
    429) rate_limited=$((rate_limited + 1)) ;;
    *) fail "unexpected HTTP $code" ;;
  esac
done

log "polls ok=$ok rate_limited=$rate_limited / $POLL_COUNT"
[[ "$ok" -ge $((POLL_COUNT * 8 / 10)) ]] || fail "too many 429 on queue poll — check poll rate limit bucket"
log "PASS — queue poll bucket tolerates rapid polling"
