#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Пересборка Glagolitsa и установка только на Android-эмулятор.
#
#   ./scripts/rebuild-emulator.sh                 # публичный API, без dev shortcuts
#   ./scripts/rebuild-emulator.sh --no-launch     # только собрать и поставить
#   ./scripts/rebuild-emulator.sh --install-only  # APK уже собран
#   ./scripts/rebuild-emulator.sh --reinstall     # clean reinstall only for public target
#   ./scripts/rebuild-emulator.sh --watch         # после установки следить за изменениями UI
#
# Test-build (Run and Debug → «Сунь Укун»):
#   ./scripts/rebuild-emulator.sh --test-build --interactive --reinstall --watch
#   → спросит local или public (один конфиг, без дублей)
#
#   CLI без вопроса:
#   ./scripts/rebuild-emulator.sh --test-build --local  --reinstall --watch
#   ./scripts/rebuild-emulator.sh --test-build --public --reinstall --watch
#
#   local  → API 10.0.2.2:8080 + кнопки «Войти как Marco/Polo» (+ поднимает Go API)
#   public → API https://api.glagolit.me + обычный логин/пароль (БЕЗ dev shortcuts), clean install
#
# Переменные (опционально):
#   AVD_NAME=quorum_api34
#   SERVER_TARGET=public|local
#   LOCAL_API_BASE_URL=http://10.0.2.2:8080

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
MAIN_ACTIVITY="com.glagolitsa.MainActivity"
AVD_NAME="${AVD_NAME:-quorum_api34}"
APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
BUILD_SCRIPT="$ROOT_DIR/scripts/build-dev-apk.sh"
API_BASE_URL_ENV="${API_BASE_URL:-}"
CLI_API_BASE_URL=""
API_BASE_URL=""
SERVER_TARGET="${SERVER_TARGET:-public}"
SERVER_TARGET_EXPLICIT=false
LOCAL_API_BASE_URL="${LOCAL_API_BASE_URL:-http://10.0.2.2:8080}"

wukong() { printf '\033[1;33m[Сунь Укун]\033[0m %s\n' "$*"; }
demon()  { printf '\033[1;31m[демон]\033[0m %s\n' "$*" >&2; }

# shellcheck source=lib/android-env.sh
source "$ROOT_DIR/scripts/lib/android-env.sh"
init_android_env
# shellcheck source=lib/dev-accounts.sh
source "$ROOT_DIR/scripts/lib/dev-accounts.sh"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"

INSTALL_ONLY=false
LAUNCH=true
REINSTALL=false
INTERACTIVE=false
TEST_BUILD=false
WATCH=false
EMULATOR_SERIAL=""

SUN_WUKONG_HINT='Run and Debug → Glagolitsa: Сунь Укун (эмулятор: local/public)'

require_test_build_mode() {
  demon "Тестовая сборка (local/public) — через $SUN_WUKONG_HINT"
  demon "или: ./scripts/rebuild-emulator.sh --test-build --interactive --reinstall --watch"
  exit 1
}

resolve_api_base_url() {
  if [[ -n "$CLI_API_BASE_URL" ]]; then
    printf '%s' "$CLI_API_BASE_URL"
    return 0
  fi

  case "$SERVER_TARGET" in
    local)
      printf '%s' "$LOCAL_API_BASE_URL"
      return 0
      ;;
    public)
      # Public target always pins production URL — ignore stale API_BASE_URL=10.0.2.2 env.
      local from_gradle
      from_gradle="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
      local url="${from_gradle:-https://api.glagolit.me}"
      if [[ "$url" == *"10.0.2.2"* || "$url" == *"127.0.0.1"* || "$url" == *"localhost"* ]]; then
        demon "gradle.properties apiBaseUrl выглядит как local ($url) — для public беру https://api.glagolit.me"
        url="https://api.glagolit.me"
      fi
      printf '%s' "$url"
      return 0
      ;;
    custom)
      if [[ -n "$API_BASE_URL_ENV" ]]; then
        printf '%s' "$API_BASE_URL_ENV"
        return 0
      fi
      demon "SERVER_TARGET=custom, но URL не задан (--api или API_BASE_URL)"
      exit 1
      ;;
    *)
      if [[ "$SERVER_TARGET" == *'${input:'* ]]; then
        demon "VS Code/Cursor не подставил выбор сервера: $SERVER_TARGET"
        demon "Запусти: $SUN_WUKONG_HINT"
      else
        demon "Неизвестный сервер: $SERVER_TARGET"
        demon "Доступно: local, public"
      fi
      exit 1
      ;;
  esac
}

