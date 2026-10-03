#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Shared local-development accounts used by debug shortcuts and local DM smoke.
# Keep production/prerelease builds explicit: public builds should pass empty
# dev shortcut values instead of relying on these defaults.

GLAGOLITSA_DEV_PRIMARY_USERNAME="${GLAGOLITSA_DEV_PRIMARY_USERNAME:-Marco}"
GLAGOLITSA_DEV_PRIMARY_PASSWORD="${GLAGOLITSA_DEV_PRIMARY_PASSWORD:-marco123}"
GLAGOLITSA_DEV_PRIMARY_LABEL="${GLAGOLITSA_DEV_PRIMARY_LABEL:-$GLAGOLITSA_DEV_PRIMARY_USERNAME}"

GLAGOLITSA_DEV_SECONDARY_USERNAME="${GLAGOLITSA_DEV_SECONDARY_USERNAME:-Polo}"
GLAGOLITSA_DEV_SECONDARY_PASSWORD="${GLAGOLITSA_DEV_SECONDARY_PASSWORD:-polo123}"
GLAGOLITSA_DEV_SECONDARY_LABEL="${GLAGOLITSA_DEV_SECONDARY_LABEL:-$GLAGOLITSA_DEV_SECONDARY_USERNAME}"

glagolitsa_dev_account_summary() {
  printf '%s/%s, %s/%s' \
    "$GLAGOLITSA_DEV_PRIMARY_USERNAME" "$GLAGOLITSA_DEV_PRIMARY_PASSWORD" \
    "$GLAGOLITSA_DEV_SECONDARY_USERNAME" "$GLAGOLITSA_DEV_SECONDARY_PASSWORD"
}
