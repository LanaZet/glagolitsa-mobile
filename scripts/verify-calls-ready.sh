#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Verify API + RTC readiness for calls (privacy-safe).
#   API_BASE_URL=https://api.example ./scripts/verify-calls-ready.sh
#   LIVEKIT_HTTP_URL=http://127.0.0.1:7880 ./scripts/verify-calls-ready.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:8080}"
LIVEKIT_HTTP_URL="${LIVEKIT_HTTP_URL:-http://127.0.0.1:7880}"
DIAG="${API_BASE_URL%/}/api/diagnostics"
POLICY="${API_BASE_URL%/}/api/client/update-policy"

echo "== verify-calls-ready =="
echo "api=$API_BASE_URL livekit_http=$LIVEKIT_HTTP_URL"
fail=0

if curl -fsS --max-time 5 "${API_BASE_URL%/}/api/health" >/dev/null; then
  echo "OK  API /api/health"
else
  echo "FAIL API health"
  fail=1
fi

if curl -fsS --max-time 5 "$DIAG" -o /tmp/glag-calls-diag.json; then
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' || fail=1
import json,sys
d=json.load(open("/tmp/glag-calls-diag.json"))
blob=json.dumps(d).lower()
for bad in ("credential","api_secret","shared_secret","password=","bearer "):
    if bad in blob:
        print("FAIL diagnostics may contain secret material:", bad)
        sys.exit(1)
rtc=d.get("subsystems",{}).get("rtc",{})
print(f"OK  diagnostics rtc.status={rtc.get('status')} detail={rtc.get('detail')!r}")
if rtc.get("status") == "disabled":
    print("WARN rtc disabled (LiveKit keys empty or CALLS_RTC_AVAILABLE=false)")
PY
  else
    echo "OK  diagnostics HTTP 200"
  fi
else
  echo "FAIL diagnostics"
  fail=1
fi

if curl -fsS --max-time 5 "$POLICY" -o /tmp/glag-calls-policy.json; then
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY'
import json
p=json.load(open("/tmp/glag-calls-policy.json"))
f=p.get("features") or {}
print("OK  features",
      "audio=", f.get("calls_audio"),
      "livekit=", f.get("calls_rtc_livekit_enabled"),
      "audio_only=", f.get("calls_audio_only_enabled"))
if not f.get("calls_rtc_livekit_enabled") and not f.get("calls_audio_only_enabled"):
    print("WARN start-call UI will hide (need livekit or audio_only + RTC available)")
PY
  fi
else
  echo "WARN update-policy unreachable"
fi

if curl -fsS --max-time 3 "$LIVEKIT_HTTP_URL" >/dev/null 2>&1; then
  echo "OK  LiveKit HTTP"
else
  echo "WARN LiveKit HTTP unreachable (set LIVEKIT_HTTP_URL or start RTC stack)"
fi

# Reuse edge smoke when available
if [[ -x "$ROOT/scripts/verify-rtc-edge-smoke.sh" ]]; then
  LIVEKIT_HTTP_URL="$LIVEKIT_HTTP_URL" API_DIAGNOSTICS_URL="$DIAG" \
    bash "$ROOT/scripts/verify-rtc-edge-smoke.sh" || true
fi

if [[ "$fail" -ne 0 ]]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS (API path)"
exit 0