server_label() {
  case "$SERVER_TARGET" in
    local) printf '%s' "локальный" ;;
    public) printf '%s' "публичный" ;;
    *) printf '%s' "кастомный" ;;
  esac
}

prompt_server_if_needed() {
  if [[ "$SERVER_TARGET_EXPLICIT" == true ]]; then
    return 0
  fi

  # Prefer /dev/tty so the prompt works in VS Code/Cursor Run and Debug terminals
  # even when stdin is not a TTY (piped launchers, task runners).
  local tty=""
  if [[ -r /dev/tty && -w /dev/tty ]]; then
    tty="/dev/tty"
  elif [[ -t 0 ]]; then
    tty="/dev/stdin"
  else
    demon "Нет TTY для выбора сервера. Укажи явно: --local или --public"
    demon "Пример: ./scripts/rebuild-emulator.sh --test-build --local --reinstall --watch"
    exit 1
  fi

  wukong "Куда ходит эмулятор?"
  wukong "  1) local  — http://10.0.2.2:8080 + Marco/Polo (поднимет локальный Go API)"
  wukong "  2) public — https://api.glagolit.me, без dev shortcuts, clean install"
  local reply=""
  while true; do
    printf '[Сунь Укун] сервер [1/2, Enter=local]: ' >"$tty"
    # shellcheck disable=SC2162
    IFS= read -r reply <"$tty" || true
    case "${reply:-1}" in
      1|l|local|L|LOCAL|"")
        SERVER_TARGET="local"
        SERVER_TARGET_EXPLICIT=true
        wukong "Выбран local"
        return 0
        ;;
      2|p|public|P|PUBLIC)
        SERVER_TARGET="public"
        SERVER_TARGET_EXPLICIT=true
        wukong "Выбран public"
        return 0
        ;;
      *)
        demon "Введи 1 (local) или 2 (public)."
        ;;
    esac
  done
}

ensure_local_server() {
  wukong "Перезапускаю локальный Go-сервер из текущего репозитория: $SERVER_DIR"
  SERVER_DIR="$SERVER_DIR" "$ROOT_DIR/scripts/run-and-debug.sh" --server-only --restart-server
}

check_selected_api() {
  if [[ "$SERVER_TARGET" == "local" ]]; then
    ensure_local_server
    if command -v curl >/dev/null 2>&1 && curl -sf --connect-timeout 2 --max-time 4 "http://localhost:8080/api/health" >/dev/null 2>&1; then
      wukong "Локальный API отвечает: http://localhost:8080/api/health"
    else
      demon "Локальный API не поднялся. Смотри лог: $RUN_DIR/server.log"
      exit 1
    fi
    return 0
  fi

  local label
  label="$(server_label)"
  if command -v curl >/dev/null 2>&1 && curl -sf --connect-timeout 5 --max-time 10 "${API_BASE_URL%/}/api/health" >/dev/null 2>&1; then
    wukong "API ($label) отвечает: ${API_BASE_URL%/}/api/health"
  else
    wukong "API ($label) выбран, но health-check с этой машины не ответил. Сборку всё равно ставлю на выбранный URL."
  fi
}

while (($#)); do
  case "$1" in
    --install-only) INSTALL_ONLY=true ;;
    --no-launch) LAUNCH=false ;;
    --reinstall) REINSTALL=true ;;
    --api)
      CLI_API_BASE_URL="${2:?укажи URL после --api}"
      SERVER_TARGET="custom"
      shift
      ;;
    --server)
      SERVER_TARGET="${2:?укажи local или public после --server}"
      SERVER_TARGET_EXPLICIT=true
      shift
      ;;
    --local)
      SERVER_TARGET="local"
      SERVER_TARGET_EXPLICIT=true
      ;;
    --public|--remote)
      SERVER_TARGET="public"
      SERVER_TARGET_EXPLICIT=true
      ;;
    --test-build)
      TEST_BUILD=true
      ;;
    --watch)
      WATCH=true
      ;;
    --interactive|-i)
      INTERACTIVE=true
      TEST_BUILD=true
      ;;
    -h|--help)
      sed -n '2,24p' "$0"
      exit 0
      ;;
    *)
      echo "Неизвестный аргумент: $1" >&2
      exit 1
      ;;
  esac
  shift
