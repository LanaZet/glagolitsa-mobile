#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Smoke test: login -> refresh -> same refresh works -> wrong device rejected.
#
# Usage:
#   USERNAME=alice PASSWORD=secret ./scripts/test-auth-refresh.sh
#   BASE_URL=https://api.glagolit.me USERNAME=alice PASSWORD=secret DEVICE_ID=my-device ./scripts/test-auth-refresh.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-}"
PASSWORD="${PASSWORD:-}"
DEVICE_ID="${DEVICE_ID:-auth-refresh-smoke-$(date +%s)}"

log() { printf '[test-auth-refresh] %s\n' "$*"; }
fail() { printf '[test-auth-refresh] FAIL: %s\n' "$*" >&2; exit 1; }

[[ -n "$USERNAME" ]] || fail "USERNAME is required"
[[ -n "$PASSWORD" ]] || fail "PASSWORD is required"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

json_get() {
  local field="$1"
  python3 - "$field" <<'PY'
import json, sys
field = sys.argv[1]
data = json.load(sys.stdin)
value = data
for part in field.split("."):
    value = value[part]
print(value)
PY
}

write_login_body() {
  local out="$1"
  python3 - "$USERNAME" "$PASSWORD" "$DEVICE_ID" >"$out" <<'PY'
import json, sys
username, password, device_id = sys.argv[1:4]
print(json.dumps({"username": username, "password": password, "device_id": device_id}))
PY
}

write_refresh_body() {
  local refresh_token="$1"
  local device_id="$2"
  local out="$3"
  python3 - "$refresh_token" "$device_id" >"$out" <<'PY'
import json, sys
refresh_token, device_id = sys.argv[1:3]
print(json.dumps({"refresh_token": refresh_token, "device_id": device_id}))
PY
}

expect_status() {
  local label="$1"
  local got="$2"
  local want="$3"
  if [[ "$got" != "$want" ]]; then
    fail "$label: HTTP $got, want $want"
  fi
}

log "health $BASE_URL..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable at $BASE_URL"

LOGIN_BODY="$TMP_DIR/login.json"
LOGIN_JSON="$TMP_DIR/login-response.json"
write_login_body "$LOGIN_BODY"

log "login $USERNAME with device_id=$DEVICE_ID..."
curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  --data-binary "@$LOGIN_BODY" >"$LOGIN_JSON" || fail "login failed"

OLD_REFRESH="$(json_get refresh_token <"$LOGIN_JSON")"
OLD_SESSION="$(json_get session_id <"$LOGIN_JSON")"
[[ -n "$OLD_REFRESH" && -n "$OLD_SESSION" ]] || fail "login response missing refresh/session"

REFRESH_BODY="$TMP_DIR/refresh.json"
REFRESH_JSON="$TMP_DIR/refresh-response.json"
write_refresh_body "$OLD_REFRESH" "$DEVICE_ID" "$REFRESH_BODY"

log "refresh immediately..."
curl -sf -X POST "$BASE_URL/api/auth/refresh" \
  -H 'Content-Type: application/json' \
  --data-binary "@$REFRESH_BODY" >"$REFRESH_JSON" || fail "refresh failed"

NEW_REFRESH="$(json_get refresh_token <"$REFRESH_JSON")"
NEW_SESSION="$(json_get session_id <"$REFRESH_JSON")"
[[ -n "$NEW_REFRESH" && -n "$NEW_SESSION" ]] || fail "refresh response missing refresh/session"
[[ "$NEW_REFRESH" == "$OLD_REFRESH" ]] || fail "refresh token rotated unexpectedly"
[[ "$NEW_SESSION" == "$OLD_SESSION" ]] || fail "session id rotated unexpectedly"

log "same refresh token must still work..."
SAME_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/refresh" \
  -H 'Content-Type: application/json' \
  --data-binary "@$REFRESH_BODY")"
expect_status "same refresh token" "$SAME_CODE" "200"

WRONG_BODY="$TMP_DIR/refresh-wrong-device.json"
write_refresh_body "$NEW_REFRESH" "${DEVICE_ID}-wrong" "$WRONG_BODY"

log "wrong device_id must be rejected..."
WRONG_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/refresh" \
  -H 'Content-Type: application/json' \
  --data-binary "@$WRONG_BODY")"
expect_status "wrong device refresh" "$WRONG_CODE" "401"

log "OK"
