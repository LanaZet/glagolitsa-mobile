#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Остановка всего dev-окружения Glagolitsa.
#
# Использование:
#   ./scripts/stop-all.sh                 # Android + iOS session + sim/AVD + server + Postgres
#   ./scripts/stop-all.sh --keep-docker   # не останавливать Postgres в Docker
#   ./scripts/stop-all.sh --keep-ios-sim  # iOS app/session снести, Simulator.app оставить
#   ./scripts/stop-all.sh --keep-ios-data # terminate app, но не uninstall (сессия на sim остаётся)
#
# iOS session: terminate + uninstall com.glagolitsa.mobile на всех booted sim
# (UserDefaults/Keychain контейнера приложения), затем shutdown simulators.
#
# Переменные (опционально):
#   SERVER_DIR=./server или ../glagolitsa/server

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
if [[ -z "${SERVER_DIR:-}" ]]; then
  if [[ -d "$ROOT_DIR/server/cmd/server" ]]; then
    SERVER_DIR="$ROOT_DIR/server"
  else
    SERVER_DIR="$ROOT_DIR/../glagolitsa/server"
  fi
fi
APP_ID="com.glagolitsa.mobile"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$ANDROID_SDK/platform-tools/adb")"

SERVER_PID_FILE="$RUN_DIR/server.pid"
SERVER_DIR_MARKER_FILE="$RUN_DIR/server.dir"
EMULATOR_PID_FILE="$RUN_DIR/emulator.pid"
IOS_UDID_FILE="$RUN_DIR/ios-simulator.udid"

KEEP_DOCKER=false
KEEP_IOS_SIM=false
KEEP_IOS_DATA=false
for arg in "$@"; do
  case "$arg" in
    --keep-docker) KEEP_DOCKER=true ;;
    --keep-ios-sim) KEEP_IOS_SIM=true ;;
    --keep-ios-data) KEEP_IOS_DATA=true ;;
    -h|--help)
      sed -n '6,18p' "$0"
      exit 0
      ;;
  esac
done

log() { printf '\033[1;34m[glagolitsa]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[glagolitsa]\033[0m %s\n' "$*"; }

kill_pid_file() {
  local label="$1"
  local file="$2"
  local signal="${3:-TERM}"

  if [[ ! -f "$file" ]]; then
    return 0
  fi

  local pid
  pid="$(cat "$file")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "-$signal" "$pid" 2>/dev/null || true
    log "$label остановлен (pid $pid)"
  else
    warn "$label уже не запущен (pid $pid из $file)"
  fi
  rm -f "$file"
}

# All booted simulator UDIDs (one per line).
ios_booted_udids() {
  xcrun simctl list devices booted 2>/dev/null \
    | sed -n 's/.*(\([A-F0-9-]\{36\}\)).*/\1/p' \
    | sort -u
}

stop_android_app() {
  if ! command -v "$ADB" >/dev/null 2>&1 && [[ ! -x "$ADB" ]]; then
    return 0
  fi
  if "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" { found=1 } END { exit !found }'; then
    "$ADB" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    log "Приложение $APP_ID остановлено на Android"
  fi
}

