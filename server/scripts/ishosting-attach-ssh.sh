#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Привязать локальный SSH-ключ к VPS через is*hosting API.
# Требует ~/.config/ishosting/.env с ISHOSTING_TOKEN и ISHOSTING_BASE_URL
#
# Использование:
#   VPS_ID=<panel-id> ./scripts/ishosting-attach-ssh.sh
set -euo pipefail

: "${VPS_ID:?Set VPS_ID to the server id from the hosting panel}"
ENV_FILE="${ISHOSTING_ENV:-$HOME/.config/ishosting/.env}"
PUB_KEY_FILE="${PUB_KEY_FILE:-$HOME/.ssh/id_ed25519.pub}"
KEY_TITLE="${KEY_TITLE:-glagolitsa-mac}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Создай $ENV_FILE:"
  echo "  ISHOSTING_TOKEN=..."
  echo "  ISHOSTING_BASE_URL=https://..."
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

: "${ISHOSTING_TOKEN:?ISHOSTING_TOKEN required}"
: "${ISHOSTING_BASE_URL:?ISHOSTING_BASE_URL required}"

if [[ ! "$ISHOSTING_BASE_URL" =~ ^https:// ]]; then
  echo "ISHOSTING_BASE_URL должен начинаться с https://"
  exit 1
fi

PUBLIC_KEY="$(tr -d '\n' <"$PUB_KEY_FILE")"

api() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -X "$method" -H "X-Api-Token: $ISHOSTING_TOKEN" -H "Content-Type: application/json" -H "Accept: application/json")
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  curl "${args[@]}" "${ISHOSTING_BASE_URL}${path}"
}

echo "==> VPS $VPS_ID status"
api GET "/vps/${VPS_ID}/status" | python3 -m json.tool 2>/dev/null || api GET "/vps/${VPS_ID}/status"

echo "==> SSH keys in profile"
KEYS_JSON="$(api GET "/settings/ssh")"
echo "$KEYS_JSON" | python3 -m json.tool 2>/dev/null || echo "$KEYS_JSON"

KEY_ID="$(python3 - <<'PY' "$KEYS_JSON" "$PUBLIC_KEY"
import json, sys
keys = json.loads(sys.argv[1])
target = sys.argv[2].strip()
items = keys if isinstance(keys, list) else keys.get("data", keys)
for item in items or []:
    pub = (item.get("public") or item.get("public_key") or "").strip()
    if pub == target:
        print(item.get("id", ""))
        break
PY
)"

if [[ -z "$KEY_ID" ]]; then
  echo "==> Add SSH key to profile"
  CREATE_JSON="$(api POST "/settings/ssh" "{\"title\":\"$KEY_TITLE\",\"public\":\"$PUBLIC_KEY\"}")"
  echo "$CREATE_JSON" | python3 -m json.tool 2>/dev/null || echo "$CREATE_JSON"
  KEY_ID="$(python3 - <<'PY' "$CREATE_JSON"
import json, sys
data = json.loads(sys.argv[1])
print(data.get("id") or data.get("data", {}).get("id", ""))
PY
)"
fi

if [[ -z "$KEY_ID" ]]; then
  echo "Не удалось получить ID SSH-ключа"
  exit 1
fi

echo "==> Attach key $KEY_ID to VPS $VPS_ID"
ATTACH_JSON="$(api PATCH "/vps/${VPS_ID}/access/ssh" "{\"is_enabled\":true,\"keys\":[$KEY_ID]}")"
echo "$ATTACH_JSON" | python3 -m json.tool 2>/dev/null || echo "$ATTACH_JSON"

echo ""
echo "Проверь SSH:"
echo "  ssh -i ~/.ssh/id_ed25519 root@<public-ip>"
echo "Затем деплой из server/:"
echo "  VPS_HOST=<public-ip> ./scripts/push-to-vps.sh"