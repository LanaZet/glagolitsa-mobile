# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# shellcheck shell=bash
# Общая настройка Maestro E2E: adb, реальное устройство по умолчанию, ANDROID_SERIAL.
#   source scripts/maestro-env.sh
#
# Переопределение:
#   ADB_SERIAL=<adb-serial> source scripts/maestro-env.sh
#   MAESTRO_DEVICE=emulator source scripts/maestro-env.sh

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "source this file: source scripts/maestro-env.sh" >&2
  exit 1
fi

_maestro_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -d "${HOME}/.maestro/bin" ]]; then
  PATH="${HOME}/.maestro/bin:${PATH}"
fi
if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/platform-tools" ]]; then
  PATH="${ANDROID_HOME}/platform-tools:${PATH}"
elif [[ -d "${HOME}/Library/Android/sdk/platform-tools" ]]; then
  PATH="${HOME}/Library/Android/sdk/platform-tools:${PATH}"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "[maestro-env] adb не найден (Android SDK platform-tools)" >&2
  return 1
fi

_maestro_pick_device() {
  local serial mode="${MAESTRO_DEVICE:-phone}"
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    echo "$ADB_SERIAL"
    return 0
  fi
  if [[ "$mode" == "emulator" ]]; then
    while IFS= read -r serial; do
      [[ -n "$serial" ]] || continue
      echo "$serial"
      return 0
    done < <(adb devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}')
    return 1
  fi
  # phone (default): USB-устройство, не эмулятор
  while IFS= read -r serial; do
    [[ -n "$serial" ]] || continue
    echo "$serial"
    return 0
  done < <(adb devices | awk '/\tdevice$/{print $1}' | grep -v '^emulator-')
  return 1
}

MAESTRO_ADB_SERIAL="$(_maestro_pick_device)" || {
  echo "[maestro-env] нет adb-устройства (подключи телефон по USB или задай ADB_SERIAL)" >&2
  adb devices >&2 || true
  return 1
}

export ANDROID_SERIAL="$MAESTRO_ADB_SERIAL"
export MAESTRO_DEVICE_ID="$MAESTRO_ADB_SERIAL"
ADB=(adb -s "$MAESTRO_ADB_SERIAL")

if [[ "${MAESTRO_DEVICE:-phone}" == "emulator" && "$MAESTRO_ADB_SERIAL" != emulator-* ]]; then
  echo "[maestro-env] MAESTRO_DEVICE=emulator, но выбран $MAESTRO_ADB_SERIAL" >&2
  return 1
fi
if [[ "${MAESTRO_DEVICE:-phone}" == "phone" && "$MAESTRO_ADB_SERIAL" == emulator-* ]]; then
  echo "[maestro-env] MAESTRO_DEVICE=phone, но выбран эмулятор $MAESTRO_ADB_SERIAL" >&2
  return 1
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "[maestro-env] устройство $MAESTRO_ADB_SERIAL недоступно" >&2
  return 1
fi

if [[ "$MAESTRO_ADB_SERIAL" == emulator-* ]]; then
  "${ADB[@]}" root >/dev/null 2>&1 || true
  "${ADB[@]}" shell settings put global adb_install_need_confirm 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
fi

if ! command -v maestro >/dev/null 2>&1; then
  echo "[maestro-env] Maestro не найден: curl -fsSL https://get.maestro.mobile.dev | bash" >&2
  return 1
fi

_maestro_install_apk() {
  local apk="$_maestro_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "[maestro-env] APK нет — собери: ./gradlew :androidApp:assembleDebug" >&2
    return 1
  fi
  echo "[maestro-env] install APK → $MAESTRO_ADB_SERIAL"
  "${ADB[@]}" install -r "$apk" >/dev/null
}

_maestro_check_api() {
  local url="${BASE_URL:-https://api.glagolit.me}"
  if ! curl -sf --connect-timeout 8 "${url}/api/health" >/dev/null 2>&1; then
    echo "[maestro-env] WARN: API недоступен: $url" >&2
  fi
}

# Maestro без --udid может взять первый adb-девайс (телефон), даже если APK ставили на эмулятор.
_maestro_run() {
  echo "[maestro-env] maestro → $MAESTRO_ADB_SERIAL"
  maestro --udid "$MAESTRO_ADB_SERIAL" "$@"
}