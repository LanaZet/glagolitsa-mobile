#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Media Service: encrypted upload/download + legacy attachment aliases.
#
# Usage:
#   ./scripts/test-media.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-media] %s\n' "$*"; }
fail() { printf '[test-media] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

TEST_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")"
TEST_TOKEN="$(echo "$TEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

TMPFILE="$(mktemp)"
python3 -c 'import os; open("'"$TMPFILE"'","wb").write(os.urandom(512))'

log "create media upload slot (photo)..."
SLOT_JSON="$(curl -sf -X POST "$BASE_URL/api/media/upload-slots" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"photo","mime_type":"image/jpeg"}')"
FILE_ID="$(echo "$SLOT_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['file_id'])")"

HASH="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open("'"$TMPFILE"'","rb").read()).hexdigest())')"

log "upload encrypted blob..."
UPLOAD_JSON="$(curl -sf -X PUT "$BASE_URL/api/media/files/$FILE_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  -H "X-Content-Hash: $HASH" \
  --data-binary @"$TMPFILE")"
SIZE="$(echo "$UPLOAD_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['size_bytes'])")"
[[ "$SIZE" == "512" ]] || fail "unexpected uploaded size: $SIZE"

log "download encrypted blob..."
DOWNLOADED="$(curl -sf "$BASE_URL/api/media/files/$FILE_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -o /tmp/glagolitsa-media.bin -w '%{size_download}')"
[[ "$DOWNLOADED" == "512" ]] || fail "unexpected downloaded size: $DOWNLOADED"

log "legacy attachment alias..."
LEGACY_SLOT="$(curl -sf -X POST "$BASE_URL/api/attachments" \
  -H "Authorization: Bearer $TEST_TOKEN")"
LEGACY_ID="$(echo "$LEGACY_SLOT" | python3 -c "import sys,json; print(json.load(sys.stdin)['attachment_id'])")"
curl -sf -X PUT "$BASE_URL/api/attachments/$LEGACY_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @"$TMPFILE" >/dev/null
LEGACY_SIZE="$(curl -sf "$BASE_URL/api/attachments/$LEGACY_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" \
  -o /dev/null -w '%{size_download}')"
[[ "$LEGACY_SIZE" == "512" ]] || fail "legacy download size mismatch"

log "delete media file..."
curl -sf -X DELETE "$BASE_URL/api/media/files/$FILE_ID" \
  -H "Authorization: Bearer $TEST_TOKEN" -o /dev/null -w '%{http_code}' | grep -q '204' \
  || fail "delete did not return 204"

rm -f "$TMPFILE" /tmp/glagolitsa-media.bin
log "PASS: media service E2E completed"