done

mkdir -p "$RUN_DIR"

if [[ "$TEST_BUILD" == false ]]; then
  if [[ "$SERVER_TARGET_EXPLICIT" == true || -n "$CLI_API_BASE_URL" ]]; then
    require_test_build_mode
  fi
  SERVER_TARGET="public"
  SERVER_TARGET_EXPLICIT=false
else
  # --interactive (Run and Debug) always asks; CLI --local/--public skips the prompt.
  if [[ "$INTERACTIVE" == true || "$SERVER_TARGET_EXPLICIT" == false ]]; then
    if [[ "$INTERACTIVE" == true ]]; then
      prompt_server_if_needed
    else
      # Bare --test-build without target: default local (scripted/non-interactive paths).
      SERVER_TARGET="local"
      SERVER_TARGET_EXPLICIT=true
    fi
  fi
fi

API_BASE_URL="$(resolve_api_base_url)"

require_emulator_serial() {
  EMULATOR_SERIAL="$(glagolitsa_android_first_emulator_serial)"
  [[ -n "$EMULATOR_SERIAL" ]]
}

pin_adb_to_emulator() {
  if ! require_emulator_serial; then
    demon "Нет запущенного эмулятора — на телефон и другие устройства не ставлю."
    exit 1
  fi
  export ANDROID_SERIAL="$EMULATOR_SERIAL"

  local phone
  while IFS= read -r phone; do
    [[ -z "$phone" ]] && continue
    wukong "Телефон/устройство $phone подключено, но APK пойдёт только на эмулятор $EMULATOR_SERIAL."
  done < <(glagolitsa_android_physical_device_serials)
}

adb_emulator() {
  "$ADB" -s "$EMULATOR_SERIAL" "$@"
}

