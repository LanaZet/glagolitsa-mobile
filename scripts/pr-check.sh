#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Fast PR gate: deterministic checks — no Maestro, no phone, no live API.
#
#   ./scripts/pr-check.sh
#
# Runs:
#   1) client unit (shared commonTest + androidUnitTest + desktopTest)
#   2) server go test (sibling ../glagolitsa/server unless SERVER_DIR set)
#
# Skip server only when needed:
#   SKIP_SERVER_TESTS=1 ./scripts/pr-check.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT_DIR"

# License headers on sources (Mattermost-style); auto-fixed by pre-commit hook.
if [[ -f "$ROOT_DIR/scripts/add-license-headers.sh" ]]; then
  bash "$ROOT_DIR/scripts/add-license-headers.sh" --check
fi

# Default messaging stack without live network / UI e2e.
"$ROOT_DIR/scripts/test-messaging.sh"
