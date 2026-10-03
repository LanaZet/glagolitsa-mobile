#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Отправка в уже открытый чат на эмуляторе через adb (без Maestro / без clearState).
set -euo pipefail

SERIAL="${1:?serial}"
MSG="${2:?message}"
RECIPIENT="${3:-Polo}"
APP_ID="com.glagolitsa.mobile"
MAIN="com.glagolitsa.MainActivity"

# shellcheck source=lib/android-ui.sh
source "$(cd "$(dirname "$0")/.." && pwd)/scripts/lib/android-ui.sh"

log() { printf '[emulator-send] %s\n' "$*"; }

in_chat_compose() {
  dump_xml | grep -q 'resource-id="chat-compose"'
}

open_dm_with_recipient() {
  local xml

  if in_chat_compose; then
    log "уже в чате (chat-compose)"
    return 0
  fi

  if tap_content_desc "Открыть чат ${RECIPIENT}"; then
    log "tap «Открыть чат ${RECIPIENT}»"
    sleep 2
    return 0
  fi
  if tap_content_desc "Открыть чат @${RECIPIENT}"; then
    log "tap «Открыть чат @${RECIPIENT}»"
    sleep 2
    return 0
  fi

  xml="$(dump_xml)"
  if echo "$xml" | grep -q 'text="Чаты"'; then
    tap_text "Личные" 2>/dev/null || adb -s "$SERIAL" shell input tap 260 380
    sleep 2
    tap_content_desc "Открыть чат ${RECIPIENT}" 2>/dev/null || \
      tap_content_desc "Открыть чат @${RECIPIENT}" 2>/dev/null || true
    sleep 2
  fi

  xml="$(dump_xml)"
  if echo "$xml" | grep -q 'resource-id="chat-compose"'; then
    return 0
  fi

  log "поиск DM @${RECIPIENT}"
  tap_text "Поиск чатов и людей" || adb -s "$SERIAL" shell input tap 540 420
  sleep 1
  adb -s "$SERIAL" shell input text "$RECIPIENT"
  sleep 2
  tap_text "Написать @${RECIPIENT}" 2>/dev/null || tap_text "@${RECIPIENT}" 2>/dev/null || true
  sleep 2
}

adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -n "$APP_ID/$MAIN" >/dev/null 2>&1 || true
sleep 2

open_dm_with_recipient || true
sleep 1

if tap_text "Принять"; then
  log "диалог ключа — «Принять»"
  sleep 1
fi

if ! in_chat_compose; then
  log "FAIL — не в чате (нет chat-compose). Откройте DM с @${RECIPIENT} на эмуляторе." >&2
  exit 1
fi

log "tap chat-message-input"
tap_resource_id "chat-message-input" || {
  log "FAIL — поле ввода не найдено (chat-message-input)" >&2
  exit 1
}
sleep 0.3

log "type: $MSG"
# adb input text: только [A-Za-z0-9_], без дефисов
adb -s "$SERIAL" shell input text "$MSG"
sleep 0.3

log "tap chat-send"
tap_resource_id "chat-send" || {
  log "FAIL — кнопка отправки не найдена (chat-send)" >&2
  exit 1
}

MAX_WAIT_ATTEMPTS="${MAX_WAIT_ATTEMPTS:-5}"
WAIT_INTERVAL_SEC="${WAIT_INTERVAL_SEC:-2}"
for attempt in $(seq 1 "$MAX_WAIT_ATTEMPTS"); do
  xml="$(dump_xml)"
  if echo "$xml" | grep -qF "$MSG"; then
    log "OK — отправлено и видно на эмуляторе: $MSG"
    exit 0
  fi
  log "ждём появления в UI ($attempt/$MAX_WAIT_ATTEMPTS)..."
  sleep "$WAIT_INTERVAL_SEC"
done

log "FAIL — сообщение не видно на эмуляторе за $((MAX_WAIT_ATTEMPTS * WAIT_INTERVAL_SEC))с: $MSG" >&2
exit 1
