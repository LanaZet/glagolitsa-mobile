#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Point this clone at repo-managed hooks (.githooks/), so new files get
# license headers automatically on commit.
#
#   ./scripts/install-git-hooks.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

chmod +x "$ROOT/.githooks/pre-commit" 2>/dev/null || true
chmod +x "$ROOT/scripts/add-license-headers.sh" 2>/dev/null || true

git config core.hooksPath .githooks
echo "OK  core.hooksPath=$(git config core.hooksPath)"
echo "    pre-commit will add license headers to staged .kt/.go/.sql/.sh files"
