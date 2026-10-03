#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Metadata v1 smoke test: sealed-sender queue (no sender graph in SQL/API).
#
# Usage:
#   ./scripts/test-sealed-relay.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-sealed-relay] %s\n' "$*"; }
fail() { printf '[test-sealed-relay] FAIL: %s\n' "$*" >&2; exit 1; }

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
[[ "$BOB_MAILBOX" != "$BOB_DEVICE" ]] || fail "mailbox_token must not equal device_id"

PAIRWISE_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
# 16-byte pairwise prefix + fake libsignal ciphertext (opaque to server).
CIPHERTEXT="$(python3 -c "
import base64, uuid
pid = uuid.UUID('$PAIRWISE_ID').bytes
print(base64.b64encode(pid + b'fake-signal-ciphertext').decode())
")"

log "relay sealed envelope (no chat_id / sender metadata)..."
RELAY_JSON="$(curl -sf -X POST "$BASE_URL/api/messages/relay" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H "X-Device-Id: $TEST_DEVICE" \
  -H 'Content-Type: application/json' \
  -d "{
    \"pairwise_id\":\"$PAIRWISE_ID\",
    \"client_message_id\":\"pending-sealed-1\",
    \"envelopes\":[{\"mailbox_token\":\"$BOB_MAILBOX\",\"envelope_type\":3,\"ciphertext\":\"$CIPHERTEXT\"}]
  }")"
ENVELOPE_ID="$(echo "$RELAY_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['envelope_ids'][0])")"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME polls queue — response must not expose sender graph..."
QUEUE_JSON="$(curl -sf "$BASE_URL/api/messages/queue?limit=10" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
echo "$QUEUE_JSON" | python3 -c "
import sys, json
queue = json.load(sys.stdin)
ids = [e['envelope_id'] for e in queue['envelopes']]
assert '$ENVELOPE_ID' in ids
item = next(e for e in queue['envelopes'] if e['envelope_id'] == '$ENVELOPE_ID')
for forbidden in ('sender_account_id', 'sender_device_id', 'chat_id', 'client_message_id'):
    assert forbidden not in item, f'server leaked {forbidden}'
assert item['ciphertext'] == '$CIPHERTEXT'
assert item['mailbox_token'] == '$BOB_MAILBOX'
print('sealed queue fetch OK')
"

log "mailbox rotation with 24h grace..."
ROTATE_JSON="$(curl -sf -X POST "$BASE_URL/api/devices/$BOB_DEVICE/mailbox/rotate" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
NEW_MAILBOX="$(echo "$ROTATE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['mailbox_token'])")"
PREV_MAILBOX="$(echo "$ROTATE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['previous_mailbox_token'])")"
[[ "$PREV_MAILBOX" == "$BOB_MAILBOX" ]] || fail "previous mailbox token mismatch"

RELAY2="$(curl -sf -X POST "$BASE_URL/api/messages/relay" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H "X-Device-Id: $TEST_DEVICE" \
  -H 'Content-Type: application/json' \
  -d "{
    \"envelopes\":[{\"mailbox_token\":\"$PREV_MAILBOX\",\"envelope_type\":3,\"ciphertext\":\"$CIPHERTEXT\"}]
  }")"
ENVELOPE2="$(echo "$RELAY2" | python3 -c "import sys,json; print(json.load(sys.stdin)['envelope_ids'][0])")"

QUEUE_GRACE="$(curl -sf "$BASE_URL/api/messages/queue?limit=10" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
echo "$QUEUE_GRACE" | python3 -c "
import sys, json
ids = [e['envelope_id'] for e in json.load(sys.stdin)['envelopes']]
assert '$ENVELOPE2' in ids, 'grace-period delivery via previous mailbox failed'
print('mailbox rotation grace OK')
"

log "PASS: sealed relay + mailbox rotation smoke completed"
