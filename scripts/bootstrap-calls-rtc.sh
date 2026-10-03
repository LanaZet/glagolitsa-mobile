#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Bootstrap checklist for calls on a real (or local) server.
# Does not print secrets. Generates RTC configs when env is set.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT/scripts/lib/common.sh" 2>/dev/null || true

echo "== Glagolitsa calls bootstrap =="

need_env() {
  local k="$1"
  if [[ -z "${!k:-}" ]]; then
    echo "MISSING $k"
    return 1
  fi
  echo "OK      $k is set"
  return 0
}

fail=0
for k in LIVEKIT_URL LIVEKIT_API_KEY LIVEKIT_API_SECRET TURN_DOMAIN TURN_SHARED_SECRET; do
  need_env "$k" || fail=1
done

if [[ "$fail" -eq 0 ]]; then
  echo "-- generating RTC configs --"
  bash "$ROOT/server/deploy/rtc/generate-config.sh"
else
  echo "WARN: set LIVEKIT_* and TURN_* then re-run to generate livekit/turn configs"
fi

echo "-- migration 032 present --"
if [[ -f "$ROOT/server/internal/store/migrations/032_calls_group_ready.sql" ]]; then
  echo "OK  032_calls_group_ready.sql in tree (API migrate() applies on start)"
else
  echo "FAIL migration 032 missing"
  fail=1
fi

echo "-- next ops steps --"
cat <<'EOF'
1) On server: docker compose -f server/deploy/rtc/docker-compose.rtc.yml up -d
   (use generated livekit.yaml keys; copy turnserver.generated.conf → turnserver.conf)
2) Caddy: reverse_proxy WSS for rtc-<region> → 127.0.0.1:7880
3) production.env: LIVEKIT_*, TURN_*, CALLS_RTC_AVAILABLE=true, CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED=true
4) Restart glagolitsa-api (migrations auto-apply)
5) ./scripts/verify-calls-ready.sh
6) ./scripts/smoke-call-1to1-api.sh
7) ./scripts/build-prerelease-apk.sh --api https://api.YOUR_DOMAIN
EOF

if [[ "$fail" -ne 0 ]]; then
  echo "RESULT: incomplete env (configs may be partial)"
  exit 1
fi
echo "RESULT: bootstrap prep OK"
exit 0
