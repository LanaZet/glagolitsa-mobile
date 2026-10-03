#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Encrypted attachment upload/download (этап 6.1).
#
# Usage:
#   ./scripts/test-attachments.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-attachments] %s\n' "$*"; }
fail() { printf '[test-attachments] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

TEST_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")"
TEST_TOKEN="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

log "create attachment slot..."
SLOT_JSON="$(curl -sf -X POST "$BASE_URL/api/attachments" \
  -H "Authorization: Bearer $TEST_TOKEN")"
ATTACHMENT_ID="$(echo "$SLOT_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['attachment_id'])")"

TMPFILE="$(mktemp)"
python3 -c 'import os; open("'"$TMPFILE"'","wb").write(os.urandom(256))'

log "upload encrypted blob..."
UPLOAD_JSON="$(curl -sf -X PUT "$BASE_URL/api/attachments/$ATTACHMENT_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @"$TMPFILE")"
SIZE="$(echo "$UPLOAD_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['size_bytes'])")"
[[ "$SIZE" == "256" ]] || fail "unexpected uploaded size: $SIZE"

log "download encrypted blob..."
DOWNLOADED="$(curl -sf "$BASE_URL/api/attachments/$ATTACHMENT_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -o /tmp/glagolitsa-attachment.bin -w '%{size_download}')"
[[ "$DOWNLOADED" == "256" ]] || fail "unexpected downloaded size: $DOWNLOADED"

rm -f "$TMPFILE" /tmp/glagolitsa-attachment.bin
log "PASS: encrypted attachment E2E completed"
