#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Проверка шифрования на relay-слое: ciphertext есть, plaintext тела сообщения в API нет.
#
#   E2E_MSG='e2e-123' RECIPIENT_USERNAME=Polo ./scripts/verify-e2e-relay-encryption.sh
# Запускать после отправки, до входа получателя в чат.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"
E2E_MSG="${E2E_MSG:-}"

e2e_disable_proxy_for_local_api "$BASE_URL"
e2e_require_vars BASE_URL RECIPIENT_USERNAME RECIPIENT_PASSWORD E2E_MSG

log() { printf '[verify-e2e-relay-encryption] %s\n' "$*"; }
fail() { printf '[verify-e2e-relay-encryption] FAIL: %s\n' "$*" >&2; exit 1; }

log "health..."
curl -sf "$BASE_URL/api/health" >/dev/null || fail "server not reachable at $BASE_URL"

log "login recipient @$RECIPIENT_USERNAME..."
LOGIN_JSON="$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$RECIPIENT_USERNAME\",\"password\":\"$RECIPIENT_PASSWORD\"}")" \
  || fail "login failed for $RECIPIENT_USERNAME"
TOKEN="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")"
USER_ID="$(echo "$LOGIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['user']['id'])")"

DEVICES_JSON="$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/users/$USER_ID/devices")" \
  || fail "devices list failed"

python3 - "$DEVICES_JSON" "$TOKEN" "$BASE_URL" "$E2E_MSG" <<'PY'
import base64
import json
import sys
import urllib.request

devices_json, token, base_url, plaintext = sys.argv[1:5]
devices = json.loads(devices_json)
device_ids = [d.get("device_id", "").strip() for d in devices if d.get("device_id")]
if not device_ids:
    sys.exit("no devices for recipient")

found_cipher = False
for device_id in device_ids:
    req = urllib.request.Request(
        f"{base_url}/api/messages/queue?limit=30",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Device-Id": device_id,
        },
    )
    with urllib.request.urlopen(req) as resp:
        queue = json.load(resp)
    for envelope in queue.get("envelopes") or []:
        cipher_b64 = (envelope.get("ciphertext") or "").strip()
        if not cipher_b64:
            continue
        env_type = envelope.get("envelope_type")
        if env_type not in (2, 3, 4):
            print(f"WARN: unexpected envelope_type={env_type}", file=sys.stderr)
        try:
            raw = base64.b64decode(cipher_b64, validate=False)
        except Exception as exc:
            sys.exit(f"invalid base64 ciphertext: {exc}")
        if plaintext.encode("utf-8") in raw:
            sys.exit("SECURITY: plaintext message found inside relay ciphertext bytes")
        if plaintext in cipher_b64:
            sys.exit("SECURITY: plaintext message found in relay ciphertext base64")
        found_cipher = True
        print(f"OK device={device_id} envelope_type={env_type} ciphertext_bytes={len(raw)}")

if not found_cipher:
    sys.exit("no ciphertext envelopes in recipient queue")
PY

log "PASS — relay queue has encrypted envelopes without plaintext leak"
