#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Privacy-safe RTC edge smoke checks (stage 1).
# Does not print tokens, TURN credentials, or media keys.
set -euo pipefail

LIVEKIT_HTTP_URL="${LIVEKIT_HTTP_URL:-http://127.0.0.1:7880}"
TURN_HOST="${TURN_HOST:-}"
API_DIAGNOSTICS_URL="${API_DIAGNOSTICS_URL:-http://127.0.0.1:8080/api/diagnostics}"

echo "== RTC edge smoke =="
echo "livekit_http=${LIVEKIT_HTTP_URL}"
echo "turn_host=${TURN_HOST:-"(unset)"}"
echo "diagnostics=${API_DIAGNOSTICS_URL}"

fail=0

if curl -fsS --max-time 3 "${LIVEKIT_HTTP_URL}" >/dev/null; then
  echo "OK  LiveKit HTTP responds (SFU process up)"
else
  echo "FAIL LiveKit HTTP unreachable — SFU down or not published on loopback"
  fail=1
fi

if curl -fsS --max-time 3 "${API_DIAGNOSTICS_URL}" -o /tmp/glagolitsa-rtc-diag.json; then
  # Parse status without dumping full JSON secrets if any slip in.
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY'
import json,sys
p="/tmp/glagolitsa-rtc-diag.json"
with open(p) as f:
    data=json.load(f)
rtc=data.get("subsystems",{}).get("rtc",{})
status=rtc.get("status","missing")
detail=rtc.get("detail","")
# Reject accidental secret-looking fields in diagnostics payload.
blob=json.dumps(data).lower()
for bad in ("credential", "api_secret", "shared_secret", "password=", "bearer "):
    if bad in blob:
        print(f"FAIL diagnostics payload may contain secret material ({bad})")
        sys.exit(2)
print(f"OK  /api/diagnostics rtc.status={status} detail={detail!r}")
if status not in ("ok","degraded","disabled"):
    sys.exit(1)
PY
    rc=$?
    if [[ $rc -eq 2 ]]; then fail=1; fi
    if [[ $rc -eq 1 ]]; then echo "WARN unexpected rtc status"; fi
  else
    echo "OK  diagnostics HTTP 200 (python3 missing; skip JSON assert)"
  fi
else
  echo "WARN API diagnostics unreachable (API may be down)"
fi

if [[ -n "${TURN_HOST}" ]]; then
  if command -v turnutils_uclient >/dev/null 2>&1; then
    echo "NOTE TURN allocate tests require short-lived creds; run manually with turnutils_uclient"
    echo "     against ${TURN_HOST} (do not paste credentials into CI logs)"
  else
    echo "NOTE install coturn turnutils_uclient for full TURN allocate smoke"
  fi
  if command -v nc >/dev/null 2>&1 || command -v ncat >/dev/null 2>&1; then
    echo "OK  TURN host set; UDP/TLS allocate left to manual/VPN matrix"
  fi
else
  echo "SKIP TURN host unset (set TURN_HOST=turn-primary.example)"
fi

echo "== scenarios to run manually =="
echo "1) normal network room join"
echo "2) blocked UDP (expect TURN/TLS path)"
echo "3) VPN on one or both peers"
echo "4) log review: no tokens / TURN secrets / media keys"

if [[ "$fail" -ne 0 ]]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS (basic)"
exit 0