wake_emulator() {
  if require_emulator_serial; then
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
    wukong "Эй, этот эмулятор ($EMULATOR_SERIAL) уже стоит на пути — проход открыт, идём дальше на Запад!"
    return 0
  fi

  if [[ ! -x "$EMULATOR" ]]; then
    demon "Не найден злой дух emulator: $EMULATOR"
    demon "Подними эмулятор руками в Android Studio или задай ANDROID_HOME / ANDROID_SDK_ROOT!"
    exit 1
  fi

  wukong "Эмулятора нет — вызываю облако! Поднимаю $AVD_NAME, как когда я летел на Небеса..."
  wukong "SDK: $ANDROID_SDK"
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load >"$RUN_DIR/emulator.log" 2>&1 &
  echo $! >"$RUN_DIR/emulator.pid"

  wukong "Жду, пока камень эмулятора оживёт..."

  local attempts=60
  for ((i = 1; i <= attempts; i++)); do
    if require_emulator_serial &&
      [[ "$(adb_emulator shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      export ANDROID_SERIAL="$EMULATOR_SERIAL"
      wukong "Эмулятор проснулся! Ещё один демон повержен — можно нести APK в Западный Рай."
      return 0
    fi
    sleep 2
  done

  demon "Эмулятор не загрузился за две сотни вдохов. Смотри лог: $RUN_DIR/emulator.log"
  exit 1
}

forge_apk() {
  check_selected_api

  # Do not pass --clean by default (wipes androidApp and races). build-dev-apk
  # already detects stale BuildConfig.java/.class and recompiles only that.
  local build_args=(--no-open --api "$API_BASE_URL")

  if [[ "$SERVER_TARGET" == "local" && "$TEST_BUILD" == true ]]; then
    wukong "Локальный сервер: emulator APK + быстрый вход $(glagolitsa_dev_account_summary)."
    wukong "API: $API_BASE_URL"
    build_args+=(--dev-shortcuts)
  else
    # public / prerelease-like: never ship dev shortcuts.
    wukong "Сервер $(server_label): emulator APK без dev shortcuts (только логин/пароль)."
    wukong "API: $API_BASE_URL"
    build_args+=(--no-dev-shortcuts)
  fi

  "$BUILD_SCRIPT" "${build_args[@]}"
  wukong "APK собран: $APK_PATH"
}

apk_size_mb() {
  if [[ ! -f "$APK_PATH" ]]; then
    echo "?"
    return 0
  fi
  du -m "$APK_PATH" | awk '{print $1}'
}

remove_old_app() {
  wukong "Снимаю старую оболочку приложения с эмулятора..."
  adb_emulator uninstall "$APP_ID" >/dev/null 2>&1 || true
}

plant_apk_on_emulator() {
  pin_adb_to_emulator

  if [[ ! -f "$APK_PATH" ]]; then
    demon "APK не найден: $APK_PATH"
    demon "Сначала собери: ./gradlew :androidApp:assembleDebug"
    exit 1
  fi

  local size_mb
  size_mb="$(apk_size_mb)"
  wukong "Размер APK: ${size_mb}M — несу свиток приложения на эмулятор!"

  # Local target must preserve app data (sessions/history/keys) across rebuilds.
  # Clean reinstall is allowed only for public target.
  if [[ "$SERVER_TARGET" == "public" ]]; then
    wukong "Чистая установка (public): сношу старое приложение, чтобы не тащить local-сессии."
    remove_old_app
  elif [[ "$REINSTALL" == true ]]; then
    wukong "Игнорирую --reinstall для local: сохраняю данные приложения."
  fi

  local install_log
  install_log="$(mktemp)"
  if adb_emulator install -r "$APK_PATH" >"$install_log" 2>&1; then
    rm -f "$install_log"
    wukong "APK установлен строго на эмулятор $EMULATOR_SERIAL."
    return 0
  fi

  demon "Установка провалилась!"
  tail -20 "$install_log" >&2 || true

  if grep -qi "not enough space" "$install_log" 2>/dev/null; then
    demon "На эмуляторе мало места. Попробуй:"
    demon "  ./scripts/rebuild-emulator.sh --test-build --interactive --reinstall"
    demon "  или Wipe Data для AVD $AVD_NAME в Android Studio Device Manager"
  fi
  rm -f "$install_log"
  exit 1
}

open_app_gate() {
  pin_adb_to_emulator
  wukong "Открываю врата Glagolitsa — пусть дух мессенджера явится на экране!"
  adb_emulator shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  # Extra clear when public: no leftover secure prefs after install -r edge cases
  if [[ "$SERVER_TARGET" == "public" ]]; then
    adb_emulator shell pm clear "$APP_ID" >/dev/null 2>&1 || true
  fi
  adb_emulator shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
  wukong "Готово! Эмулятор: $EMULATOR_SERIAL"
  if [[ "$SERVER_TARGET" == "local" && "$TEST_BUILD" == true ]]; then
    wukong "Быстрый вход: $(glagolitsa_dev_account_summary) (только local)"
  else
    wukong "Dev shortcuts выключены — войди логином/паролем аккаунта на $(server_label) сервере."
  fi
  wukong "API (реальный baseUrl): $API_BASE_URL"
}

watch_and_reinstall() {
  pin_adb_to_emulator

  local gradle_args=(
    :androidApp:installDebug
    -t
    --console=plain
    "-PapiBaseUrl=$API_BASE_URL"
  )

  if [[ "$SERVER_TARGET" == "local" && "$TEST_BUILD" == true ]]; then
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

  wukong "Включаю watch-режим: сохраняй Compose/Kotlin файлы — APK пересоберётся и перезапустится."
  wukong "Ctrl+C остановит watcher. Эмулятор: $EMULATOR_SERIAL | API: $API_BASE_URL"

  (
    cd "$ROOT_DIR"
    ./gradlew "${gradle_args[@]}" 2>&1
  ) | while IFS= read -r line; do
    printf '%s\n' "$line"
    if [[ "$line" == *"Installed on"* ]]; then
      # Soft restart only — never pm clear in watch mode (would wipe public session).
      pin_adb_to_emulator
      adb_emulator shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
      adb_emulator shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
      wukong "Watch install OK — soft restart $EMULATOR_SERIAL (session kept)"
    fi
  done
}

wukong "Пересборка Glagolitsa для эмулятора"

wake_emulator

if [[ "$INSTALL_ONLY" == false ]]; then
  forge_apk
else
  wukong "Ты велел не ковать заново — беру готовый APK, как найденный в пути свиток."
fi

plant_apk_on_emulator

if [[ "$LAUNCH" == true ]]; then
  open_app_gate
else
  wukong "Запуск пропущен (--no-launch). APK стоит у ворот — зови, когда понадобится."
fi

if [[ "$WATCH" == true ]]; then
  watch_and_reinstall
fi

wukong "Готово: ./scripts/rebuild-emulator.sh"
