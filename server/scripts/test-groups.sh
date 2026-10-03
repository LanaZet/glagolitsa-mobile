#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Group Service: create, invite, membership version / key epoch.
#
# Usage:
#   ./scripts/test-groups.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../scripts/lib/dev-accounts.sh
source "$REPO_ROOT/scripts/lib/dev-accounts.sh"

log() { printf '[test-groups] %s\n' "$*"; }
fail() { printf '[test-groups] FAIL: %s\n' "$*" >&2; exit 1; }

login() {
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

log "health check..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

ADMIN_JSON="$(login "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD")"
ADMIN_TOKEN="$(echo "$ADMIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

BOB_JSON="$(login "$GLAGOLITSA_DEV_SECONDARY_USERNAME" "$GLAGOLITSA_DEV_SECONDARY_PASSWORD")"
BOB_TOKEN="$(echo "$BOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

log "create group..."
GROUP_JSON="$(curl -sf -X POST "$BASE_URL/api/groups" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"E2EE Council"}')"
GROUP_ID="$(echo "$GROUP_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['group_id'])")"
VERSION="$(echo "$GROUP_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['membership_version'])")"
[[ "$VERSION" == "1" ]] || fail "unexpected initial version: $VERSION"

log "create invite..."
INVITE_JSON="$(curl -sf -X POST "$BASE_URL/api/groups/$GROUP_ID/invites" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"expires_in_hours":24}')"
TOKEN="$(echo "$INVITE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"

log "join by invite ($GLAGOLITSA_DEV_SECONDARY_USERNAME)..."
JOIN_JSON="$(curl -sf -X POST "$BASE_URL/api/groups/invites/$TOKEN/join" \
  -H "Authorization: Bearer $BOB_TOKEN")"
ROTATE="$(echo "$JOIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['key_rotation_required'])")"
[[ "$ROTATE" == "True" ]] || fail "expected key_rotation_required"

log "key epoch..."
EPOCH_JSON="$(curl -sf "$BASE_URL/api/groups/$GROUP_ID/key-epoch" \
  -H "Authorization: Bearer $ADMIN_TOKEN")"
EPOCH="$(echo "$EPOCH_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['membership_version'])")"
[[ "$EPOCH" -ge 2 ]] || fail "expected membership_version >= 2"

log "PASS: group service E2E completed"
