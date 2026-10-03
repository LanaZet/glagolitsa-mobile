#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Локальный dev-цикл: Postgres + Go API + эмулятор/симулятор + debug-сборка.
#
# Тот же локальный сервер (Docker Postgres + Go :8080) для Android и iOS.
#
#   ./scripts/run-and-debug.sh                 # Android: сервер + AVD + APK
#   ./scripts/run-and-debug.sh --ios           # iOS: сервер + Simulator + app
#   ./scripts/run-and-debug.sh --both          # сервер + Android + iOS
#   ./scripts/run-and-debug.sh --server-only   # только Postgres + Go
#   ./scripts/run-and-debug.sh --server-only --restart-server
#   ./scripts/run-and-debug.sh --app-only      # без сервера (нужен уже запущенный API)
#   ./scripts/run-and-debug.sh --app-only --ios
#   ./scripts/run-and-debug.sh --build-only    # только сборка (Android APK / iOS app)
#   ./scripts/run-and-debug.sh --remote        # VPS из gradle.properties
#   ./scripts/run-and-debug.sh --local         # явно локальный API
#   ./scripts/run-and-debug.sh --dev-shortcuts # Marco/Polo shortcuts (Android)
#   ./scripts/stop-all.sh
#
# API URL:
#   Android emulator → http://10.0.2.2:8080  (хост Mac)
#   iOS Simulator    → http://127.0.0.1:8080  (тот же Go на хосте)
#
# Переменные:
#   IOS_SIMULATOR_NAME   default: iPhone 17 Pro
#   IOS_API_BASE_URL     override for iOS local (default http://127.0.0.1:8080)
#   AVD_NAME             Android AVD
#   SERVER_DIR           path to Go server module
#
# APK для внешних тестеров (публичный API, без shortcuts):
#   ./scripts/build-prerelease-apk.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
export GLAGOLITSA_RUN_DIR="$RUN_DIR"
if [[ -z "${SERVER_DIR:-}" ]]; then
  if [[ -d "$ROOT_DIR/server/cmd/server" ]]; then
    SERVER_DIR="$ROOT_DIR/server"
  else
    SERVER_DIR="$ROOT_DIR/../glagolitsa/server"
  fi
fi
AVD_NAME="${AVD_NAME:-quorum_api34}"
SERVER_ADDR="${SERVER_ADDR:-:8080}"
APP_ID="com.glagolitsa.mobile"
MAIN_ACTIVITY="com.glagolitsa.MainActivity"
IOS_DERIVED_DATA="$RUN_DIR/ios-derived"

# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
# shellcheck source=lib/android-env.sh
source "$ROOT_DIR/scripts/lib/android-env.sh"
# shellcheck source=lib/ios-env.sh
source "$ROOT_DIR/scripts/lib/ios-env.sh"
# shellcheck source=lib/dev-accounts.sh
source "$ROOT_DIR/scripts/lib/dev-accounts.sh"

SERVER_PID_FILE="$RUN_DIR/server.pid"
SERVER_DIR_MARKER_FILE="$RUN_DIR/server.dir"
EMULATOR_PID_FILE="$RUN_DIR/emulator.pid"
SERVER_LOG="$RUN_DIR/server.log"
BUILD_SCRIPT="$ROOT_DIR/scripts/build-dev-apk.sh"
APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

mkdir -p "$RUN_DIR"

log() { glagolitsa_log "$*"; }
warn() { glagolitsa_warn "$*"; }
err() { glagolitsa_err "$*"; }

wait_for_postgres() {
  local attempts=30
  for ((i = 1; i <= attempts; i++)); do
    if docker compose -f "$SERVER_DIR/docker-compose.yml" exec -T postgres pg_isready -U glagolitsa -d glagolitsa >/dev/null 2>&1; then
      return 0
    fi
    if pg_isready -h localhost -p 5432 >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  err "PostgreSQL не отвечает на :5432"
  return 1
}

start_postgres() {
  [[ -f "$SERVER_DIR/docker-compose.yml" ]] || { err "Не найден $SERVER_DIR/docker-compose.yml"; exit 1; }
  log "Поднимаем PostgreSQL (Docker — общий для Android и iOS)..."
  docker compose -f "$SERVER_DIR/docker-compose.yml" up -d
  wait_for_postgres
  log "PostgreSQL готов"
}

server_pid_alive() {
  [[ -f "$SERVER_PID_FILE" ]] && kill -0 "$(cat "$SERVER_PID_FILE")" 2>/dev/null
}

