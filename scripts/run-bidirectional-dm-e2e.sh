#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E: Marco↔Polo в обе стороны (локальный API, эмулятор=Marco, телефон=Polo).
#
#   ./scripts/run-bidirectional-dm-e2e.sh
#   SENDER_ADB_SERIAL=emulator-5554 RECIPIENT_ADB_SERIAL=<adb-serial> ./scripts/run-bidirectional-dm-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E_ROOT="$ROOT"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"
# shellcheck source=e2e-dm-security.sh
source "$ROOT/scripts/e2e-dm-security.sh"
# shellcheck source=lib/android-env.sh
source "$ROOT/scripts/lib/android-env.sh"

if [[ -d "${HOME}/.maestro/bin" ]]; then PATH="${HOME}/.maestro/bin:${PATH}"; fi
if [[ -d "${HOME}/Library/Android/sdk/platform-tools" ]]; then PATH="${HOME}/Library/Android/sdk/platform-tools:${PATH}"; fi

init_android_env
command -v maestro >/dev/null 2>&1 || { echo "maestro not found" >&2; exit 1; }
[[ -x "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

SENDER_SERIAL="${SENDER_ADB_SERIAL:-${TEST_ADB_SERIAL:-$(glagolitsa_android_first_emulator_serial || true)}}"
RECIPIENT_SERIAL="${RECIPIENT_ADB_SERIAL:-${BOB_ADB_SERIAL:-$(glagolitsa_android_first_physical_device_serial || true)}}"
[[ -n "$SENDER_SERIAL" ]] || { echo "Нет эмулятора" >&2; exit 1; }
[[ -n "$RECIPIENT_SERIAL" ]] || { echo "Нет телефона" >&2; exit 1; }

BASE_URL="${BASE_URL:-$E2E_LOCAL_BASE_URL}"
SENDER_USERNAME="${SENDER_USERNAME:-$E2E_LOCAL_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_LOCAL_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_LOCAL_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_LOCAL_RECIPIENT_PASSWORD}"

RUN_ID="${RUN_ID:-$(date +%s)}"
MSG_M2P="m2p_${RUN_ID}"
MSG_P2M="p2m_${RUN_ID}"
TOTAL_STEPS=7

log() { printf '[bidir-e2e] %s\n' "$*"; }

maestro_on() {
  maestro --udid "$1" "${@:2}"
}

login_shortcut() {
  local serial="$1" user="$2" pass="$3"
  log "login $user @ $serial"
  maestro_on "$serial" test "$ROOT/maestro/flows/dm-message-dev-login.yaml" \
    -e "USERNAME=$user" \
    -e "PASSWORD=$pass"
}

open_dm() {
  local serial="$1" partner="$2"
  log "open DM $partner @ $serial"
  maestro_on "$serial" test "$ROOT/maestro/flows/dm-message-open-chat-navigate.yaml" \
    -e "PARTNER_USERNAME=$partner"
}

send_in_open_chat() {
  local serial="$1" msg="$2"
  log "send '$msg' @ $serial"
  maestro_on "$serial" test "$ROOT/maestro/flows/dm-message-send-open-chat.yaml" \
    -e "E2E_MSG=$msg"
}

assert_message() {
  local serial="$1" msg="$2" sender="$3"
  log "assert '$msg' from $sender @ $serial"
  bash "$ROOT/scripts/phone-adb-assert-message.sh" "$serial" "$msg" "$sender"
}

curl -sf "${BASE_URL}/api/health" >/dev/null || { echo "API down: $BASE_URL" >&2; exit 1; }
adb -s "$RECIPIENT_SERIAL" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || true

APK="$ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
if [[ "${SKIP_INSTALL:-}" != "1" && -f "$APK" ]]; then
  log "install APK"
  adb -s "$SENDER_SERIAL" install -r "$APK" >/dev/null
  adb -s "$RECIPIENT_SERIAL" install -r "$APK" >/dev/null
fi

log "devices: sender=$SENDER_SERIAL ($SENDER_USERNAME) recipient=$RECIPIENT_SERIAL ($RECIPIENT_USERNAME)"
log "1/$TOTAL_STEPS login $SENDER_USERNAME"
login_shortcut "$SENDER_SERIAL" "$SENDER_USERNAME" "$SENDER_PASSWORD"
log "2/$TOTAL_STEPS login $RECIPIENT_USERNAME"
login_shortcut "$RECIPIENT_SERIAL" "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD"

log "3/$TOTAL_STEPS $SENDER_USERNAME → $RECIPIENT_USERNAME: $MSG_M2P"
open_dm "$SENDER_SERIAL" "$RECIPIENT_USERNAME"
send_in_open_chat "$SENDER_SERIAL" "$MSG_M2P"
e2e_verify_relay_encryption "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD" "$MSG_M2P"
open_dm "$RECIPIENT_SERIAL" "$SENDER_USERNAME"
assert_message "$RECIPIENT_SERIAL" "$MSG_M2P" "$SENDER_USERNAME"
e2e_assert_no_encrypted_placeholder "$RECIPIENT_SERIAL" "$RECIPIENT_USERNAME"
e2e_assert_decrypt_log "$RECIPIENT_SERIAL" "$MSG_M2P" 60 || true

log "4/$TOTAL_STEPS $RECIPIENT_USERNAME → $SENDER_USERNAME: $MSG_P2M"
open_dm "$RECIPIENT_SERIAL" "$SENDER_USERNAME"
send_in_open_chat "$RECIPIENT_SERIAL" "$MSG_P2M"
e2e_verify_relay_encryption "$SENDER_USERNAME" "$SENDER_PASSWORD" "$MSG_P2M"
open_dm "$SENDER_SERIAL" "$RECIPIENT_USERNAME"
assert_message "$SENDER_SERIAL" "$MSG_P2M" "$RECIPIENT_USERNAME"
e2e_assert_no_encrypted_placeholder "$SENDER_SERIAL" "$SENDER_USERNAME"

log "OK bidirectional DM: $MSG_M2P / $MSG_P2M"
