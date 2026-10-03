#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Создаёт приватные GitHub-репозитории и пушит текущий проект.
#
# Перед запуском:
#   gh auth login
#
# Использование (из glagolitsa-mobile):
#   ./scripts/setup-private-remote.sh
#
# Или для бэкенда:
#   cd ../glagolitsa && ./../glagolitsa-mobile/scripts/setup-private-remote.sh --backend

set -euo pipefail

MODE="mobile"
REPO_NAME=""
GITHUB_OWNER=""

for arg in "$@"; do
  case "$arg" in
    --backend) MODE="backend" ;;
    --owner=*) GITHUB_OWNER="${arg#*=}" ;;
    --name=*) REPO_NAME="${arg#*=}" ;;
    -h|--help)
      echo "Usage: setup-private-remote.sh [--backend] [--owner=USER] [--name=REPO]"
      exit 0
      ;;
  esac
done

if ! command -v gh >/dev/null 2>&1; then
  echo "Установите GitHub CLI: brew install gh" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "Сначала войдите: gh auth login" >&2
  exit 1
fi

if [[ -z "$GITHUB_OWNER" ]]; then
  GITHUB_OWNER="$(gh api user -q .login)"
fi

if [[ "$MODE" == "backend" ]]; then
  ROOT_DIR="$(cd "$(dirname "$0")/../../glagolitsa" && pwd)"
  REPO_NAME="${REPO_NAME:-glagolitsa}"
else
  ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
  REPO_NAME="${REPO_NAME:-glagolitsa-mobile}"
fi

cd "$ROOT_DIR"

if [[ ! -d .git ]]; then
  git init -b main
  git add .
  git commit -m "Initial commit"
fi

if git remote get-url origin >/dev/null 2>&1; then
  echo "Remote origin уже настроен: $(git remote get-url origin)"
  exit 0
fi

gh repo create "$GITHUB_OWNER/$REPO_NAME" \
  --private \
  --source=. \
  --remote=origin \
  --push \
  --description "Glagolitsa ${MODE} (private)"

echo "Приватный репозиторий создан: https://github.com/$GITHUB_OWNER/$REPO_NAME"