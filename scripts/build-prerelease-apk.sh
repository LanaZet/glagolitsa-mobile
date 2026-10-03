#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Собрать APK для внешних тестеров (публичный API, без dev shortcuts).
#
#   ./scripts/build-prerelease-apk.sh              # собрать + открыть папку в Finder
#   ./scripts/build-prerelease-apk.sh --install   # только собрать и установить на телефон
#   ./scripts/build-prerelease-apk.sh --no-dev-shortcuts  # явно: только логин/пароль
#   ./scripts/build-prerelease-apk.sh --api URL   # другой API (по умолчанию gradle.properties)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
# shellcheck source=lib/android-env.sh
source "$ROOT_DIR/scripts/lib/android-env.sh"

BUILD_SCRIPT="$ROOT_DIR/scripts/build-dev-apk.sh"
APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
APP_ID="com.glagolitsa.mobile"
MAIN_ACTIVITY="com.glagolitsa.MainActivity"

API_BASE_URL=""
INSTALL_ON_DEVICE=false

usage() {
  cat <<'EOF'
Сборка APK для тестеров (shareable debug build).

  ./scripts/build-prerelease-apk.sh [--install] [--api URL] [--no-dev-shortcuts]

  --install   только собрать и установить APK на физический телефон; Finder/hot reload/share output не запускаются
  --api URL   переопределить apiBaseUrl (иначе из gradle.properties)
  --no-dev-shortcuts
              принудительно убрать быстрый вход; для prerelease это значение по умолчанию

EOF
}

while (($#)); do
  case "$1" in
    --install) INSTALL_ON_DEVICE=true ;;
    --no-dev-shortcuts) ;;
    --api)
      API_BASE_URL="${2:?укажи URL после --api}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      glagolitsa_err "Неизвестный аргумент: $1"
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ -z "$API_BASE_URL" ]]; then
  API_BASE_URL="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
fi
if [[ -z "$API_BASE_URL" ]]; then
  glagolitsa_err "apiBaseUrl не задан. Добавьте в gradle.properties или передайте --api URL"
  exit 1
fi

if [[ "$INSTALL_ON_DEVICE" == true ]]; then
  glagolitsa_log "Сборка APK и установка на телефон"
else
  glagolitsa_log "Сборка APK для тестеров"
fi
glagolitsa_log "API: $API_BASE_URL"
glagolitsa_log "Dev shortcuts: выключены"

if glagolitsa_is_remote_api_url "$API_BASE_URL"; then
  glagolitsa_check_api_reachable "$API_BASE_URL" || true
fi

build_args=(
  --api "$API_BASE_URL"
  --no-open
  --no-dev-shortcuts
)
if [[ "$INSTALL_ON_DEVICE" != true ]]; then
  build_args+=(--clean)
else
  build_args+=(--no-summary)
fi

DEV_SHORTCUTS_ENABLED=false \
DEV_PRIMARY_USERNAME="" \
DEV_PRIMARY_PASSWORD="" \
DEV_PRIMARY_LABEL="" \
DEV_SECONDARY_USERNAME="" \
DEV_SECONDARY_PASSWORD="" \
DEV_SECONDARY_LABEL="" \
  "$BUILD_SCRIPT" "${build_args[@]}"

if [[ ! -f "$APK_PATH" ]]; then
  glagolitsa_err "APK не найден: $APK_PATH"
  exit 1
fi

if [[ "$INSTALL_ON_DEVICE" == true ]]; then
  init_android_env
  phone="$(glagolitsa_android_first_physical_device_serial)"
  if [[ -z "$phone" ]]; then
    glagolitsa_warn "--install: физический телефон не найден, пропускаем установку"
  else
    # install -r preserves app data (session/crypto). Force-stop first so we don't race a
    # mid-refresh activity that could crash if network is slow right after replace.
    glagolitsa_log "Установка на $phone (поверх существующего, данные сессии сохраняются)..."
    "$ADB" -s "$phone" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    if ! "$ADB" -s "$phone" install -r "$APK_PATH"; then
      glagolitsa_err "adb install -r failed. Xiaomi: включите «Установка через USB» в Для разработчиков."
      exit 1
    fi
    "$ADB" -s "$phone" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    "$ADB" -s "$phone" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
    glagolitsa_log "Приложение запущено на $phone"
    glagolitsa_log "Если нужен чистый вход: adb -s $phone shell pm clear $APP_ID"
  fi
  exit 0
fi

glagolitsa_log "Готово: $APK_PATH"
ls -lh "$APK_PATH"

if [[ "$(uname -s)" == "Darwin" ]]; then
  open "$(dirname "$APK_PATH")"
fi

glagolitsa_start_required_hot_reload "$ROOT_DIR" "$API_BASE_URL"

glagolitsa_log ""
glagolitsa_log "Отправьте файл тестеру: androidApp-debug.apk"
glagolitsa_log "Установка: adb install -r \"$APK_PATH\""
