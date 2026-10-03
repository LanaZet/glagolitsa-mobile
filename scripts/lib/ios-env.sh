#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# iOS Simulator helpers for run-and-debug (same monorepo, same local Go/Postgres).

glagolitsa_ios_require_tools() {
  if ! command -v xcrun >/dev/null 2>&1; then
    glagolitsa_err "Нужен Xcode CLI (xcrun). Установите Xcode."
    return 1
  fi
  if ! xcrun simctl help >/dev/null 2>&1; then
    glagolitsa_err "simctl недоступен. Откройте Xcode хотя бы раз и примите license."
    return 1
  fi
  if ! command -v xcodebuild >/dev/null 2>&1; then
    glagolitsa_err "Нужен xcodebuild."
    return 1
  fi
  return 0
}

# Prefer already-booted sim; else first match by name (default iPhone 17 Pro).
glagolitsa_ios_resolve_simulator_udid() {
  local name="${IOS_SIMULATOR_NAME:-iPhone 17 Pro}"
  local udid

  udid="$(xcrun simctl list devices booted 2>/dev/null | sed -n 's/.*(\([A-F0-9-]\{36\}\)).*/\1/p' | head -1)"
  if [[ -n "$udid" ]]; then
    printf '%s' "$udid"
    return 0
  fi

  # Available devices matching name (prefer exact, then prefix).
  udid="$(
    xcrun simctl list devices available 2>/dev/null \
      | awk -v n="$name" '
          index($0, n) && /\([A-F0-9-]{36}\)/ && $0 !~ /unavailable/ {
            if (match($0, /\([A-F0-9-]{36}\)/)) {
              id = substr($0, RSTART + 1, RLENGTH - 2)
              print id
              exit
            }
          }'
  )"
  if [[ -z "$udid" ]]; then
    udid="$(
      xcrun simctl list devices available 2>/dev/null \
        | awk '
            /iPhone/ && /\([A-F0-9-]{36}\)/ && $0 !~ /unavailable/ {
              if (match($0, /\([A-F0-9-]{36}\)/)) {
                print substr($0, RSTART + 1, RLENGTH - 2)
                exit
              }
            }'
    )"
  fi
  printf '%s' "$udid"
}

glagolitsa_ios_boot_simulator() {
  local udid="$1"
  local state

  [[ -n "$udid" ]] || { glagolitsa_err "Не найден iOS Simulator UDID"; return 1; }

  state="$(xcrun simctl list devices 2>/dev/null | grep "$udid" | sed -n 's/.*(\(Booted\|Shutdown\|Creating[^)]*\)).*/\1/p' | head -1)"
  # State is usually " (Shutdown)" or " (Booted)" in the line — parse more reliably:
  if xcrun simctl list devices booted 2>/dev/null | grep -q "$udid"; then
    glagolitsa_log "iOS Simulator уже booted: $udid"
  else
    glagolitsa_log "Запускаем iOS Simulator $udid..."
    xcrun simctl boot "$udid" 2>/dev/null || true
    # Wait until booted
    local i
    for ((i = 1; i <= 60; i++)); do
      if xcrun simctl list devices booted 2>/dev/null | grep -q "$udid"; then
        break
      fi
      sleep 1
    done
    if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$udid"; then
      glagolitsa_err "Simulator не загрузился: $udid"
      return 1
    fi
  fi

  open -a Simulator --args -CurrentDeviceUDID "$udid" >/dev/null 2>&1 || open -a Simulator >/dev/null 2>&1 || true
  printf '%s' "$udid" >"${GLAGOLITSA_RUN_DIR:-.run}/ios-simulator.udid"
  glagolitsa_log "iOS Simulator готов: $udid"
}

# SDK folder name used by KMP embed path, e.g. iphonesimulator18.2
glagolitsa_ios_simulator_sdk_name() {
  local path
  path="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)"
  if [[ -n "$path" ]]; then
    basename "$path" .sdk
    return 0
  fi
  printf '%s' "iphonesimulator"
}

# Local API as seen from iOS Simulator (shares host network → 127.0.0.1, not 10.0.2.2).
glagolitsa_ios_local_api_url() {
  printf '%s' "${IOS_API_BASE_URL:-http://127.0.0.1:8080}"
}

glagolitsa_ios_stage_framework() {
  local root="${1:?root}"
  local configuration="${2:-Debug}"

  glagolitsa_log "Сборка Shared.framework (iosSimulatorArm64)..."
  (
    cd "$root"
    ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --quiet
  ) || {
    glagolitsa_err "Gradle linkDebugFrameworkIosSimulatorArm64 failed"
    return 1
  }

  local src="$root/shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework"
  [[ -d "$src" ]] || {
    glagolitsa_err "Framework не найден: $src"
    return 1
  }

  # Xcode uses versioned SDK_NAME (iphonesimulator26.2); stage under several names.
  local sdk_path sdk_basename versioned
  sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)"
  sdk_basename="$(basename "${sdk_path:-iphonesimulator.sdk}" .sdk)"
  versioned="$(echo "$sdk_basename" | tr '[:upper:]' '[:lower:]')"
  # Also try PLATFORM_NAME style from xcodebuild
  local platform_sdk
  platform_sdk="$(
    xcodebuild -sdk iphonesimulator -showBuildSettings 2>/dev/null \
      | awk -F' = ' '/^[[:space:]]*SDK_NAME[[:space:]]*=/{print $2; exit}'
  )"

  local name dest_dir
  for name in "iphonesimulator" "$versioned" "$platform_sdk" "$sdk_basename"; do
    [[ -n "$name" ]] || continue
    dest_dir="$root/shared/build/xcode-frameworks/${configuration}/${name}"
    mkdir -p "$dest_dir"
    rm -rf "$dest_dir/Shared.framework"
    cp -R "$src" "$dest_dir/Shared.framework"
  done
  # Stable path for FRAMEWORK_SEARCH_PATHS override
  dest_dir="$root/shared/build/xcode-frameworks/${configuration}/cli"
  mkdir -p "$dest_dir"
  rm -rf "$dest_dir/Shared.framework"
  cp -R "$src" "$dest_dir/Shared.framework"
  glagolitsa_log "Framework staged (cli + sdk aliases): $dest_dir/Shared.framework"
}

