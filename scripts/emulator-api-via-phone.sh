#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Прокинуть public API (VPS) на эмулятор через USB-телефон.
#
# Схема (сейчас HTTP на IP, без TLS):
#   Emulator → 10.0.2.2:18480 → Mac localhost:18480
#            → SSH (ProxyCommand: adb phone nc) → VPS :80 (Caddy → API)
#
# В APK: http://$VPS_HOST:$TUNNEL_PORT + DNS host→10.0.2.2 (только tunnel-build).
#
#   ./scripts/emulator-api-via-phone.sh              # tunnel + rebuild + install + launch
#   ./scripts/emulator-api-via-phone.sh --tunnel-only
#   ./scripts/emulator-api-via-phone.sh --stop
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
# shellcheck source=lib/android-env.sh
source "$ROOT_DIR/scripts/lib/android-env.sh"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
init_android_env

: "${VPS_HOST:?Set VPS_HOST to the server address}"
VPS_USER="${VPS_USER:-root}"
VPS_PORT="${VPS_PORT:-80}"
PUBLIC_API_HOST="${PUBLIC_API_HOST:-$VPS_HOST}"
TUNNEL_PORT="${TUNNEL_PORT:-18480}"
PHONE_SERIAL="${PHONE_SERIAL:-}"
EMULATOR_SERIAL="${EMULATOR_SERIAL:-}"
PID_FILE="$RUN_DIR/emulator-phone-tunnel.pid"
LOG_FILE="$RUN_DIR/emulator-phone-tunnel.log"
APP_ID="com.glagolitsa.mobile"
MAIN_ACTIVITY="com.glagolitsa.MainActivity"
APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

TUNNEL_ONLY=false
STOP=false
NO_INSTALL=false

log() { printf '\033[1;36m[emu-via-phone]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[emu-via-phone]\033[0m %s\n' "$*" >&2; }

while (($#)); do
  case "$1" in
    --tunnel-only) TUNNEL_ONLY=true ;;
    --stop) STOP=true ;;
    --no-install) NO_INSTALL=true ;;
    --phone) PHONE_SERIAL="${2:?}"; shift ;;
    --emulator) EMULATOR_SERIAL="${2:?}"; shift ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *) err "unknown arg: $1"; exit 1 ;;
  esac
  shift
done

mkdir -p "$RUN_DIR"

stop_tunnel() {
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      log "Stopping tunnel pid=$pid"
      kill "$pid" 2>/dev/null || true
      sleep 0.5
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  # orphan ssh on this port
  if command -v lsof >/dev/null 2>&1; then
    local p
    p="$(lsof -tiTCP:"$TUNNEL_PORT" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$p" ]]; then
      log "Freeing port $TUNNEL_PORT (pids: $p)"
      # shellcheck disable=SC2086
      kill $p 2>/dev/null || true
    fi
  fi
}

if [[ "$STOP" == true ]]; then
  stop_tunnel
  log "Tunnel stopped"
  exit 0
fi

if [[ -z "$PHONE_SERIAL" ]]; then
  PHONE_SERIAL="$(glagolitsa_android_first_physical_device_serial)"
fi
if [[ -z "$EMULATOR_SERIAL" ]]; then
  EMULATOR_SERIAL="$(glagolitsa_android_first_emulator_serial)"
fi

if [[ -z "$PHONE_SERIAL" ]]; then
  err "Нужен USB-телефон (adb device, не emulator)"
  exit 1
fi
if [[ -z "$EMULATOR_SERIAL" && "$TUNNEL_ONLY" == false ]]; then
  err "Нужен запущенный эмулятор"
  exit 1
fi

log "Phone: $PHONE_SERIAL"
log "Emulator: ${EMULATOR_SERIAL:-none}"
log "Tunnel: 127.0.0.1:$TUNNEL_PORT → $VPS_HOST:$VPS_PORT via phone nc"

