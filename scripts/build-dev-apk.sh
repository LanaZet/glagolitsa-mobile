#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Собрать debug APK (низкоуровневый билдер; для тестеров — build-prerelease-apk.sh).
#
#   ./scripts/build-dev-apk.sh
#   ./scripts/build-dev-apk.sh --api https://api.glagolit.me
#   API_BASE_URL=http://10.0.2.2:8080 ./scripts/build-dev-apk.sh   # только эмулятор + локальный сервер
#   ./scripts/build-dev-apk.sh --dev-shortcuts                       # показать Marco/Polo shortcuts
#   ./scripts/build-dev-apk.sh --no-dev-shortcuts                    # обычная форма логин/пароль
#   ./scripts/build-dev-apk.sh --no-summary                          # не печатать путь/инструкции раздачи APK
#   ./scripts/build-dev-apk.sh --clean                               # не переиспользовать старый BuildConfig
#   ./scripts/build-dev-apk.sh --dev-user alice --dev-password "$DEV_PASSWORD"
#   ./scripts/build-dev-apk.sh --no-dev-secondary                    # только один shortcut
#   ./scripts/build-dev-apk.sh --emulator-phone-tunnel               # DNS api→10.0.2.2 for phone tunnel
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK_DIR="$ROOT_DIR/androidApp/build/outputs/apk/debug"
APK_PATH="$APK_DIR/androidApp-debug.apk"

# shellcheck source=lib/dev-accounts.sh
source "$ROOT_DIR/scripts/lib/dev-accounts.sh"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"

API_BASE_URL="${API_BASE_URL:-}"
OPEN_OUTPUT_DIR=true
PRINT_SUMMARY=true
CLEAN_BUILD=false
EMULATOR_PHONE_TUNNEL="${EMULATOR_PHONE_TUNNEL:-false}"
DEV_SHORTCUTS_ENABLED="${DEV_SHORTCUTS_ENABLED:-false}"
DEV_PRIMARY_USERNAME="${DEV_PRIMARY_USERNAME:-$GLAGOLITSA_DEV_PRIMARY_USERNAME}"
DEV_PRIMARY_PASSWORD="${DEV_PRIMARY_PASSWORD:-$GLAGOLITSA_DEV_PRIMARY_PASSWORD}"
DEV_PRIMARY_LABEL="${DEV_PRIMARY_LABEL:-$GLAGOLITSA_DEV_PRIMARY_LABEL}"
DEV_SECONDARY_USERNAME="${DEV_SECONDARY_USERNAME:-$GLAGOLITSA_DEV_SECONDARY_USERNAME}"
DEV_SECONDARY_PASSWORD="${DEV_SECONDARY_PASSWORD:-$GLAGOLITSA_DEV_SECONDARY_PASSWORD}"
DEV_SECONDARY_LABEL="${DEV_SECONDARY_LABEL:-$GLAGOLITSA_DEV_SECONDARY_LABEL}"

usage() {
  sed -n '2,7p' "$0"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api)
      API_BASE_URL="${2:?укажи URL после --api}"
      shift 2
      ;;
    --dev-shortcuts)
      DEV_SHORTCUTS_ENABLED=true
      shift
      ;;
    --no-dev-shortcuts)
      DEV_SHORTCUTS_ENABLED=false
      DEV_PRIMARY_USERNAME=""
      DEV_PRIMARY_PASSWORD=""
      DEV_PRIMARY_LABEL=""
      DEV_SECONDARY_USERNAME=""
      DEV_SECONDARY_PASSWORD=""
      DEV_SECONDARY_LABEL=""
      shift
      ;;
    --dev-user)
      DEV_PRIMARY_USERNAME="${2:?укажи username после --dev-user}"
      DEV_PRIMARY_LABEL="$DEV_PRIMARY_USERNAME"
      shift 2
      ;;
    --dev-password)
      DEV_PRIMARY_PASSWORD="${2:?укажи пароль после --dev-password}"
      shift 2
      ;;
    --dev-label)
      DEV_PRIMARY_LABEL="${2:?укажи label после --dev-label}"
      shift 2
      ;;
    --dev-secondary-user)
      DEV_SECONDARY_USERNAME="${2:?укажи username после --dev-secondary-user}"
      DEV_SECONDARY_LABEL="$DEV_SECONDARY_USERNAME"
      shift 2
      ;;
    --dev-secondary-password)
      DEV_SECONDARY_PASSWORD="${2:?укажи пароль после --dev-secondary-password}"
      shift 2
      ;;
    --dev-secondary-label)
      DEV_SECONDARY_LABEL="${2:?укажи label после --dev-secondary-label}"
      shift 2
      ;;
    --no-dev-secondary)
      DEV_SECONDARY_USERNAME=""
      DEV_SECONDARY_PASSWORD=""
      DEV_SECONDARY_LABEL=""
      shift
      ;;
    --no-open)
      OPEN_OUTPUT_DIR=false
      shift
      ;;
    --no-summary)
      PRINT_SUMMARY=false
      shift
      ;;
    --clean)
      CLEAN_BUILD=true
      shift
      ;;
    --emulator-phone-tunnel)
      EMULATOR_PHONE_TUNNEL=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Неизвестный аргумент: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

gradle_args=()
if [[ -z "$API_BASE_URL" ]]; then
  if [[ "$DEV_SHORTCUTS_ENABLED" != "true" ]]; then
    API_BASE_URL="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
  fi
