#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E smoke test: local dev account DM messaging via REST API.
#
# Usage:
#   ./scripts/test-dm.sh
#   BASE_URL=http://localhost:8080 ./scripts/test-dm.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-dm] %s\n' "$*"; }
fail() { printf '[test-dm] FAIL: %s\n' "$*" >&2; exit 1; }

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

log "login $GLAGOLITSA_DEV_SECONDARY_USERNAME..."
BOB_JSON="$(login "$GLAGOLITSA_DEV_SECONDARY_USERNAME" "$GLAGOLITSA_DEV_SECONDARY_PASSWORD")" \
  || fail "$GLAGOLITSA_DEV_SECONDARY_USERNAME login failed"
BOB_TOKEN="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
BOB_ID="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"

log "open DM $GLAGOLITSA_DEV_PRIMARY_USERNAME -> $GLAGOLITSA_DEV_SECONDARY_USERNAME..."
DM_JSON="$(curl -sf -X POST "$BASE_URL/api/chats/dm" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"user_id\":\"$BOB_ID\"}")" || fail "create dm failed"
CHAT_ID="$(echo "$DM_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")"

UNIQUE_BODY="dm-e2e-$(date +%s)"
log "$GLAGOLITSA_DEV_PRIMARY_USERNAME sends: $UNIQUE_BODY"
curl -sf -X POST "$BASE_URL/api/chats/$CHAT_ID/messages" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"body\":\"$UNIQUE_BODY\",\"pending_id\":\"e2e-pending-1\"}" >/dev/null || fail "send message failed"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME reads messages (paginated)..."
PAGE_JSON="$(curl -sf "$BASE_URL/api/chats/$CHAT_ID/messages?limit=20" \
  -H "Authorization: Bearer $BOB_TOKEN")" || fail "list messages failed"

echo "$PAGE_JSON" | python3 -c "
import sys, json
page = json.load(sys.stdin)
bodies = [m['body'] for m in page['messages']]
target = '$UNIQUE_BODY'
if target not in bodies:
    raise SystemExit('message not found for recipient')
print('recipient received message OK')
print('has_more=', page.get('has_more', False))
"

log "$GLAGOLITSA_DEV_SECONDARY_USERNAME replies..."
REPLY_BODY="reply-$(date +%s)"
curl -sf -X POST "$BASE_URL/api/chats/$CHAT_ID/messages" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"body\":\"$REPLY_BODY\"}" >/dev/null || fail "$GLAGOLITSA_DEV_SECONDARY_USERNAME reply failed"

log "$GLAGOLITSA_DEV_PRIMARY_USERNAME reads reply..."
TEST_PAGE="$(curl -sf "$BASE_URL/api/chats/$CHAT_ID/messages?limit=20" \
  -H "Authorization: Bearer $TEST_TOKEN")"
echo "$TEST_PAGE" | python3 -c "
import sys, json
page = json.load(sys.stdin)
bodies = [m['body'] for m in page['messages']]
if '$REPLY_BODY' not in bodies:
    raise SystemExit('reply not found for sender')
print('sender received reply OK')
"

log "PASS: DM E2E smoke completed"
