# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# shellcheck shell=bash
# Хелперы E2E: проверка шифрования relay и отсутствия placeholder в UI.
# Подключать после e2e-defaults.sh: source "$(dirname "$0")/e2e-dm-security.sh"

e2e_verify_relay_encryption() {
  local recipient_username="$1"
  local recipient_password="$2"
  local message="$3"
  local root="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
  RECIPIENT_USERNAME="$recipient_username" \
    RECIPIENT_PASSWORD="$recipient_password" \
    BASE_URL="${BASE_URL:?}" \
    E2E_MSG="$message" \
    bash "$root/scripts/verify-e2e-relay-encryption.sh"
}

e2e_assert_no_encrypted_placeholder() {
  local serial="$1"
  local label="${2:-$serial}"
  if adb -s "$serial" shell uiautomator dump /sdcard/glag_e2e.xml >/dev/null 2>&1; then
    if adb -s "$serial" shell cat /sdcard/glag_e2e.xml 2>/dev/null | grep -q 'Зашифрованное сообщение'; then
      printf '[e2e-dm-security] FAIL — placeholder «Зашифрованное сообщение» на %s\n' "$label" >&2
      return 1
    fi
  fi
  return 0
}

e2e_assert_decrypt_log() {
  local serial="$1"
  local message="$2"
  local timeout_sec="${3:-60}"
  local root="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
  bash "$root/scripts/phone-adb-assert-decrypt-log.sh" "$serial" "$message" "$timeout_sec"
}