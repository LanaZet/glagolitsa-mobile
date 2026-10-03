#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Level 2 smoke: register → active, login без device → 403 chats, device → OK.
#
# Usage:
#   ./scripts/test-registration-flow.sh
#   BASE_URL=http://localhost:8080 ./scripts/test-registration-flow.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="${RUN_ID:-$(date +%s)}"
USERNAME="flow-${RUN_ID}"
PASSWORD="FlowTest2026!"

log() { printf '[test-registration-flow] %s\n' "$*"; }
fail() { printf '[test-registration-flow] FAIL: %s\n' "$*" >&2; exit 1; }

expect_status() {
  local label="$1"
  local got="$2"
  local want="$3"
  if [[ "$got" != "$want" ]]; then
    fail "$label: HTTP $got, want $want"
  fi
}

device_payload() {
  local device_id="$1"
  python3 - <<PY
import base64, json, uuid
key = base64.b64encode(bytes(range(32))).decode()
print(json.dumps({
  "device_id": "$device_id",
  "registration_id": 42,
  "identity_public_key": key,
  "signed_prekey": {"id": 1001, "public_key": key, "signature": key, "created_at": 1700000000000},
  "pq_prekey": {"id": 2001, "public_material": key, "signature": key, "created_at": 1700000000000},
  "one_time_prekeys": [{"id": 301, "public_key": key}],
}))
PY
}

log "health..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable at $BASE_URL"

register_body() {
  local user="$1"
  local pass="$2"
  python3 - <<PY
import hashlib, json, subprocess, sys, urllib.request

base = "$BASE_URL"
user = "$user"
password = "$pass"

def leading_zero_bits(data: bytes) -> int:
    bits = 0
    for b in data:
        if b == 0:
            bits += 8
            continue
        for shift in range(7, -1, -1):
            if b & (1 << shift) == 0:
                bits += 1
            else:
                return bits
    return bits

def solve(challenge: str, difficulty: int) -> str:
    for nonce in range(5_000_000):
        solution = str(nonce)
        digest = hashlib.sha256(f"{challenge}:{solution}".encode()).digest()
        if leading_zero_bits(digest) >= difficulty:
            return solution
    raise SystemExit("pow solve failed")

pow_data = json.load(urllib.request.urlopen(f"{base}/api/auth/pow"))
solution = solve(pow_data["challenge"], int(pow_data["difficulty"]))
body = {
    "username": user,
    "password": password,
    "email": f"{user}@example.com",
    "pow_challenge_id": pow_data["challenge_id"],
    "pow_solution": solution,
}
print(json.dumps(body))
PY
}

log "T1 register $USERNAME..."
REG_BODY="$(register_body "$USERNAME" "$PASSWORD")"
REG_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "$REG_BODY")" \
  || fail "register failed"
TOKEN="$(echo "$REG_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
USER_ID="$(echo "$REG_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"
[[ -n "$TOKEN" && -n "$USER_ID" ]] || fail "register response missing token/user"

if [[ -n "${DATABASE_URL:-}" ]]; then
  log "T1b account_status=active in Postgres..."
  STATUS="$(psql "$DATABASE_URL" -Atqc "SELECT account_status FROM users WHERE id='$USER_ID';")"
  [[ "$STATUS" == "active" ]] || fail "account_status=$STATUS, want active"
  DEV_COUNT="$(psql "$DATABASE_URL" -Atqc "SELECT COUNT(*) FROM devices WHERE account_id='$USER_ID';")"
  [[ "$DEV_COUNT" == "0" ]] || fail "expected 0 devices after register, got $DEV_COUNT"
fi

log "T2 login without device..."
LOGIN_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")" \
  || fail "login failed"
LOGIN_TOKEN="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

CHATS_CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $LOGIN_TOKEN" "$BASE_URL/api/chats")"
expect_status "GET /api/chats without device" "$CHATS_CODE" "403"

log "T3 security: wrong password → 401..."
BAD_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"wrong-$PASSWORD\"}")"
expect_status "login wrong password" "$BAD_CODE" "401"

log "T4 POST /api/devices..."
DEVICE_ID="device-${RUN_ID}"
PAYLOAD="$(device_payload "$DEVICE_ID")"
DEV_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/devices" \
  -H "Authorization: Bearer $LOGIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")"
expect_status "POST /api/devices" "$DEV_CODE" "201"

if [[ -n "${DATABASE_URL:-}" ]]; then
  STORED="$(psql "$DATABASE_URL" -Atqc "SELECT device_id FROM devices WHERE account_id='$USER_ID' LIMIT 1;")"
  [[ "$STORED" == "$DEVICE_ID" ]] || fail "device_id in DB = $STORED, want $DEVICE_ID"
fi

log "T5 GET /api/chats after device..."
CHATS_OK="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $LOGIN_TOKEN" "$BASE_URL/api/chats")"
expect_status "GET /api/chats with device" "$CHATS_OK" "200"

log "OK — registration/device flow passed for $USERNAME"