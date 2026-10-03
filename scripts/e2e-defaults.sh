# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# shellcheck shell=bash
# Общие константы E2E / pre-release. Подключать: source "$(dirname "$0")/e2e-defaults.sh"

E2E_DEFAULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/dev-accounts.sh
source "$E2E_DEFAULTS_DIR/lib/dev-accounts.sh"

e2e_is_local_api_url() {
  local url="${1:-}"
  [[ -z "$url" ]] && return 0
  case "$url" in
    *10.0.2.2* | *127.0.0.1* | *localhost*) return 0 ;;
    *) return 1 ;;
  esac
}

e2e_disable_proxy_for_local_api() {
  local url="${1:-}"
  if ! e2e_is_local_api_url "$url"; then
    return 0
  fi
  unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
  export NO_PROXY="localhost,127.0.0.1,10.0.2.2${NO_PROXY:+,$NO_PROXY}"
  export no_proxy="localhost,127.0.0.1,10.0.2.2${no_proxy:+,$no_proxy}"
}

e2e_require_vars() {
  local missing=()
  local name
  for name in "$@"; do
    if [[ -z "${!name:-}" ]]; then
      missing+=("$name")
    fi
  done
  if ((${#missing[@]} > 0)); then
    printf 'Missing required env: %s\n' "${missing[*]}" >&2
    return 2
  fi
}

# Remote checks need an explicit URL and accounts. Nothing personal is baked in.
E2E_DEFAULT_BASE_URL="${E2E_DEFAULT_BASE_URL:-}"
E2E_DEFAULT_VPS_HOST="${E2E_DEFAULT_VPS_HOST:-}"
E2E_DEFAULT_VPS_USER="${E2E_DEFAULT_VPS_USER:-root}"
E2E_DEFAULT_DEPLOY_DIR="${E2E_DEFAULT_DEPLOY_DIR:-/root/glagolitsa-deploy/deploy}"
E2E_DEFAULT_VERIFY_USERNAME="${E2E_DEFAULT_VERIFY_USERNAME:-}"
E2E_DEFAULT_VERIFY_PASSWORD="${E2E_DEFAULT_VERIFY_PASSWORD:-}"
E2E_DEFAULT_TEST_PASSWORD="${E2E_DEFAULT_TEST_PASSWORD:-}"
E2E_DEFAULT_SENDER_USERNAME="${E2E_DEFAULT_SENDER_USERNAME:-}"
E2E_DEFAULT_SENDER_PASSWORD="${E2E_DEFAULT_SENDER_PASSWORD:-}"
E2E_DEFAULT_RECIPIENT_USERNAME="${E2E_DEFAULT_RECIPIENT_USERNAME:-}"
E2E_DEFAULT_RECIPIENT_PASSWORD="${E2E_DEFAULT_RECIPIENT_PASSWORD:-}"

# Локальный docker API (Marco↔Polo на эмуляторе + телефоне)
E2E_LOCAL_BASE_URL="${E2E_LOCAL_BASE_URL:-http://127.0.0.1:8080}"
E2E_LOCAL_SENDER_USERNAME="${E2E_LOCAL_SENDER_USERNAME:-$GLAGOLITSA_DEV_PRIMARY_USERNAME}"
E2E_LOCAL_SENDER_PASSWORD="${E2E_LOCAL_SENDER_PASSWORD:-$GLAGOLITSA_DEV_PRIMARY_PASSWORD}"
E2E_LOCAL_RECIPIENT_USERNAME="${E2E_LOCAL_RECIPIENT_USERNAME:-$GLAGOLITSA_DEV_SECONDARY_USERNAME}"
E2E_LOCAL_RECIPIENT_PASSWORD="${E2E_LOCAL_RECIPIENT_PASSWORD:-$GLAGOLITSA_DEV_SECONDARY_PASSWORD}"
