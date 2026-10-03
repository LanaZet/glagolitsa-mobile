#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E DM на двух устройствах (Signal-style): Polo на телефоне, Marco на эмуляторе.
# Без logout на получателе — decrypt и live UI на втором экране.
#
#   ./scripts/run-maestro-message-dual-e2e.sh
#   RECIPIENT_ADB_SERIAL=<adb-serial> SENDER_ADB_SERIAL=emulator-5554 ./scripts/run-maestro-message-dual-e2e.sh
#
# Удалённый API: задайте BASE_URL и учётки явно. Локально по умолчанию Marco/Polo.
# Локально: BASE_URL=http://127.0.0.1:8080 SENDER_USERNAME=Marco RECIPIENT_USERNAME=Polo
# Только VPS (без fallback): REQUIRE_VPS=1 ./scripts/run-maestro-message-dual-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E_ROOT="$ROOT"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"
# shellcheck source=e2e-dm-security.sh
source "$ROOT/scripts/e2e-dm-security.sh"
# shellcheck source=lib/android-env.sh
source "$ROOT/scripts/lib/android-env.sh"

if [[ -d "${HOME}/.maestro/bin" ]]; then
  PATH="${HOME}/.maestro/bin:${PATH}"
fi
if [[ -d "${HOME}/Library/Android/sdk/platform-tools" ]]; then
  PATH="${HOME}/Library/Android/sdk/platform-tools:${PATH}"
fi

init_android_env
[[ -x "$ADB" ]] || { echo "adb not found" >&2; exit 1; }
command -v maestro >/dev/null 2>&1 || { echo "maestro not found" >&2; exit 1; }

RECIPIENT_ADB_SERIAL="${RECIPIENT_ADB_SERIAL:-$(glagolitsa_android_first_physical_device_serial || true)}"
SENDER_ADB_SERIAL="${SENDER_ADB_SERIAL:-$(glagolitsa_android_first_emulator_serial || true)}"
[[ -n "$RECIPIENT_ADB_SERIAL" ]] || { echo "Нет USB-телефона (RECIPIENT_ADB_SERIAL)" >&2; exit 1; }
[[ -n "$SENDER_ADB_SERIAL" ]] || { echo "Нет эмулятора (SENDER_ADB_SERIAL)" >&2; exit 1; }

RUN_ID="${RUN_ID:-$(date +%s)}"
SENDER_USERNAME="${SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
E2E_MSG="${E2E_MSG:-e2e-dual-${RUN_ID}}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"

log() { printf '[dual-e2e] %s\n' "$*"; }
warn() { printf '[dual-e2e] WARN: %s\n' "$*" >&2; }

is_local_api() {
  [[ "$BASE_URL" == *127.0.0.1* || "$BASE_URL" == *localhost* ]]
}

if is_local_api; then
  : # локальный docker
elif [[ "${REQUIRE_VPS:-}" == "1" ]]; then
  log "REQUIRE_VPS=1 — только $BASE_URL"
elif ! curl -sf --connect-timeout 5 --max-time 10 "${BASE_URL}/api/health" >/dev/null 2>&1; then
  warn "Прод API недоступен ($BASE_URL) — fallback на http://127.0.0.1:8080 (задай REQUIRE_VPS=1 чтобы отключить)"
  BASE_URL="http://127.0.0.1:8080"
fi

e2e_require_vars BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

log "BASE_URL=$BASE_URL sender=$SENDER_USERNAME recipient=$RECIPIENT_USERNAME"

maestro_on() {
  local serial="$1"
  shift
  log "maestro → $serial"
  maestro --udid "$serial" "$@"
}

prime_recipient_on_phone() {
  # На MIUI надёжнее adb (dev shortcut получателя внутри скрипта).
  if [[ "${PHONE_PRIME_MAESTRO:-}" == "1" ]]; then
    maestro_on "$RECIPIENT_ADB_SERIAL" test "$ROOT/maestro/flows/dm-message-prime-recipient-stay.yaml" \
      -e "RECIPIENT_USERNAME=$RECIPIENT_USERNAME" \
      -e "RECIPIENT_PASSWORD=$RECIPIENT_PASSWORD"
    return $?
  fi
  log "prime через adb (PHONE_PRIME_MAESTRO=1 для Maestro)"
  bash "$ROOT/scripts/phone-maestro-prime.sh" \
    "$RECIPIENT_ADB_SERIAL" "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD"
}

receive_on_phone() {
  if [[ "${PHONE_RECEIVE_ADB:-}" == "1" ]]; then
    log "receive через adb (PHONE_RECEIVE_ADB=1)"
    bash "$ROOT/scripts/phone-adb-assert-message.sh" \
      "$RECIPIENT_ADB_SERIAL" "$E2E_MSG" "$SENDER_USERNAME"
    return $?
  fi
  if maestro_on "$RECIPIENT_ADB_SERIAL" test "$ROOT/maestro/flows/dm-message-receive-stay.yaml" "${MAESTRO_ENV[@]}" \
    2>/tmp/maestro-phone-receive.err; then
    return 0
  fi
  warn "Maestro receive failed — adb fallback..."
  cat /tmp/maestro-phone-receive.err >&2 || true
  bash "$ROOT/scripts/phone-adb-assert-message.sh" \
    "$RECIPIENT_ADB_SERIAL" "$E2E_MSG" "$SENDER_USERNAME"
}

