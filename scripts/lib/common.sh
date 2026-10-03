#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Общие хелперы для dev-скриптов Glagolitsa.

glagolitsa_log() { printf '\033[1;34m[glagolitsa]\033[0m %s\n' "$*"; }
glagolitsa_warn() { printf '\033[1;33m[glagolitsa]\033[0m %s\n' "$*"; }
glagolitsa_err() { printf '\033[1;31m[glagolitsa]\033[0m %s\n' "$*" >&2; }

glagolitsa_default_api_from_gradle() {
  local root="${1:?root dir}"
  local file line
  # local.properties is gitignored and wins, so a public checkout has no server URL.
  for file in "$root/local.properties" "$root/gradle.properties"; do
    line="$(grep -E '^apiBaseUrl=' "$file" 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
    if [[ -n "$line" ]]; then
      printf '%s' "$line"
      return 0
    fi
  done
}

glagolitsa_is_remote_api_url() {
  local url="$1"
  [[ "$url" != *"://10.0.2.2"* && "$url" != *"://127.0.0.1"* && "$url" != *"://localhost"* ]]
}

glagolitsa_check_api_reachable() {
  local api_url="$1"
  local health_url="${api_url%/}/api/health"
  if curl -sf --connect-timeout 8 --max-time 15 "$health_url" >/dev/null 2>&1; then
    glagolitsa_log "API доступен: $health_url"
    return 0
  fi
  glagolitsa_warn "API не отвечает: $health_url"
  if glagolitsa_is_remote_api_url "$api_url"; then
    glagolitsa_warn "Проверьте VPS и сеть на устройстве тестера"
  fi
  return 1
}

glagolitsa_start_required_hot_reload() {
  local root="${1:?root dir}"
  local api_url="${2:?api url}"
  glagolitsa_log "Обязательный hot reload: запускаю watcher на эмуляторе"
  "$root/scripts/hot-reload.sh" --api "$api_url" --background
}
