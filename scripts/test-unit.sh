#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Юнит-тесты shared-модуля (commonTest + androidUnitTest).
#
#   ./scripts/test-unit.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT_DIR"
./gradlew :shared:testDebugUnitTest :shared:desktopTest --quiet

echo "Все тесты прошли (commonTest + androidUnitTest + desktopTest)."