#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Проверка текста сообщения на телефоне через uiautomator (fallback без Maestro на MIUI).
set -euo pipefail

SERIAL="${1:?serial}"
MSG="${2:?message}"
SENDER="${3:?sender username}"
APP_ID="com.glagolitsa.mobile"
MAIN="com.glagolitsa.MainActivity"

# shellcheck source=lib/android-ui.sh
source "$(cd "$(dirname "$0")/.." && pwd)/scripts/lib/android-ui.sh"

log() { printf '[phone-assert] %s\n' "$*"; }

foreground_app() {
  adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell am start -W -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER -n "$APP_ID/$MAIN" >/dev/null 2>&1 || true
  sleep 2
}

open_dm_with_sender() {
  local xml

  if tap_content_desc "Открыть чат ${SENDER}"; then
    log "tap content-desc «Открыть чат ${SENDER}»"
    sleep 2
    return 0
  fi
  if tap_content_desc "Открыть чат @${SENDER}"; then
    log "tap content-desc «Открыть чат @${SENDER}»"
    sleep 2
    return 0
  fi

  xml="$(dump_xml)"
  if echo "$xml" | grep -q 'text="Личные"'; then
    tap_text "Личные" || true
    sleep 2
    tap_content_desc "Открыть чат ${SENDER}" 2>/dev/null || \
      tap_content_desc "Открыть чат @${SENDER}" 2>/dev/null || true
    sleep 2
    return 0
  fi

  if echo "$xml" | grep -q 'text="Чаты"'; then
    tap_text "Личные" 2>/dev/null || adb -s "$SERIAL" shell input tap 260 380
    sleep 2
    tap_content_desc "Открыть чат ${SENDER}" 2>/dev/null || \
      tap_content_desc "Открыть чат @${SENDER}" 2>/dev/null || true
    sleep 2
    return 0
  fi

  if echo "$xml" | grep -qi "text=\"@${SENDER}\"" || echo "$xml" | grep -qi "text=\"${SENDER}\""; then
    tap_text "@${SENDER}" 2>/dev/null || tap_text "${SENDER}" 2>/dev/null || true
    sleep 2
    return 0
  fi

  if echo "$xml" | grep -q 'text="Поиск чатов и людей"'; then
    tap_text "Поиск чатов и людей" || true
    sleep 1
    adb -s "$SERIAL" shell input text "$SENDER"
    sleep 2
    tap_text "Написать @${SENDER}" 2>/dev/null || tap_text "@${SENDER}" 2>/dev/null || true
    sleep 2
    return 0
  fi

  return 1
}

log "foreground $APP_ID на $SERIAL"
foreground_app

for attempt in $(seq 1 36); do
  xml="$(dump_xml)"

  if echo "$xml" | grep -qF "$MSG"; then
    log "OK — виден текст: $MSG"
    exit 0
  fi

  if echo "$xml" | grep -q 'chat-message-input\|resource-id="chat-compose"\|text="Сообщение"'; then
    log "в чате, ждём sync ($attempt)..."
  elif echo "$xml" | grep -qF 'com.glagolitsa.mobile'; then
    log "в приложении, открываем DM с ${SENDER} ($attempt)..."
    open_dm_with_sender || true
  elif echo "$xml" | grep -q 'text="Facebook"\|text="TradingView"\|package="com.miui.home"'; then
    log "на лаунчере, возвращаем приложение ($attempt)..."
    foreground_app
  else
    log "неизвестный экран, foreground ($attempt)..."
    foreground_app
    open_dm_with_sender || true
  fi

  if echo "$xml" | grep -q 'text="Принять"'; then
    log "диалог смены ключа — «Принять»"
    tap_text "Принять" || true
    sleep 3
  elif echo "$xml" | grep -q 'Ключ безопасности изменился'; then
    tap_text "Принять" || true
    sleep 3
  fi
  sleep 3
done

log "FAIL — текст не найден: $MSG" >&2
adb -s "$SERIAL" shell uiautomator dump /sdcard/glag_e2e_fail.xml >/dev/null 2>&1 || true
log "UI dump: /sdcard/glag_e2e_fail.xml на устройстве" >&2
exit 1
