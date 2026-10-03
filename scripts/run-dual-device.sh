#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Тест DM на двух устройствах без logout/login:
#   эмулятор → primary dev account, телефон (или 2-й эмулятор) → secondary dev account
#
#   ./scripts/run-dual-device.sh              # сервер + adb reverse + установка на все устройства
#   ./scripts/run-dual-device.sh --wifi       # для телефона по Wi‑Fi (LAN IP вместо adb reverse)
#   ./scripts/run-dual-device.sh --app-only   # сервер уже запущен
#   ./scripts/run-dual-device.sh --manual     # только инструкция
#
# Переменные:
#   SERVER_DIR=../glagolitsa/server
#   LAN_IP=192.168.1.5   # для --wifi, иначе определяется автоматически

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT_DIR/../glagolitsa/server}"
APP_ID="com.glagolitsa.mobile"
MAIN_ACTIVITY="com.glagolitsa.MainActivity"
LOCAL_API_BASE_URL="${LOCAL_API_BASE_URL:-http://10.0.2.2:8080}"

# shellcheck source=lib/dev-accounts.sh
source "$ROOT_DIR/scripts/lib/dev-accounts.sh"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$ANDROID_SDK/platform-tools/adb")"

MODE="full"
USE_WIFI=false

for arg in "$@"; do
  case "$arg" in
    --app-only) MODE="app" ;;
    --wifi) USE_WIFI=true ;;
    --manual) MODE="manual" ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
  esac
done

log() { printf '\033[1;34m[dual-device]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[dual-device]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[dual-device]\033[0m %s\n' "$*" >&2; }

detect_lan_ip() {
  if [[ -n "${LAN_IP:-}" ]]; then
    echo "$LAN_IP"
    return
  fi
  local ip=""
  if command -v ipconfig >/dev/null 2>&1; then
    ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
    if [[ -z "$ip" ]]; then
      ip="$(ipconfig getifaddr en1 2>/dev/null || true)"
    fi
  fi
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  echo "$ip"
}

is_emulator_serial() {
  local serial="$1"
  [[ "$serial" == emulator-* ]]
}

read_device_serials() {
  DEVICE_SERIALS=()
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && DEVICE_SERIALS+=("$serial")
  done < <("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" { print $1 }')
}

print_manual_steps() {
  cat <<'EOF'

── DM на двух устройствах (без выхода из аккаунта) ──

1. Запусти:
     ./scripts/run-dual-device.sh

2. На эмуляторе нажми «Войти как $GLAGOLITSA_DEV_PRIMARY_LABEL»

3. На телефоне нажми «Войти как $GLAGOLITSA_DEV_SECONDARY_LABEL»
   (телефон по USB: adb reverse уже настроен скриптом)

4. На $GLAGOLITSA_DEV_PRIMARY_LABEL: Чаты → «Найти пользователя (DM)» → $GLAGOLITSA_DEV_SECONDARY_USERNAME → отправь сообщение

5. На $GLAGOLITSA_DEV_SECONDARY_LABEL: открой чат с $GLAGOLITSA_DEV_PRIMARY_USERNAME — сообщение должно появиться, ответь

Если телефон только по Wi‑Fi (без USB):
     ./scripts/run-dual-device.sh --wifi
   Телефон и Mac должны быть в одной сети.

Быстрый вход: кнопки «Войти как $GLAGOLITSA_DEV_PRIMARY_LABEL» / «Войти как $GLAGOLITSA_DEV_SECONDARY_LABEL» на экране входа.

EOF
}

if [[ "$MODE" == "manual" ]]; then
  print_manual_steps
  exit 0
fi

ensure_server() {
  if curl -sf "http://localhost:8080/api/health" >/dev/null 2>&1; then
    log "Сервер уже отвечает на :8080"
    return 0
  fi
  log "Поднимаем сервер..."
  "$ROOT_DIR/scripts/run-and-debug.sh" --server-only
}

setup_device_routing() {
  read_device_serials

  if [[ ${#DEVICE_SERIALS[@]} -eq 0 ]]; then
    err "Нет подключённых устройств. Подключи телефон (USB + отладка) или запусти эмулятор."
    exit 1
  fi

  log "Устройства: ${DEVICE_SERIALS[*]}"

  if [[ "$USE_WIFI" == true ]]; then
    local lan_ip
    lan_ip="$(detect_lan_ip)"
    if [[ -z "$lan_ip" ]]; then
      err "Не удалось определить LAN IP. Задай: LAN_IP=192.168.x.x ./scripts/run-dual-device.sh --wifi"
      exit 1
    fi
    warn "Wi‑Fi режим: устройства будут ходить на http://$lan_ip:8080"
    warn "Убедись, что Mac и телефон в одной Wi‑Fi сети."
    export GRADLE_API_BASE_URL="http://$lan_ip:8080"
    export GRADLE_API_BASE_URL_SET=true
    return 0
  fi

  local serial
  # Force an empty BuildConfig.API_BASE_URL for USB/local runs. The Android
  # client then chooses 10.0.2.2 on emulators and 127.0.0.1 on USB phones.
  export GRADLE_API_BASE_URL=""
  export GRADLE_API_BASE_URL_SET=true
  for serial in "${DEVICE_SERIALS[@]}"; do
    if is_emulator_serial "$serial"; then
      log "$serial — эмулятор (API: $LOCAL_API_BASE_URL)"
    else
      log "$serial — телефон, настраиваем adb reverse tcp:8080"
      "$ADB" -s "$serial" reverse tcp:8080 tcp:8080
      log "$serial — API: 127.0.0.1:8080 через USB"
    fi
  done
}

install_on_all_devices() {
  read_device_serials
  local gradle_args=()

  if [[ "${GRADLE_API_BASE_URL_SET:-false}" == true ]]; then
    gradle_args+=("-PapiBaseUrl=$GRADLE_API_BASE_URL")
  fi
  gradle_args+=("-PdevShortcutsEnabled=true")
  gradle_args+=("-PdevPrimaryUsername=$GLAGOLITSA_DEV_PRIMARY_USERNAME")
  gradle_args+=("-PdevPrimaryPassword=$GLAGOLITSA_DEV_PRIMARY_PASSWORD")
  gradle_args+=("-PdevPrimaryLabel=$GLAGOLITSA_DEV_PRIMARY_LABEL")
  gradle_args+=("-PdevSecondaryUsername=$GLAGOLITSA_DEV_SECONDARY_USERNAME")
  gradle_args+=("-PdevSecondaryPassword=$GLAGOLITSA_DEV_SECONDARY_PASSWORD")
  gradle_args+=("-PdevSecondaryLabel=$GLAGOLITSA_DEV_SECONDARY_LABEL")

  log "Собираем и устанавливаем APK..."
  (
    cd "$ROOT_DIR"
    ./gradlew :androidApp:installDebug "${gradle_args[@]}" --quiet
  )

  local serial
  for serial in "${DEVICE_SERIALS[@]}"; do
    log "Запускаем приложение на $serial"
    "$ADB" -s "$serial" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
  done
}

case "$MODE" in
  full)
    ensure_server
    setup_device_routing
    install_on_all_devices
    ;;
  app)
    setup_device_routing
    install_on_all_devices
    ;;
esac

log ""
log "Готово! Эмулятор → «Войти как $GLAGOLITSA_DEV_PRIMARY_LABEL», телефон → «Войти как $GLAGOLITSA_DEV_SECONDARY_LABEL»"
print_manual_steps