server_dir_marker_matches() {
  [[ -f "$SERVER_DIR_MARKER_FILE" ]] && [[ "$(cat "$SERVER_DIR_MARKER_FILE")" == "$SERVER_DIR" ]]
}

stop_server_processes() {
  if server_pid_alive; then
    local pid
    pid="$(cat "$SERVER_PID_FILE")"
    kill "$pid" 2>/dev/null || true
    sleep 1
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$SERVER_PID_FILE" "$SERVER_DIR_MARKER_FILE"

  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids="$(lsof -ti :8080 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
      echo "$pids" | xargs kill -9 2>/dev/null || true
    fi
  fi
}

start_server() {
  log "SERVER_DIR: $SERVER_DIR"

  if [[ "$RESTART_SERVER" == false ]] && server_pid_alive && server_dir_marker_matches; then
    log "Go-сервер уже запущен (pid $(cat "$SERVER_PID_FILE")) — переиспользуем"
    return 0
  fi

  if [[ "$RESTART_SERVER" == true ]]; then
    log "Перезапускаем Go-сервер из текущего SERVER_DIR..."
  elif server_pid_alive; then
    warn "Найден старый Go-сервер без метки текущего SERVER_DIR — перезапускаем"
  fi

  stop_server_processes

  log "Запускаем Go-сервер на $SERVER_ADDR..."
  (
    cd "$SERVER_DIR"
    export ADDR="$SERVER_ADDR"
    export SEED_DEV_USERS="${SEED_DEV_USERS:-true}"
    export RATE_LIMIT_RELAXED="${RATE_LIMIT_RELAXED:-true}"
    export DATABASE_URL="${DATABASE_URL:-postgres://glagolitsa:glagolitsa@localhost:5432/glagolitsa?sslmode=disable}"
    nohup go run ./cmd/server >"$SERVER_LOG" 2>&1 &
    echo $! >"$SERVER_PID_FILE"
    printf '%s\n' "$SERVER_DIR" >"$SERVER_DIR_MARKER_FILE"
  )

  for ((i = 1; i <= 30; i++)); do
    if curl -sf "http://localhost:8080/api/health" >/dev/null 2>&1; then
      log "Go-сервер готов → http://localhost:8080"
      log "  Android emulator: http://10.0.2.2:8080"
      log "  iOS Simulator:    http://127.0.0.1:8080"
      log "Тестовые аккаунты: $(glagolitsa_dev_account_summary)"
      return 0
    fi
    sleep 1
  done

  err "Сервер не поднялся. Лог: $SERVER_LOG"
  tail -20 "$SERVER_LOG" >&2 || true
  exit 1
}

pin_adb_target() {
  local emulator phone
  emulator="$(glagolitsa_android_first_emulator_serial)"
  phone="$(glagolitsa_android_first_physical_device_serial)"

  if [[ -n "$emulator" ]]; then
    export ANDROID_SERIAL="$emulator"
    log "Целевое устройство: эмулятор $emulator"
    return 0
  fi
  if [[ -n "$phone" ]]; then
    export ANDROID_SERIAL="$phone"
    log "Целевое устройство: телефон $phone"
    return 0
  fi
  unset ANDROID_SERIAL
}

start_android_emulator() {
  init_android_env
  local emulator_serial
  emulator_serial="$(glagolitsa_android_first_emulator_serial)"
  if [[ -n "$emulator_serial" ]]; then
    pin_adb_target
    log "Android-эмулятор уже online: $emulator_serial"
    return 0
  fi

  [[ -x "$EMULATOR" ]] || { err "Не найден emulator: $EMULATOR"; exit 1; }

  log "Запускаем Android-эмулятор $AVD_NAME..."
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load >"$RUN_DIR/emulator.log" 2>&1 &
  echo $! >"$EMULATOR_PID_FILE"

  "$ADB" wait-for-device
  for ((i = 1; i <= 60; i++)); do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      log "Android-эмулятор загружен"
      return 0
    fi
    sleep 2
  done
  err "Android-эмулятор не завершил загрузку за 2 минуты"
  exit 1
}

# --- API base URL -----------------------------------------------------------

