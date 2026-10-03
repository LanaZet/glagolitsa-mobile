#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Android continuous install (hot reload) on the emulator only.
#
# Usage:
#   ./scripts/hot-reload.sh --public            # public API watch (default for dual-device)
#   ./scripts/hot-reload.sh --local             # local 10.0.2.2 + Marco/Polo shortcuts
#   ./scripts/hot-reload.sh --api URL           # custom API watch without dev shortcuts
#   ./scripts/hot-reload.sh --app-only --public # skip bootstrapping emulator
#   ./scripts/hot-reload.sh --no-restart        # install only
#   ./scripts/hot-reload.sh --stop              # stop background watcher
#   ./scripts/hot-reload.sh --status            # is watcher running?
#
# Does NOT wipe app data on reinstall (session survives).
# Pins adb to the first emulator so a USB phone is never overwritten.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
WATCHER_PID_FILE="$RUN_DIR/hot-reload.pid"
WATCHER_LOG_FILE="$RUN_DIR/hot-reload.log"
APP_ID="com.glagolitsa.mobile"
MAIN_ACTIVITY="com.glagolitsa.MainActivity"

source "$ROOT_DIR/scripts/lib/android-env.sh"
init_android_env
# shellcheck source=lib/dev-accounts.sh
source "$ROOT_DIR/scripts/lib/dev-accounts.sh"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"

log() { printf '\033[1;34m[hot-reload]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[hot-reload]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[hot-reload]\033[0m %s\n' "$*" >&2; }

RESTART_APP=1
MODE="full"
SERVER_TARGET="public"
CLI_API_BASE_URL=""
BACKGROUND=0

stop_watcher() {
  if [[ -f "$WATCHER_PID_FILE" ]]; then
    local pid
    pid="$(cat "$WATCHER_PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      sleep 0.4
      kill -9 "$pid" 2>/dev/null || true
      log "Hot reload watcher stopped (pid=$pid)"
    else
      warn "Hot reload watcher is not running"
    fi
    rm -f "$WATCHER_PID_FILE"
  else
    warn "Hot reload watcher is not running"
  fi
}

status_watcher() {
  if [[ -f "$WATCHER_PID_FILE" ]] && kill -0 "$(cat "$WATCHER_PID_FILE")" 2>/dev/null; then
    log "running pid=$(cat "$WATCHER_PID_FILE") log=$WATCHER_LOG_FILE"
    return 0
  fi
  log "not running"
  return 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stop) stop_watcher; exit 0 ;;
    --status) status_watcher; exit $? ;;
    --app-only) MODE="app" ;;
    --no-restart) RESTART_APP=0 ;;
    --public|--remote) SERVER_TARGET="public" ;;
    --local) SERVER_TARGET="local" ;;
    --api)
      CLI_API_BASE_URL="${2:?укажи URL после --api}"
      SERVER_TARGET="custom"
      shift
      ;;
    --background|-d) BACKGROUND=1 ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *)
      err "unknown arg: $1"
      exit 1
      ;;
  esac
  shift
done

pin_emulator() {
  local serial
  serial="$(glagolitsa_android_first_emulator_serial)"
  if [[ -z "$serial" ]]; then
    err "No running emulator — start AVD first (or use rebuild-emulator.sh)"
    exit 1
  fi
  export ANDROID_SERIAL="$serial"
  log "Pinned to emulator $ANDROID_SERIAL (phone devices ignored)"
}

resolve_api() {
  case "$SERVER_TARGET" in
    local) printf '%s' "http://10.0.2.2:8080" ;;
    public)
      local from_gradle
      from_gradle="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
      printf '%s' "${from_gradle:-https://api.glagolit.me}"
      ;;
    custom)
      printf '%s' "$CLI_API_BASE_URL"
      ;;
  esac
}

restart_app() {
  if [[ "$RESTART_APP" -eq 0 ]]; then
    return 0
  fi
  # force-stop keeps data (session); never pm clear in hot-reload.
  "$ADB" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  "$ADB" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
  log "Activity restarted on $ANDROID_SERIAL"
}

run_continuous_install() {
  mkdir -p "$RUN_DIR"
  local api_url
  api_url="$(resolve_api)"
  local gradle_args=(
    :androidApp:installDebug
    -t
    --console=plain
    "-PapiBaseUrl=$api_url"
  )
  if [[ "$SERVER_TARGET" == "local" ]]; then
    gradle_args+=(
      "-PdevShortcutsEnabled=true"
      "-PdevPrimaryUsername=$GLAGOLITSA_DEV_PRIMARY_USERNAME"
      "-PdevPrimaryPassword=$GLAGOLITSA_DEV_PRIMARY_PASSWORD"
      "-PdevPrimaryLabel=$GLAGOLITSA_DEV_PRIMARY_LABEL"
      "-PdevSecondaryUsername=$GLAGOLITSA_DEV_SECONDARY_USERNAME"
      "-PdevSecondaryPassword=$GLAGOLITSA_DEV_SECONDARY_PASSWORD"
      "-PdevSecondaryLabel=$GLAGOLITSA_DEV_SECONDARY_LABEL"
    )
  else
    gradle_args+=(
      "-PdevShortcutsEnabled=false"
      "-PdevPrimaryUsername="
      "-PdevPrimaryPassword="
      "-PdevPrimaryLabel="
      "-PdevSecondaryUsername="
      "-PdevSecondaryPassword="
      "-PdevSecondaryLabel="
    )
  fi

  log "Watch mode target=$SERVER_TARGET api=$api_url"
  log "Save Compose/Kotlin under shared/ or androidApp/ to rebuild+install"
  log "Log: $WATCHER_LOG_FILE"

  (
    cd "$ROOT_DIR"
    ./gradlew "${gradle_args[@]}" 2>&1
  ) | while IFS= read -r line; do
    printf '%s\n' "$line"
    if [[ "$line" == *"Installed on"* ]]; then
      restart_app
    fi
  done
}

pin_emulator

if [[ -f "$WATCHER_PID_FILE" ]] && kill -0 "$(cat "$WATCHER_PID_FILE")" 2>/dev/null; then
  warn "Already running pid=$(cat "$WATCHER_PID_FILE") — use --stop first"
  exit 0
fi

if [[ "$MODE" == "full" ]]; then
  # Ensure emulator is up; do not start local server for public.
  if [[ "$SERVER_TARGET" == "local" ]]; then
    "$ROOT_DIR/scripts/run-and-debug.sh" --app-only || true
  fi
fi

if [[ "$BACKGROUND" -eq 1 ]]; then
  mkdir -p "$RUN_DIR"
  : >"$WATCHER_LOG_FILE"
  (
    run_continuous_install
  ) >>"$WATCHER_LOG_FILE" 2>&1 &
  echo $! >"$WATCHER_PID_FILE"
  log "Started in background pid=$(cat "$WATCHER_PID_FILE")"
  log "Follow: tail -f $WATCHER_LOG_FILE"
  exit 0
fi

# Foreground: still record pid of this shell for --stop.
echo $$ >"$WATCHER_PID_FILE"
trap 'rm -f "$WATCHER_PID_FILE"' EXIT
run_continuous_install
