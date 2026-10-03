#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Compose Hot Reload — песочница для дизайна (desktop JVM).
# Редактируйте composable в shared/, сохраните — UI обновится без перезапуска.
#
# Usage:
#   ./scripts/hot-reload-desktop.sh              # каталог экранов (дизайн)
#   ./scripts/hot-reload-desktop.sh --full       # полное приложение + API
#   ./scripts/hot-reload-desktop.sh --no-reload  # без auto-reload
#
# Дизайн-режим не требует сервера. --full ходит на http://127.0.0.1:8080.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODE="design"
AUTO_RELOAD=1

for arg in "$@"; do
  case "$arg" in
    --full) MODE="full" ;;
    --design) MODE="design" ;;
    --no-reload) AUTO_RELOAD=0 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
  esac
done

cd "$ROOT_DIR"

GRADLE_ARGS=(--console=plain)
if [[ "$AUTO_RELOAD" -eq 1 ]]; then
  GRADLE_ARGS+=(--autoReload)
fi

case "$MODE" in
  design)
    GRADLE_ARGS+=(--className=com.glagolitsa.desktop.DesignMainKt)
    ;;
  full)
    GRADLE_ARGS+=(--className=com.glagolitsa.desktop.DesktopMainKt)
    ;;
esac

exec ./gradlew :desktopApp:hotDevJvm "${GRADLE_ARGS[@]}"