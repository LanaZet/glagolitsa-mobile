#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Общее определение Android SDK / adb / emulator для dev-скриптов.

resolve_android_sdk() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return 0
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return 0
  fi

  local adb_path sdk
  adb_path="$(command -v adb 2>/dev/null || true)"
  if [[ -n "$adb_path" && -x "$adb_path" ]]; then
    sdk="$(cd "$(dirname "$adb_path")/.." && pwd)"
    if [[ -x "$sdk/emulator/emulator" ]]; then
      printf '%s\n' "$sdk"
      return 0
    fi
  fi

  printf '%s\n' "$HOME/Library/Android/sdk"
}

init_android_env() {
  ANDROID_SDK="$(resolve_android_sdk)"
  EMULATOR="$ANDROID_SDK/emulator/emulator"
  if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    ADB="$ANDROID_SDK/platform-tools/adb"
  fi
}

glagolitsa_android_first_emulator_serial() {
  "$ADB" devices 2>/dev/null | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

glagolitsa_android_first_physical_device_serial() {
  "$ADB" devices 2>/dev/null | awk 'NR > 1 && $1 !~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

glagolitsa_android_physical_device_serials() {
  "$ADB" devices 2>/dev/null | awk 'NR > 1 && $1 !~ /^emulator-/ && $2 == "device" { print $1 }'
}