fi
if [[ -n "$API_BASE_URL" ]]; then
  gradle_args+=("-PapiBaseUrl=$API_BASE_URL")
  api_label="$API_BASE_URL"
else
  gradle_args+=("-PapiBaseUrl=")
  api_label="<auto: emulator http://10.0.2.2:8080, phone http://127.0.0.1:8080>"
fi
gradle_args+=("-PdevShortcutsEnabled=$DEV_SHORTCUTS_ENABLED")
gradle_args+=("-PemulatorPhoneTunnel=$EMULATOR_PHONE_TUNNEL")
if [[ "$DEV_SHORTCUTS_ENABLED" == "true" ]]; then
  gradle_args+=("-PdevPrimaryUsername=$DEV_PRIMARY_USERNAME")
  gradle_args+=("-PdevPrimaryPassword=$DEV_PRIMARY_PASSWORD")
  gradle_args+=("-PdevPrimaryLabel=$DEV_PRIMARY_LABEL")
  gradle_args+=("-PdevSecondaryUsername=$DEV_SECONDARY_USERNAME")
  gradle_args+=("-PdevSecondaryPassword=$DEV_SECONDARY_PASSWORD")
  gradle_args+=("-PdevSecondaryLabel=$DEV_SECONDARY_LABEL")
else
  gradle_args+=("-PdevPrimaryUsername=")
  gradle_args+=("-PdevPrimaryPassword=")
  gradle_args+=("-PdevPrimaryLabel=")
  gradle_args+=("-PdevSecondaryUsername=")
  gradle_args+=("-PdevSecondaryPassword=")
  gradle_args+=("-PdevSecondaryLabel=")
fi

log() { printf '==> %s\n' "$*"; }

log "Сборка :androidApp:assembleDebug"
log "API: $api_label"
log "Dev shortcuts: $DEV_SHORTCUTS_ENABLED"
log "Emulator phone tunnel DNS: $EMULATOR_PHONE_TUNNEL"
if [[ "$DEV_SHORTCUTS_ENABLED" == "true" ]]; then
  log "Primary dev user: $DEV_PRIMARY_USERNAME"
  if [[ -n "$DEV_SECONDARY_USERNAME" ]]; then
    log "Secondary dev user: $DEV_SECONDARY_USERNAME"
  fi
fi
(
  cd "$ROOT_DIR"
  if [[ "$CLEAN_BUILD" == true ]]; then
    ./gradlew --no-build-cache :shared:clean :androidApp:clean
    gradle_cmd=(./gradlew --no-build-cache --rerun-tasks)
  else
    gradle_cmd=(./gradlew)
  fi
  if ((${#gradle_args[@]} > 0)); then
    "${gradle_cmd[@]}" :androidApp:assembleDebug "${gradle_args[@]}"
  else
    "${gradle_cmd[@]}" :androidApp:assembleDebug
  fi
)

if [[ ! -f "$APK_PATH" ]]; then
  echo "Ошибка: APK не найден — $APK_PATH" >&2
  exit 1
fi

# Guard: never ship a "no shortcuts" build that still has DEV_SHORTCUTS_ENABLED=true.
BUILD_CONFIG_JAVA="$ROOT_DIR/shared/build/generated/source/buildConfig/debug/com/glagolitsa/shared/BuildConfig.java"
if [[ -f "$BUILD_CONFIG_JAVA" ]]; then
  log "BuildConfig snapshot:"
  grep -E 'API_BASE_URL|DEV_SHORTCUTS_ENABLED|DEV_PRIMARY_USERNAME|DEV_SECONDARY_USERNAME' "$BUILD_CONFIG_JAVA" || true
  if [[ "$DEV_SHORTCUTS_ENABLED" != "true" ]]; then
    if grep -q 'DEV_SHORTCUTS_ENABLED = true' "$BUILD_CONFIG_JAVA"; then
      echo "Ошибка: просили без dev shortcuts, но BuildConfig.DEV_SHORTCUTS_ENABLED=true" >&2
      exit 1
    fi
    if grep -qE 'DEV_PRIMARY_USERNAME = "[^"]+"|DEV_SECONDARY_USERNAME = "[^"]+"' "$BUILD_CONFIG_JAVA"; then
      echo "Ошибка: dev credentials baked into BuildConfig despite --no-dev-shortcuts" >&2
      exit 1
    fi
  fi
  if [[ -n "$API_BASE_URL" ]] && ! grep -q "API_BASE_URL = \"$API_BASE_URL\"" "$BUILD_CONFIG_JAVA"; then
    echo "Ошибка: BuildConfig.API_BASE_URL не совпадает с запрошенным $API_BASE_URL" >&2
    grep 'API_BASE_URL' "$BUILD_CONFIG_JAVA" >&2 || true
    exit 1
  fi
fi

if [[ "$PRINT_SUMMARY" == true ]]; then
  log "Готово"
  ls -lh "$APK_PATH"
  echo ""
  echo "Файл: $APK_PATH"
  echo "Установка: adb install -r \"$APK_PATH\""
fi

if [[ "$OPEN_OUTPUT_DIR" == true ]]; then
  if [[ "$(uname -s)" == "Darwin" ]]; then
    open "$APK_DIR"
  else
    log "Папка: $APK_DIR"
  fi
fi