# Probe phone can open VPS HTTP
if ! "$ADB" -s "$PHONE_SERIAL" shell -T "toybox nc -w 3 $VPS_HOST $VPS_PORT </dev/null" >/dev/null 2>&1; then
  err "Телефон не достучался до $VPS_HOST:$VPS_PORT"
  exit 1
fi
log "Phone → VPS:$VPS_PORT OK"

stop_tunnel

# SSH local forward through phone USB (same ProxyCommand as deploy)
nohup ssh -N \
  -o ConnectTimeout=20 \
  -o ServerAliveInterval=15 \
  -o ServerAliveCountMax=4 \
  -o ExitOnForwardFailure=yes \
  -o StrictHostKeyChecking=accept-new \
  -o ProxyCommand="$ADB -s $PHONE_SERIAL shell -T toybox nc %h %p" \
  -L "127.0.0.1:${TUNNEL_PORT}:127.0.0.1:${VPS_PORT}" \
  "${VPS_USER}@${VPS_HOST}" \
  >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
sleep 2

if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  err "SSH tunnel failed to start. Log:"
  tail -30 "$LOG_FILE" >&2 || true
  rm -f "$PID_FILE"
  exit 1
fi

# Health through local tunnel (HTTP :80 on VPS).
if curl -sf --connect-timeout 8 --max-time 15 \
  "http://127.0.0.1:${TUNNEL_PORT}/api/health" >/dev/null 2>&1; then
  log "Tunnel health OK: http://127.0.0.1:${TUNNEL_PORT}/api/health"
else
  err "Tunnel up but health failed. Log:"
  tail -40 "$LOG_FILE" >&2 || true
  exit 1
fi

if [[ "$TUNNEL_ONLY" == true ]]; then
  log "Tunnel-only mode. Keep this process alive; pid=$(cat "$PID_FILE")"
  log "Emulator APK needs: apiBaseUrl=http://10.0.2.2:${TUNNEL_PORT}"
  exit 0
fi

if [[ "$NO_INSTALL" == true ]]; then
  log "Skip install (--no-install). Tunnel running pid=$(cat "$PID_FILE")"
  exit 0
fi

# Emulator reaches Mac loopback tunnel via 10.0.2.2 (direct public IP:port would bypass tunnel).
API_URL="http://10.0.2.2:${TUNNEL_PORT}"
log "Building emulator APK for tunnel: $API_URL (no dev shortcuts)"
(
  cd "$ROOT_DIR"
  ./scripts/build-dev-apk.sh \
    --api "$API_URL" \
    --no-dev-shortcuts \
    --clean \
    --no-open
)

if [[ ! -f "$APK_PATH" ]]; then
  err "APK missing: $APK_PATH"
  exit 1
fi

log "Installing on emulator $EMULATOR_SERIAL (clean)"
"$ADB" -s "$EMULATOR_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1 || true
"$ADB" -s "$EMULATOR_SERIAL" install -r -t "$APK_PATH"
"$ADB" -s "$EMULATOR_SERIAL" shell pm clear "$APP_ID" >/dev/null 2>&1 || true
"$ADB" -s "$EMULATOR_SERIAL" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null

sleep 3
UI="$("$ADB" -s "$EMULATOR_SERIAL" shell uiautomator dump /dev/tty 2>/dev/null || true)"
if printf '%s' "$UI" | grep -q "API: http://10.0.2.2:${TUNNEL_PORT}"; then
  log "UI API label OK: http://10.0.2.2:${TUNNEL_PORT}"
elif printf '%s' "$UI" | grep -q "10.0.2.2"; then
  log "UI shows 10.0.2.2 (check port in BuildConfig)"
else
  err "Could not confirm API label on emulator UI"
fi
if printf '%s' "$UI" | grep -q "Войти как Marco"; then
  err "FAIL: dev shortcuts still present"
  exit 1
fi

log "Done."
log "  Tunnel pid=$(cat "$PID_FILE")  log=$LOG_FILE"
log "  Emulator must keep using host 10.0.2.2 → Mac :$TUNNEL_PORT → phone → VPS"
log "  Stop: $0 --stop"
log "  Login as public user credentials — not local-only shortcut accounts"
