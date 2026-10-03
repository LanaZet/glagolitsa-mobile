#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Redis TTL relay queue smoke test (этап 6.2).
# Requires server started with REDIS_URL=redis://localhost:6379/0
#
# Usage:
#   REDIS_URL=redis://localhost:6379/0 ./scripts/test-redis-relay.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-redis-relay] %s\n' "$*"; }
fail() { printf '[test-redis-relay] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

b64_key() {
  python3 -c "import base64; print(base64.b64encode(bytes(($1 + i) % 256 for i in range(32))).decode())"
}

register_device() {
  local token="$1"
  local device_id="$2"
  local registration_id="$3"
  curl -sf -X POST "$BASE_URL/api/devices" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -d "{
      \"device_id\":\"$device_id\",
      \"registration_id\":$registration_id,
      \"identity_public_key\":\"$(b64_key 1)\",
      \"signed_prekey\":{\"id\":1001,\"public_key\":\"$(b64_key 10)\",\"signature\":\"$(b64_key 20)\",\"created_at\":1700000000000},
      \"pq_prekey\":{\"id\":2001,\"public_material\":\"$(b64_key 30)\",\"signature\":\"$(b64_key 40)\",\"created_at\":1700000000000},
      \"one_time_prekeys\":[{\"id\":301,\"public_key\":\"$(b64_key 50)\"}]
    }"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

TEST_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")"
BOB_JSON="$(login "$GLAGOLITSA_DEV_SECONDARY_USERNAME" "$GLAGOLITSA_DEV_SECONDARY_PASSWORD")"
TEST_TOKEN="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
BOB_TOKEN="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
BOB_ID="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"

TEST_DEVICE="$(python3 -c 'import uuid; print(uuid.uuid4())')"
BOB_DEVICE="$(python3 -c 'import uuid; print(uuid.uuid4())')"

register_device "$TEST_TOKEN" "$TEST_DEVICE" 42 >/dev/null
register_device "$BOB_TOKEN" "$BOB_DEVICE" 77 >/dev/null

BOB_DEVICES="$(curl -sf "$BASE_URL/api/users/$BOB_ID/devices" -H "Authorization: Bearer $TEST_TOKEN")"
BOB_MAILBOX="$(echo "$BOB_DEVICES" | python3 -c "
import sys, json
devices = json.load(sys.stdin)
device = next(d for d in devices if d['device_id'] == '$BOB_DEVICE')
print(device['mailbox_token'])
")"

CIPHERTEXT="$(python3 -c 'import base64; print(base64.b64encode(b"redis-queue-ciphertext").decode())')"

log "relay via redis-backed queue..."
RELAY_JSON="$(curl -sf -X POST "$BASE_URL/api/messages/relay" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H "X-Device-Id: $TEST_DEVICE" \
  -H 'Content-Type: application/json' \
  -d "{\"envelopes\":[{\"mailbox_token\":\"$BOB_MAILBOX\",\"envelope_type\":3,\"ciphertext\":\"$CIPHERTEXT\"}]}")"
ENVELOPE_ID="$(echo "$RELAY_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['envelope_ids'][0])")"

QUEUE_JSON="$(curl -sf "$BASE_URL/api/messages/queue?limit=10" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
echo "$QUEUE_JSON" | python3 -c "
import sys, json
ids = [e['envelope_id'] for e in json.load(sys.stdin)['envelopes']]
assert '$ENVELOPE_ID' in ids
print('redis queue fetch OK')
"

log "verify postgres queue table stays empty (redis backend)..."
COUNT="$(docker exec server-postgres-1 psql -U glagolitsa -d glagolitsa -tAc "SELECT COUNT(*) FROM messages_queue WHERE envelope_id = '$ENVELOPE_ID'" 2>/dev/null || echo 0)"
[[ "$COUNT" == "0" ]] || fail "envelope leaked into postgres queue: count=$COUNT"

log "PASS: redis relay queue smoke completed"