resolve_android_api_base_url() {
  if [[ -n "${API_BASE_URL:-}" ]]; then printf '%s' "$API_BASE_URL"; return; fi
  if [[ -n "${CLI_API_BASE_URL:-}" ]]; then printf '%s' "$CLI_API_BASE_URL"; return; fi
  if [[ "${USE_LOCAL_API:-}" == "1" ]]; then printf '%s' "http://10.0.2.2:8080"; return; fi
  if [[ "${USE_REMOTE_API:-}" == "1" ]]; then
    local from_gradle
    from_gradle="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
    [[ -n "$from_gradle" ]] || { err "USE_REMOTE_API=1, но apiBaseUrl не задан в local.properties или gradle.properties"; exit 1; }
    printf '%s' "$from_gradle"
    return
  fi
  # Default full/local cycle: Android emulator → host Go
  printf '%s' "http://10.0.2.2:8080"
}

resolve_ios_api_base_url() {
  if [[ -n "${API_BASE_URL:-}" ]]; then printf '%s' "$API_BASE_URL"; return; fi
  if [[ -n "${CLI_API_BASE_URL:-}" ]]; then printf '%s' "$CLI_API_BASE_URL"; return; fi
  if [[ "${USE_REMOTE_API:-}" == "1" ]]; then
    local from_gradle
    from_gradle="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
    [[ -n "$from_gradle" ]] || { err "USE_REMOTE_API=1, но apiBaseUrl не задан в local.properties или gradle.properties"; exit 1; }
    printf '%s' "$from_gradle"
    return
  fi
  # iOS Simulator → same local Go as Docker/run-and-debug (host loopback)
  glagolitsa_ios_local_api_url
}

# Back-compat name used by older helpers
resolve_api_base_url() {
  if [[ "$WANT_IOS" == true && "$WANT_ANDROID" != true ]]; then
    resolve_ios_api_base_url
  else
    resolve_android_api_base_url
  fi
}

# --- Android ----------------------------------------------------------------

build_app_apk() {
  local api_url build_args=()
  api_url="$(resolve_android_api_base_url)"

  [[ -x "$BUILD_SCRIPT" ]] || { err "Не найден $BUILD_SCRIPT"; exit 1; }

  if glagolitsa_is_remote_api_url "$api_url"; then
    log "Удалённый API"
    glagolitsa_check_api_reachable "$api_url" || true
  else
    log "Локальный API (Android emulator → host Go :8080)"
  fi

  log "Сборка debug APK"
  log "API: $api_url"
  build_args=(--api "$api_url")
  [[ "${DEV_SHORTCUTS_ENABLED:-false}" == "true" ]] && build_args+=(--dev-shortcuts)
  "$BUILD_SCRIPT" "${build_args[@]}"
}

install_built_apk() {
  [[ -f "$APK_PATH" ]] || { err "APK не найден: $APK_PATH"; exit 1; }
  pin_adb_target
  log "Устанавливаем APK на ${ANDROID_SERIAL:-<default>}..."
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$ADB" -s "$ANDROID_SERIAL" install -r "$APK_PATH" >/dev/null
  else
    "$ADB" install -r "$APK_PATH" >/dev/null
  fi
}

install_and_launch_android() {
  build_app_apk
  install_built_apk
  pin_adb_target
  log "Запускаем Android-приложение..."
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    "$ADB" -s "$ANDROID_SERIAL" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
  else
    "$ADB" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    "$ADB" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
  fi
  log "Android готово. API: $(resolve_android_api_base_url)"
  [[ "${DEV_SHORTCUTS_ENABLED:-false}" == "true" ]] && log "Dev shortcuts: $(glagolitsa_dev_account_summary)"
}

# --- iOS --------------------------------------------------------------------

install_and_launch_ios() {
  local udid app_path api_url
  api_url="$(resolve_ios_api_base_url)"

  glagolitsa_ios_require_tools || exit 1

  udid="$(glagolitsa_ios_resolve_simulator_udid)"
  [[ -n "$udid" ]] || { err "Нет доступного iOS Simulator. Создайте device в Xcode → Devices."; exit 1; }
  glagolitsa_ios_boot_simulator "$udid" || exit 1

  if glagolitsa_is_remote_api_url "$api_url"; then
    log "iOS → удалённый API: $api_url"
    glagolitsa_check_api_reachable "$api_url" || true
  else
    log "iOS Simulator → локальный Go (тот же Docker/сервер): $api_url"
    glagolitsa_check_api_reachable "$api_url" || warn "Подняли сервер? ./scripts/run-and-debug.sh --server-only"
  fi

  # Client auto-picks 127.0.0.1:8080 on simulator; env override if non-default.
  if [[ "$api_url" != "http://127.0.0.1:8080" && "$api_url" != "http://localhost:8080" ]]; then
    export GLAGOLITSA_API_BASE_URL="$api_url"
    log "GLAGOLITSA_API_BASE_URL=$api_url (для Xcode; sim install uses binary default unless set in scheme)"
  fi

  glagolitsa_ios_build_app "$ROOT_DIR" "$IOS_DERIVED_DATA" "Debug" || exit 1
  app_path="$(glagolitsa_ios_find_app_bundle "$IOS_DERIVED_DATA")"
  [[ -n "$app_path" ]] || { err "Glagolitsa.app не найден в $IOS_DERIVED_DATA"; exit 1; }

  glagolitsa_ios_install_and_launch "$udid" "$app_path" "$APP_ID" || exit 1
  log "iOS готово. Simulator UDID=$udid API=$api_url"
}

