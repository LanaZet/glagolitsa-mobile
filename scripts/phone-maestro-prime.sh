#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Вход получателя на телефоне через adb (fallback без Maestro driver на MIUI).
set -euo pipefail

SERIAL="${1:?serial}"
USER="${2:?username}"
PASS="${3:?password}"
APP_ID="com.glagolitsa.mobile"
MAIN="com.glagolitsa.MainActivity"

# shellcheck source=lib/android-ui.sh
source "$(cd "$(dirname "$0")/.." && pwd)/scripts/lib/android-ui.sh"

input_into_focused() {
  adb -s "$SERIAL" shell input text "$1"
}

log() { printf '[phone-prime] %s\n' "$*"; }

adb -s "$SERIAL" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
adb -s "$SERIAL" shell pm clear "$APP_ID" >/dev/null 2>&1 || true
sleep 1
adb -s "$SERIAL" shell am start -n "$APP_ID/$MAIN" >/dev/null
sleep 4

if tap_text "Войти как ${USER}"; then
  log "dev shortcut «Войти как ${USER}»"
  sleep 2
else
  log "tap Логин"
  tap_text "Логин" || adb -s "$SERIAL" shell input tap 540 820
  sleep 0.8
  input_into_focused "$USER"
  sleep 0.8

  log "tap Пароль"
  tap_text "Пароль" || adb -s "$SERIAL" shell input tap 540 980
  sleep 0.8
  input_into_focused "$PASS"
  sleep 0.8

  log "tap Войти"
  tap_text "Войти" || adb -s "$SERIAL" shell input tap 540 1180
  sleep 2
fi

log "Ждём «Чаты»..."
for _ in $(seq 1 24); do
  adb -s "$SERIAL" shell uiautomator dump /sdcard/glag_e2e.xml >/dev/null 2>&1 || true
  if adb -s "$SERIAL" shell cat /sdcard/glag_e2e.xml 2>/dev/null | grep -q 'text="Чаты"'; then
    log "OK — $USER на «Чаты»"
    exit 0
  fi
  sleep 5
done

log "FAIL — не дождались «Чаты»" >&2
exit 1