ensure_api() {
  if curl -sf --connect-timeout 10 --max-time 20 "${BASE_URL}/api/health" >/dev/null 2>&1; then
    return 0
  fi
  if ! is_local_api; then
    ADB_SERIAL="$RECIPIENT_ADB_SERIAL" bash "$ROOT/scripts/verify-vps-api.sh" || exit 1
    return 0
  fi
  log "API недоступен: $BASE_URL — поднимаю локальный сервер..."
  bash "$ROOT/scripts/run-and-debug.sh" --server-only
  curl -sf "${BASE_URL}/api/health" >/dev/null || { echo "API still down" >&2; exit 1; }
}

install_apks() {
  local api_arg=()
  if is_local_api; then
    log "adb reverse на $RECIPIENT_ADB_SERIAL (локальный API)"
    "$ADB" -s "$RECIPIENT_ADB_SERIAL" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || true
    api_arg=(-PapiBaseUrl=)
    log "Сборка APK (локально: emulator 10.0.2.2, phone 127.0.0.1)"
  else
    api_arg=(-PapiBaseUrl="$BASE_URL")
    log "Сборка APK (VPS API: $BASE_URL)"
  fi
  (
    cd "$ROOT"
    ./gradlew :androidApp:installDebug \
      "${api_arg[@]}" \
      -PdevShortcutsEnabled=true \
      -PdevPrimaryUsername="$SENDER_USERNAME" \
      -PdevPrimaryPassword="$SENDER_PASSWORD" \
      -PdevPrimaryLabel="$SENDER_USERNAME" \
      -PdevSecondaryUsername="$RECIPIENT_USERNAME" \
      -PdevSecondaryPassword="$RECIPIENT_PASSWORD" \
      -PdevSecondaryLabel="$RECIPIENT_USERNAME" \
      --quiet
  )

  local apk="$ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
  [[ -f "$apk" ]] || { echo "APK missing" >&2; exit 1; }
  "$ADB" -s "$SENDER_ADB_SERIAL" install -r "$apk" >/dev/null
  "$ADB" -s "$RECIPIENT_ADB_SERIAL" install -r "$apk" >/dev/null
}

maybe_clean_e2e_devices() {
  if [[ "$BASE_URL" != *127.0.0.1* && "$BASE_URL" != *localhost* ]]; then
    return 0
  fi
  if ! docker compose -f "$ROOT/../glagolitsa/server/docker-compose.yml" ps postgres 2>/dev/null | grep -q Up; then
    return 0
  fi
  local user_lc
  for user_lc in \
    "$(printf '%s' "$RECIPIENT_USERNAME" | tr '[:upper:]' '[:lower:]')" \
    "$(printf '%s' "$SENDER_USERNAME" | tr '[:upper:]' '[:lower:]')"; do
    log "Очистка stale devices у $user_lc (local postgres)"
    docker compose -f "$ROOT/../glagolitsa/server/docker-compose.yml" exec -T postgres \
      psql -U glagolitsa -d glagolitsa -c \
      "DELETE FROM devices WHERE account_id = (SELECT user_id FROM profiles WHERE lower(username)='${user_lc}');" \
      >/dev/null 2>&1 || true
  done
}

MAESTRO_ENV=(
  -e "SENDER_USERNAME=$SENDER_USERNAME"
  -e "SENDER_PASSWORD=$SENDER_PASSWORD"
  -e "RECIPIENT_USERNAME=$RECIPIENT_USERNAME"
  -e "RECIPIENT_PASSWORD=$RECIPIENT_PASSWORD"
  -e "E2E_MSG=$E2E_MSG"
)

ensure_api
maybe_clean_e2e_devices
if [[ "${SKIP_INSTALL:-}" == "1" ]]; then
  log "SKIP_INSTALL=1"
  "$ADB" -s "$RECIPIENT_ADB_SERIAL" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || true
else
  install_apks
fi

log "1/4 Prime recipient on phone ($RECIPIENT_USERNAME @ $RECIPIENT_ADB_SERIAL)"
prime_recipient_on_phone

log "2/4 Send on emulator ($SENDER_USERNAME @ $SENDER_ADB_SERIAL) msg=$E2E_MSG"
maestro_on "$SENDER_ADB_SERIAL" test "$ROOT/maestro/flows/dm-message-send-dual.yaml" "${MAESTRO_ENV[@]}"

log "3/4 Receive on phone (stay logged in)"
receive_on_phone

log "4/4 Read receipt on sender (double check)"
maestro_on "$SENDER_ADB_SERIAL" test "$ROOT/maestro/flows/dm-message-read-receipt-sender.yaml" "${MAESTRO_ENV[@]}"

if [[ "${SKIP_API_VERIFY:-}" != "1" ]]; then
  log "Post API verify"
  SENDER_USERNAME="$SENDER_USERNAME" SENDER_PASSWORD="$SENDER_PASSWORD" \
    RECIPIENT_USERNAME="$RECIPIENT_USERNAME" RECIPIENT_PASSWORD="$RECIPIENT_PASSWORD" \
    BASE_URL="$BASE_URL" \
    bash "$ROOT/scripts/verify-e2e-message.sh"
fi

log "E2E dual-device OK ($E2E_MSG)"
