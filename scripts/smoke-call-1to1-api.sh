#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# API-level 1:1 call smoke (no real media). Verifies control plane on a live server.
# Requires two accounts. Does not print tokens or secrets.
#
#   API_BASE_URL=https://api.glagolit.me \
#   CALLER_USER=marco CALLER_PASS=... CALLEE_USER=polo CALLEE_PASS=... \
#   ./scripts/smoke-call-1to1-api.sh
set -euo pipefail
API="${API_BASE_URL:-http://127.0.0.1:8080}"
API="${API%/}"
CALLER_USER="${CALLER_USER:-}"
CALLER_PASS="${CALLER_PASS:-}"
CALLEE_USER="${CALLEE_USER:-}"
CALLEE_PASS="${CALLEE_PASS:-}"

if [[ -z "$CALLER_USER" || -z "$CALLER_PASS" || -z "$CALLEE_USER" || -z "$CALLEE_PASS" ]]; then
  echo "Set CALLER_USER/PASS and CALLEE_USER/PASS"
  exit 2
fi

login() {
  local user="$1" pass="$2"
  curl -fsS -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}"
}

echo "== 1:1 call API smoke against $API =="
caller_json="$(login "$CALLER_USER" "$CALLER_PASS")"
callee_json="$(login "$CALLEE_USER" "$CALLEE_PASS")"

caller_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' <<<"$caller_json")"
callee_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' <<<"$callee_json")"
caller_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user"]["id"])' <<<"$caller_json")"
callee_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user"]["id"])' <<<"$callee_json")"

# Never echo tokens
echo "OK  logged in caller_id=${caller_id:0:8}… callee_id=${callee_id:0:8}…"

create="$(curl -fsS -X POST "$API/api/calls" \
  -H "Authorization: Bearer $caller_token" \
  -H "X-Device-Id: smoke-caller" \
  -H 'Content-Type: application/json' \
  -d "{\"callee_id\":\"$callee_id\",\"call_type\":\"audio\",\"device_id\":\"smoke-caller\"}")" || {
  echo "FAIL create call (RTC flags? 503=kill switch)"
  exit 1
}
call_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["call"]["id"])' <<<"$create")"
status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["call"]["status"])' <<<"$create")"
echo "OK  create call_id=${call_id:0:8}… status=$status"

accept="$(curl -fsS -X POST "$API/api/calls/$call_id/accept" \
  -H "Authorization: Bearer $callee_token" \
  -H "X-Device-Id: smoke-callee")"
astatus="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["call"]["status"])' <<<"$accept")"
echo "OK  accept status=$astatus"

# Token (may 503 if LiveKit not configured)
if tok="$(curl -fsS -X GET "$API/api/calls/$call_id/token" \
  -H "Authorization: Bearer $caller_token" \
  -H "X-Device-Id: smoke-caller" 2>/dev/null)"; then
  has_url="$(python3 -c 'import json,sys; t=json.load(sys.stdin); print("yes" if t.get("livekit_url") and t.get("token") else "no")' <<<"$tok")"
  e2ee="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("media_config",{}).get("e2ee"))' <<<"$tok")"
  # Do not print token
  echo "OK  token issued livekit_url_present=$has_url e2ee=$e2ee"
else
  echo "WARN token unavailable (LiveKit disabled or keys missing)"
fi

# ICE short-lived (must not log credentials in this script output beyond presence)
if ice="$(curl -fsS -X GET "$API/api/ice/servers?call_id=$call_id" \
  -H "Authorization: Bearer $caller_token" \
  -H "X-Device-Id: smoke-caller" 2>/dev/null)"; then
  n="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("servers",[])))' <<<"$ice")"
  echo "OK  ice servers count=$n (credentials not printed)"
else
  echo "WARN ice servers failed"
fi

curl -fsS -X POST "$API/api/calls/$call_id/end" \
  -H "Authorization: Bearer $caller_token" \
  -H "X-Device-Id: smoke-caller" >/dev/null
echo "OK  end call"
echo "RESULT: PASS (control plane). Real audio needs two devices + LiveKit media path."
exit 0
