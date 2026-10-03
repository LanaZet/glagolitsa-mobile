#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Post-E2E API checks: оба аккаунта активны, есть device, DM-чат существует.
#
#   SENDER_USERNAME=marco RECIPIENT_USERNAME=polo ./scripts/verify-e2e-message.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

SENDER_USERNAME="${SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"

e2e_require_vars BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

log() { printf '[verify-e2e-message] %s\n' "$*"; }
fail() { printf '[verify-e2e-message] FAIL: %s\n' "$*" >&2; exit 1; }

login_user() {
  local username="$1" password="$2"
  curl -sf -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$username\",\"password\":\"$password\"}"
}

expect_devices() {
  local label="$1" token="$2" user_id="$3"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
    "$BASE_URL/api/users/$user_id/devices")"
  [[ "$code" == "200" ]] || fail "$label: GET devices HTTP $code"
  curl -sf -H "Authorization: Bearer $token" "$BASE_URL/api/users/$user_id/devices" | python3 -c '
import json, sys
devices = json.load(sys.stdin)
if not devices:
    sys.exit("devices list empty")
device_id = (devices[0].get("device_id") or "").strip()
if not device_id:
    sys.exit("device_id missing")
print(f"device_id={device_id}")
' || fail "$label: no device_id"
}

find_dm_partner() {
  local token="$1" partner_username="$2"
  curl -sf -H "Authorization: Bearer $token" "$BASE_URL/api/chats" | \
    PARTNER_USERNAME="$partner_username" python3 -c '
import json, os, sys
partner = os.environ["PARTNER_USERNAME"].strip().lower()
chats = json.load(sys.stdin)
for chat in chats:
    title = (chat.get("title") or "").strip().lower()
    chat_type = (chat.get("chat_type") or chat.get("type") or "").strip().lower()
    is_dm = chat_type in ("dm", "direct", "personal") or chat.get("dm_key")
    if is_dm and partner in title:
        print(chat.get("id") or "")
        sys.exit(0)
sys.exit("DM chat not found in list")
'
}

log "health..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable"

log "sender @$SENDER_USERNAME..."
SENDER_JSON="$(login_user "$SENDER_USERNAME" "$SENDER_PASSWORD")" || fail "sender login failed"
SENDER_TOKEN="$(echo "$SENDER_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
SENDER_ID="$(echo "$SENDER_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"
expect_devices "sender" "$SENDER_TOKEN" "$SENDER_ID"

log "recipient @$RECIPIENT_USERNAME..."
RECIPIENT_JSON="$(login_user "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD")" || fail "recipient login failed"
RECIPIENT_TOKEN="$(echo "$RECIPIENT_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
RECIPIENT_ID="$(echo "$RECIPIENT_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"
expect_devices "recipient" "$RECIPIENT_TOKEN" "$RECIPIENT_ID"

log "DM chat visible to sender..."
SENDER_DM_ID="$(find_dm_partner "$SENDER_TOKEN" "$RECIPIENT_USERNAME")" || fail "$RECIPIENT_USERNAME DM missing for sender"
log "sender DM id=$SENDER_DM_ID"

log "DM chat visible to recipient..."
RECIPIENT_DM_ID="$(find_dm_partner "$RECIPIENT_TOKEN" "$SENDER_USERNAME")" || fail "$SENDER_USERNAME DM missing for recipient"
log "recipient DM id=$RECIPIENT_DM_ID"

log "OK — both users have devices and shared DM chat"