glagolitsa_ios_build_app() {
  local root="${1:?root}"
  local derived="${2:?derived data path}"
  local configuration="${3:-Debug}"
  local fw_dir

  glagolitsa_ios_stage_framework "$root" "$configuration" || return 1
  fw_dir="$root/shared/build/xcode-frameworks/${configuration}/cli"
  local bin_fw="$root/shared/build/bin/iosSimulatorArm64/debugFramework"

  mkdir -p "$derived"
  if [[ -f "$root/iosApp/Podfile" ]]; then
    if [[ ! -d "$root/iosApp/Pods" ]]; then
      glagolitsa_log "pod install (LibSignalClient)..."
      (cd "$root/iosApp" && pod install --silent)
    fi
  fi
  glagolitsa_log "xcodebuild iosApp (iphonesimulator)..."
  # Simulator: signing not required. Point search paths at staged + gradle output.
  local xcode_args=()
  if [[ -d "$root/iosApp/iosApp.xcworkspace" ]]; then
    xcode_args+=(-workspace "$root/iosApp/iosApp.xcworkspace")
  else
    xcode_args+=(-project "$root/iosApp/iosApp.xcodeproj")
  fi
  xcodebuild \
    "${xcode_args[@]}" \
    -scheme iosApp \
    -configuration "$configuration" \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$derived" \
    CODE_SIGNING_ALLOWED=YES \
    CODE_SIGN_IDENTITY=- \
    CODE_SIGNING_REQUIRED=YES \
    ONLY_ACTIVE_ARCH=YES \
    ARCHS=arm64 \
    FRAMEWORK_SEARCH_PATHS="$fw_dir $bin_fw \$(inherited)" \
    build

  local app_dir res_src
  app_dir="$(glagolitsa_ios_find_app_bundle "$derived")"
  res_src="$root/shared/build/generated/compose/resourceGenerator/assembledResources/iosSimulatorArm64Main/composeResources"
  if [[ ! -d "$res_src" ]]; then
    res_src="$(find "$root/shared/build/generated/compose" -type d -path "*/iosSimulatorArm64Main/composeResources" 2>/dev/null | head -1 || true)"
  fi
  if [[ -d "$app_dir" && -d "$res_src" ]]; then
    # Compose iOS reader resolves: <bundle>/compose-resources/<ResourceItem.path>
    # ResourceItem.path = composeResources/<package>/...
    dest="$app_dir/compose-resources/composeResources"
    mkdir -p "$dest"
    rsync -a --delete "$res_src/" "$dest/"
    glagolitsa_log "Compose resources -> $dest"
  else
    glagolitsa_warn "Compose resources missing (app=$app_dir res=$res_src)"
  fi
}

glagolitsa_ios_find_app_bundle() {
  local derived="${1:?derived}"
  local app
  app="$(find "$derived" -type d -name 'Glagolitsa.app' -path '*/Debug-iphonesimulator/*' 2>/dev/null | head -1)"
  if [[ -z "$app" ]]; then
    app="$(find "$derived" -type d -name 'Glagolitsa.app' 2>/dev/null | head -1)"
  fi
  printf '%s' "$app"
}

glagolitsa_ios_install_and_launch() {
  local udid="${1:?udid}"
  local app_path="${2:?app}"
  local bundle_id="${3:-com.glagolitsa.mobile}"

  [[ -d "$app_path" ]] || { glagolitsa_err "App bundle не найден: $app_path"; return 1; }

  glagolitsa_log "Устанавливаем $app_path → sim $udid"
  xcrun simctl install "$udid" "$app_path"

  glagolitsa_log "Запускаем $bundle_id"
  xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
  if [[ -n "${GLAGOLITSA_API_BASE_URL:-}" ]]; then
    glagolitsa_log "sim env GLAGOLITSA_API_BASE_URL=$GLAGOLITSA_API_BASE_URL"
    xcrun simctl spawn "$udid" launchctl setenv GLAGOLITSA_API_BASE_URL "$GLAGOLITSA_API_BASE_URL" >/dev/null 2>&1 || true
    SIMCTL_CHILD_GLAGOLITSA_API_BASE_URL="$GLAGOLITSA_API_BASE_URL" \
      xcrun simctl launch "$udid" "$bundle_id"
  else
    xcrun simctl launch "$udid" "$bundle_id"
  fi || {
    glagolitsa_err "launch failed — проверьте bundle id / подпись"
    return 1
  }
}