build_ios_only() {
  glagolitsa_ios_require_tools || exit 1
  glagolitsa_ios_build_app "$ROOT_DIR" "$IOS_DERIVED_DATA" "Debug" || exit 1
  log "iOS app: $(glagolitsa_ios_find_app_bundle "$IOS_DERIVED_DATA")"
}

# --- CLI --------------------------------------------------------------------

MODE="full"
CLI_API_BASE_URL=""
USE_LOCAL_API=""
USE_REMOTE_API=""
DEV_SHORTCUTS_ENABLED="${DEV_SHORTCUTS_ENABLED:-false}"
RESTART_SERVER=false
WANT_ANDROID=false
WANT_IOS=false
PLATFORM_EXPLICIT=false

while (($#)); do
  case "$1" in
    --stop) shift; exec "$ROOT_DIR/scripts/stop-all.sh" "$@" ;;
    --server-only) MODE="server" ;;
    --app-only) MODE="app" ;;
    --build-only) MODE="build" ;;
    --remote) USE_REMOTE_API=1 ;;
    --local) USE_LOCAL_API=1 ;;
    --dev-shortcuts) DEV_SHORTCUTS_ENABLED=true ;;
    --restart-server) RESTART_SERVER=true ;;
    --ios)
      WANT_IOS=true
      PLATFORM_EXPLICIT=true
      ;;
    --android)
      WANT_ANDROID=true
      PLATFORM_EXPLICIT=true
      ;;
    --both)
      WANT_ANDROID=true
      WANT_IOS=true
      PLATFORM_EXPLICIT=true
      ;;
    --api)
      CLI_API_BASE_URL="${2:?укажи URL после --api}"
      shift
      ;;
    -h|--help)
      sed -n '6,34p' "$0"
      exit 0
      ;;
    *)
      err "Неизвестный аргумент: $1 (см. --help)"
      exit 1
      ;;
  esac
  shift
done

# Default platform = Android (historical behaviour) unless --ios / --both.
if [[ "$PLATFORM_EXPLICIT" == false ]]; then
  WANT_ANDROID=true
  WANT_IOS=false
fi

run_server_stack() {
  start_postgres
  start_server
}

case "$MODE" in
  server)
    run_server_stack
    ;;
  app)
    if [[ "$WANT_ANDROID" == true ]]; then
      start_android_emulator
      install_and_launch_android
    fi
    if [[ "$WANT_IOS" == true ]]; then
      install_and_launch_ios
    fi
    ;;
  build)
    if [[ "$WANT_ANDROID" == true ]]; then
      build_app_apk
    fi
    if [[ "$WANT_IOS" == true ]]; then
      build_ios_only
    fi
    ;;
  full)
    run_server_stack
    if [[ "$WANT_ANDROID" == true ]]; then
      start_android_emulator
      install_and_launch_android
    fi
    if [[ "$WANT_IOS" == true ]]; then
      install_and_launch_ios
    fi
    ;;
esac

log ""
log "Локальный API (Docker Postgres + Go) общий:"
log "  host:              http://localhost:8080"
log "  Android emulator:  http://10.0.2.2:8080"
log "  iOS Simulator:     http://127.0.0.1:8080"
log "  tail -f $SERVER_LOG"
log "  ./scripts/stop-all.sh"
# Use if/fi (not `[[ ]] &&`) so a false branch does not become the script exit code
# under `set -e` / as the last statement — that broke Sun Wukong local builds:
# ensure_local_server → run-and-debug --server-only exited 1 and skipped APK install.
if [[ "$WANT_ANDROID" == true ]]; then
  log "Showkase: иконка «Glagolitsa UI» на лаунчере Android"
fi
if [[ "$WANT_IOS" == true ]]; then
  log "iOS: docs/ios/device-install.md · derived: $IOS_DERIVED_DATA"
fi
