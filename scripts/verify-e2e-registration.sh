#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Post-E2E API checks: active path, device_id, searchable by another user; затем cleanup.
#
#   E2E_USERNAME=testbcdef PASSWORD='FlowTest2026!' ./scripts/verify-e2e-registration.sh
#   SKIP_E2E_CLEANUP=1 ...  # не удалять тестового пользователя
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

E2E_USERNAME="${E2E_USERNAME:-${MAESTRO_USERNAME:-${USERNAME:-}}}"
PASSWORD="${PASSWORD:?set PASSWORD}"
[[ -n "$E2E_USERNAME" ]] || { echo "set E2E_USERNAME" >&2; exit 1; }
VERIFY_USERNAME="${VERIFY_USERNAME:-$E2E_DEFAULT_VERIFY_USERNAME}"
VERIFY_PASSWORD="${VERIFY_PASSWORD:-$E2E_DEFAULT_VERIFY_PASSWORD}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"

e2e_require_vars BASE_URL VERIFY_USERNAME VERIFY_PASSWORD

log() { printf '[verify-e2e-registration] %s\n' "$*"; }
fail() { printf '[verify-e2e-registration] FAIL: %s\n' "$*" >&2; exit 1; }

expect_status() {
  local label="$1" got="$2" want="$3"
  [[ "$got" == "$want" ]] || fail "$label: HTTP $got, want $want"
}

log "health..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable at $BASE_URL"

log "E1 login as new user $E2E_USERNAME..."
LOGIN_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$E2E_USERNAME\",\"password\":\"$PASSWORD\"}")" \
  || fail "login failed for $E2E_USERNAME"
TOKEN="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
USER_ID="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"
[[ -n "$TOKEN" && -n "$USER_ID" ]] || fail "login response missing token/user"

log "E2 GET /api/chats (device gate)..."
CHATS_CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/chats")"
expect_status "GET /api/chats" "$CHATS_CODE" "200"

log "E3 GET /api/users/$USER_ID/devices (device_id)..."
DEVICES_JSON="$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/users/$USER_ID/devices")" \
  || fail "devices list failed"
echo "$DEVICES_JSON" | python3 -c '
import json, sys
devices = json.load(sys.stdin)
if not devices:
    sys.exit("devices list empty")
device_id = (devices[0].get("device_id") or "").strip()
if not device_id:
    sys.exit("device_id missing in first device")
print(f"device_id={device_id}")
' || fail "no device_id for $E2E_USERNAME"

log "E4 search as $VERIFY_USERNAME..."
VERIFY_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$VERIFY_USERNAME\",\"password\":\"$VERIFY_PASSWORD\"}")" \
  || fail "verifier login failed"
VERIFY_TOKEN="$(echo "$VERIFY_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

SEARCH_JSON="$(curl -sf -H "Authorization: Bearer $VERIFY_TOKEN" \
  "$BASE_URL/api/users/search?q=${E2E_USERNAME}")" \
  || fail "search failed"

echo "$SEARCH_JSON" | E2E_USERNAME="$E2E_USERNAME" python3 -c '
import json, os, sys
username = os.environ["E2E_USERNAME"].lower()
users = json.load(sys.stdin)
found = any((u.get("username") or "").lower() == username for u in users)
if not found:
    print("search returned:", [u.get("username") for u in users], file=sys.stderr)
    sys.exit(1)
print(f"found @{username} in search ({len(users)} hit(s))")
' || fail "user $E2E_USERNAME not found in search"

if [[ -n "${DATABASE_URL:-}" ]]; then
  log "E5 account_status=active in Postgres..."
  STATUS="$(psql "$DATABASE_URL" -Atqc "SELECT account_status FROM users WHERE id='$USER_ID';")"
  [[ "$STATUS" == "active" ]] || fail "account_status=$STATUS, want active"
fi

log "OK — $E2E_USERNAME active, has device, searchable"

log "E6 cleanup test user..."
E2E_USERNAME="$E2E_USERNAME" E2E_USER_ID="$USER_ID" \
  bash "$ROOT/scripts/cleanup-e2e-user.sh"
