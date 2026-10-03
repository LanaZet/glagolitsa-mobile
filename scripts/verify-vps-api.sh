#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Проверка прод API на VPS перед E2E.
#
#   ./scripts/verify-vps-api.sh
#   BASE_URL=https://api.glagolit.me SENDER_PASSWORD='...' ./scripts/verify-vps-api.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"
SENDER_USERNAME="${SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
ADB_SERIAL="${ADB_SERIAL:-}"

e2e_require_vars BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

log() { printf '[verify-vps] %s\n' "$*"; }
fail() {
  printf '[verify-vps] FAIL: %s\n' "$*" >&2
  cat >&2 <<'EOF'

VPS API недоступен. Починка с веб-консоли is*hosting (root):
  bash /root/glagolitsa-deploy/deploy/scripts/vps-console-fix-all.sh --build
или с Mac (если SSH открыт):
  cd server && ./scripts/phone-deploy-server.sh

EOF
  exit 1
}

login_user() {
  local user="$1" pass="$2"
  curl -sf --connect-timeout 10 --max-time 20 -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}"
}

log "health $BASE_URL ..."
if ! curl -sf --connect-timeout 10 --max-time 20 "$BASE_URL/api/health" >/dev/null 2>&1; then
  if [[ -n "$ADB_SERIAL" ]] && command -v adb >/dev/null 2>&1; then
    log "health с Mac не ответил — пробую curl с телефона ($ADB_SERIAL)..."
    if adb -s "$ADB_SERIAL" shell "curl -sf --connect-timeout 10 --max-time 20 $BASE_URL/api/health" >/dev/null 2>&1; then
      log "OK — health с телефона"
    else
      fail "TLS/health не отвечает ($BASE_URL), телефон тоже"
    fi
  else
    fail "TLS/health не отвечает ($BASE_URL)"
  fi
else
  log "OK — health"
fi

log "login sender @$SENDER_USERNAME ..."
SENDER_JSON="$(login_user "$SENDER_USERNAME" "$SENDER_PASSWORD")" \
  || fail "login failed for $SENDER_USERNAME"
log "OK — sender token"

log "login recipient @$RECIPIENT_USERNAME ..."
RECIPIENT_JSON="$(login_user "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD")" \
  || fail "login failed for $RECIPIENT_USERNAME (задай RECIPIENT_PASSWORD)"
log "OK — recipient token"

log "PASS — VPS API готов к E2E ($SENDER_USERNAME → $RECIPIENT_USERNAME @ $BASE_URL)"