# Signal/Mattermost-style tear-down: kill process, then wipe app container (local session).
stop_ios_session() {
  if ! command -v xcrun >/dev/null 2>&1; then
    warn "xcrun недоступен — iOS session не трогаем"
    return 0
  fi
  if ! xcrun simctl help >/dev/null 2>&1; then
    warn "simctl недоступен — iOS session не трогаем"
    return 0
  fi

  local udids=()
  local u
  # Marker from run-and-debug --ios
  if [[ -f "$IOS_UDID_FILE" ]]; then
    u="$(tr -d '[:space:]' <"$IOS_UDID_FILE")"
    [[ -n "$u" ]] && udids+=("$u")
  fi
  # All currently booted sims
  while IFS= read -r u; do
    [[ -n "$u" ]] || continue
    local seen=0
    local x
    for x in "${udids[@]+"${udids[@]}"}"; do
      [[ "$x" == "$u" ]] && seen=1 && break
    done
    [[ "$seen" -eq 0 ]] && udids+=("$u")
  done < <(ios_booted_udids)

  if [[ ${#udids[@]} -eq 0 ]]; then
    log "iOS: нет booted Simulator — session нечего чистить"
    return 0
  fi

  for u in "${udids[@]}"; do
    xcrun simctl terminate "$u" "$APP_ID" >/dev/null 2>&1 || true
    if [[ "$KEEP_IOS_DATA" == true ]]; then
      log "iOS: terminate $APP_ID на $u (data сохранена --keep-ios-data)"
    else
      # Uninstall drops sandbox: NSUserDefaults session, local SQLite, etc.
      xcrun simctl uninstall "$u" "$APP_ID" >/dev/null 2>&1 || true
      log "iOS: session сброшена (uninstall $APP_ID) на $u"
    fi
  done

  # Host-side leftover debug processes (rare)
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "Glagolitsa.app/Glagolitsa" 2>/dev/null || true
  fi
}

stop_ios_simulator() {
  if [[ "$KEEP_IOS_SIM" == true ]]; then
    warn "iOS Simulator оставлен (--keep-ios-sim)"
    rm -f "$IOS_UDID_FILE"
    return 0
  fi
  if ! command -v xcrun >/dev/null 2>&1; then
    return 0
  fi

  local u
  # Prefer explicit marker, then every booted device
  if [[ -f "$IOS_UDID_FILE" ]]; then
    u="$(tr -d '[:space:]' <"$IOS_UDID_FILE")"
    if [[ -n "$u" ]]; then
      xcrun simctl shutdown "$u" >/dev/null 2>&1 || true
      log "iOS Simulator shutdown: $u"
    fi
  fi
  while IFS= read -r u; do
    [[ -n "$u" ]] || continue
    xcrun simctl shutdown "$u" >/dev/null 2>&1 || true
    log "iOS Simulator shutdown: $u"
  done < <(ios_booted_udids)

  # Close Simulator.app UI if nothing left booted
  if [[ -z "$(ios_booted_udids)" ]]; then
    if command -v osascript >/dev/null 2>&1; then
      osascript -e 'tell application "Simulator" to quit' >/dev/null 2>&1 || true
    fi
    if command -v pkill >/dev/null 2>&1; then
      pkill -x Simulator 2>/dev/null || true
    fi
    log "Simulator.app закрыт"
  fi

  rm -f "$IOS_UDID_FILE"
}

stop_emulator() {
  kill_pid_file "Эмулятор (pid-файл)" "$EMULATOR_PID_FILE"

  if command -v "$ADB" >/dev/null 2>&1; then
    "$ADB" emu kill >/dev/null 2>&1 || true
  fi

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "emulator.*-avd" 2>/dev/null || true
    pkill -f "qemu-system" 2>/dev/null || true
  fi

  log "Эмулятор(ы) остановлены"
}

stop_server() {
  kill_pid_file "Go-сервер (pid-файл)" "$SERVER_PID_FILE"
  rm -f "$SERVER_DIR_MARKER_FILE"

  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids="$(lsof -ti :8080 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
      echo "$pids" | xargs kill -9 2>/dev/null || true
      log "Процессы на порту :8080 остановлены"
    fi
  fi

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "glagolitsa/server/cmd/server" 2>/dev/null || true
    pkill -f "go run ./cmd/server" 2>/dev/null || true
  fi

  log "Go-сервер остановлен"
}

stop_postgres() {
  if [[ "$KEEP_DOCKER" == true ]]; then
    warn "Postgres в Docker оставлен запущенным (--keep-docker)"
    return 0
  fi

  if [[ ! -f "$SERVER_DIR/docker-compose.yml" ]]; then
    warn "docker-compose.yml не найден, пропускаем Postgres"
    return 0
  fi

  if command -v docker >/dev/null 2>&1; then
    docker compose -f "$SERVER_DIR/docker-compose.yml" stop >/dev/null 2>&1 || true
    log "PostgreSQL (docker compose stop) остановлен"
  fi
}

log "Останавливаем все процессы Glagolitsa..."

stop_android_app
stop_ios_session
stop_emulator
stop_ios_simulator
stop_server
stop_postgres

log "Готово. Dev-окружение остановлено (Android + iOS session + server)."
log "Android: ./scripts/run-and-debug.sh"
log "iOS:     ./scripts/run-and-debug.sh --ios"
