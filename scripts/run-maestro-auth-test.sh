#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Level 4: Maestro UI test для device gate на входе.
# По умолчанию — реальное USB-устройство.
#
#   ./scripts/run-maestro-auth-test.sh
#   ADB_SERIAL=<adb-serial> USERNAME=marco PASSWORD='...' ./scripts/run-maestro-auth-test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=maestro-env.sh
source "$ROOT/scripts/maestro-env.sh"

USERNAME="${USERNAME:-flow-maestro}"
PASSWORD="${PASSWORD:-FlowTest2026!}"

_maestro_install_apk

echo "==> Maestro device-gate on $MAESTRO_ADB_SERIAL"
_maestro_run test "$ROOT/maestro/flows/login-device-gate.yaml" \
  -e "USERNAME=$USERNAME" \
  -e "PASSWORD=$PASSWORD"