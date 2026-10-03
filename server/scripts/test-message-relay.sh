#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E smoke test: encrypted DM relay via messages_queue.
#
# Usage:
#   ./scripts/test-message-relay.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-message-relay] %s\n' "$*"; }
fail() { printf '[test-message-relay] FAIL: %s\n' "$*" >&2; exit 1; }

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

DM_JSON="$(curl -sf -X POST "$BASE_URL/api/chats/dm" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"user_id\":\"$BOB_ID\"}")"
CHAT_ID="$(echo "$DM_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")"

BOB_DEVICES="$(curl -sf "$BASE_URL/api/users/$BOB_ID/devices" -H "Authorization: Bearer $TEST_TOKEN")"
BOB_MAILBOX="$(echo "$BOB_DEVICES" | python3 -c "
import sys, json
devices = json.load(sys.stdin)
device = next(d for d in devices if d['device_id'] == '$BOB_DEVICE')
print(device['mailbox_token'])
")"

CIPHERTEXT="$(python3 -c 'import base64; print(base64.b64encode(b"fake-ciphertext-bytes").decode())')"

log "plaintext DM endpoint is deprecated..."
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/chats/$CHAT_ID/messages" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"body":"plaintext"}')"
[[ "$STATUS" == "410" ]] || fail "expected 410 for plaintext DM, got $STATUS"

log "relay encrypted envelope (sealed-sender, no chat_id)..."
RELAY_JSON="$(curl -sf -X POST "$BASE_URL/api/messages/relay" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H "X-Device-Id: $TEST_DEVICE" \
  -H 'Content-Type: application/json' \
  -d "{
    \"client_message_id\":\"pending-e2e-1\",
    \"envelopes\":[{\"mailbox_token\":\"$BOB_MAILBOX\",\"envelope_type\":3,\"ciphertext\":\"$CIPHERTEXT\"}]
  }")"
ENVELOPE_ID="$(echo "$RELAY_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['envelope_ids'][0])")"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME polls queue..."
QUEUE_JSON="$(curl -sf "$BASE_URL/api/messages/queue?limit=10" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
echo "$QUEUE_JSON" | python3 -c "
import sys, json
queue = json.load(sys.stdin)
ids = [e['envelope_id'] for e in queue['envelopes']]
assert '$ENVELOPE_ID' in ids
item = next(e for e in queue['envelopes'] if e['envelope_id'] == '$ENVELOPE_ID')
assert item['ciphertext'] == '$CIPHERTEXT'
assert item['size_bucket'] >= 16
print('queue fetch OK')
"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME ACKs delivery..."
curl -sf -X POST "$BASE_URL/api/messages/queue/ack" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE" \
  -H 'Content-Type: application/json' \
  -d "{\"envelope_ids\":[\"$ENVELOPE_ID\"]}" >/dev/null

QUEUE_AFTER="$(curl -sf "$BASE_URL/api/messages/queue?limit=10" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "X-Device-Id: $BOB_DEVICE")"
echo "$QUEUE_AFTER" | python3 -c "
import sys, json
ids = [e['envelope_id'] for e in json.load(sys.stdin)['envelopes']]
if '$ENVELOPE_ID' in ids:
    raise SystemExit('envelope still present after ack')
print('ack deletion OK')
"

log "PASS: message relay E2E smoke completed"
