#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E smoke test: two clients register key bundles and exchange one.
#
# Usage:
#   ./scripts/test-key-bundle.sh
#   BASE_URL=http://localhost:8080 ./scripts/test-key-bundle.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-key-bundle] %s\n' "$*"; }
fail() { printf '[test-key-bundle] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  local user="$1"
  local pass="$2"
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable at $BASE_URL"

log "login $GLAGOLITSA_DEV_PRIMARY_USERNAME..."
TEST_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")" \
  || fail "$GLAGOLITSA_DEV_PRIMARY_USERNAME login failed"
TEST_TOKEN="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
TEST_ID="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"

log "login $GLAGOLITSA_DEV_SECONDARY_USERNAME..."
BOB_JSON="$(login "$GLAGOLITSA_DEV_SECONDARY_USERNAME" "$GLAGOLITSA_DEV_SECONDARY_PASSWORD")" \
  || fail "$GLAGOLITSA_DEV_SECONDARY_USERNAME login failed"
BOB_TOKEN="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
BOB_ID="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"

PAYLOAD="$(python3 - <<'PY'
import base64, json, uuid

def key(seed: int) -> str:
    return base64.b64encode(bytes((seed + i) % 256 for i in range(32))).decode()

def device_payload(device_id: str, registration_id: int, prekey_ids: list[int]) -> dict:
    return {
        "device_id": device_id,
        "registration_id": registration_id,
        "identity_public_key": key(1),
        "signed_prekey": {
            "id": 1001,
            "public_key": key(10),
            "signature": key(20),
            "created_at": 1700000000000,
        },
        "pq_prekey": {
            "id": 2001,
            "public_material": key(30),
            "signature": key(40),
            "created_at": 1700000000000,
        },
        "one_time_prekeys": [
            {"id": pid, "public_key": key(50 + pid)} for pid in prekey_ids
        ],
    }

bob_device = str(uuid.uuid4())
test_device = str(uuid.uuid4())
print(json.dumps({
    "bob_device": bob_device,
    "test_device": test_device,
    "bob_payload": device_payload(bob_device, 42, [301, 302, 303]),
    "test_payload": device_payload(test_device, 77, [401, 402]),
}))
PY
)"

BOB_DEVICE="$(echo "$PAYLOAD" | python3 -c "import sys,json; print(json.load(sys.stdin)['bob_device'])")"
TEST_DEVICE="$(echo "$PAYLOAD" | python3 -c "import sys,json; print(json.load(sys.stdin)['test_device'])")"
BOB_REGISTER="$(echo "$PAYLOAD" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['bob_payload']))")"
TEST_REGISTER="$(echo "$PAYLOAD" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['test_payload']))")"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME registers device $BOB_DEVICE..."
curl -sf -X POST "$BASE_URL/api/devices" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$BOB_REGISTER" >/dev/null || fail "$GLAGOLITSA_DEV_SECONDARY_USERNAME device registration failed"

log "$GLAGOLITSA_DEV_PRIMARY_USERNAME registers device $TEST_DEVICE..."
curl -sf -X POST "$BASE_URL/api/devices" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$TEST_REGISTER" >/dev/null || fail "$GLAGOLITSA_DEV_PRIMARY_USERNAME device registration failed"

log "$GLAGOLITSA_DEV_PRIMARY_USERNAME fetches $GLAGOLITSA_DEV_SECONDARY_USERNAME bundle..."
BOB_BUNDLE="$(curl -sf "$BASE_URL/api/devices/$BOB_DEVICE/bundle" \
  -H "Authorization: Bearer $TEST_TOKEN")" || fail "fetch $GLAGOLITSA_DEV_SECONDARY_USERNAME bundle failed"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME fetches $GLAGOLITSA_DEV_PRIMARY_USERNAME bundle..."
TEST_BUNDLE="$(curl -sf "$BASE_URL/api/devices/$TEST_DEVICE/bundle" \
  -H "Authorization: Bearer $BOB_TOKEN")" || fail "fetch $GLAGOLITSA_DEV_PRIMARY_USERNAME bundle failed"

python3 - <<PY
import json, sys

bob_bundle = json.loads('''$BOB_BUNDLE''')
test_bundle = json.loads('''$TEST_BUNDLE''')

assert bob_bundle["device_id"] == "$BOB_DEVICE"
assert bob_bundle["account_id"] == "$BOB_ID"
assert bob_bundle["registration_id"] == 42
assert bob_bundle["identity_public_key"]
assert bob_bundle["signed_prekey"]["public_key"]
assert bob_bundle["pq_prekey"]["public_material"]
assert bob_bundle["one_time_prekey"]["id"] == 301

assert test_bundle["device_id"] == "$TEST_DEVICE"
assert test_bundle["account_id"] == "$TEST_ID"
assert test_bundle["one_time_prekey"]["id"] == 401

print("bundle exchange OK")
PY

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME replenishes one-time prekeys..."
curl -sf -X POST "$BASE_URL/api/devices/$BOB_DEVICE/prekeys" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"one_time_prekeys":[{"id":304,"public_key":"'$(python3 -c "import base64; print(base64.b64encode(bytes(range(80,112))).decode())")'"}]}' >/dev/null \
  || fail "replenish prekeys failed"

log "$GLAGOLITSA_DEV_PRIMARY_USERNAME fetches $GLAGOLITSA_DEV_SECONDARY_USERNAME bundle again (consumes next prekey)..."
BOB_BUNDLE_2="$(curl -sf "$BASE_URL/api/devices/$BOB_DEVICE/bundle" \
  -H "Authorization: Bearer $TEST_TOKEN")" || fail "second bundle fetch failed"

echo "$BOB_BUNDLE_2" | python3 -c "
import sys, json
bundle = json.load(sys.stdin)
assert bundle['one_time_prekey']['id'] == 302, bundle
print('prekey consumption OK')
"

log "PASS: key bundle E2E smoke completed"
