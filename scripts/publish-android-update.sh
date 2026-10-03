#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Собрать тестовый Android update и опубликовать его в server policy.
#
# Flow:
#   1) читает текущую /api/client/update-policy на VPS;
#   2) bump-ает android.latest_version/latest_build, если версия не задана явно;
#   3) собирает APK с этой versionName/versionCode;
#   4) выкладывает APK в https://api.glagolit.me/downloads/;
#   5) обновляет /etc/glagolitsa/production.env и перезапускает glagolitsa-api.
#
# Важно: server defaults трактуют пустой CLIENT_ANDROID_STORE_URL как default Play URL.
# Поэтому скрипт выставляет download_url для APK; приложение предпочитает
# прямую загрузку и не показывает store-кнопку рядом с APK.
#
# Использование:
#   ./scripts/publish-android-update.sh
#   ./scripts/publish-android-update.sh --version-name 0.2.0 --version-code 2
#   ./scripts/publish-android-update.sh --no-build  # переиспользовать текущий androidApp-debug.apk
#   ./scripts/publish-android-update.sh --dry-run   # показать план без сборки/деплоя
#   ./scripts/publish-android-update.sh --via-phone # форс SSH через USB-телефон
#
# SSH transport (по умолчанию auto):
#   1) пробует прямой SSH на VPS;
#   2) если недоступен — fallback через USB-телефон (ProxyCommand adb nc).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
# shellcheck source=lib/android-env.sh
source "$ROOT_DIR/scripts/lib/android-env.sh"

API_BASE_URL=""
: "${VPS_HOST:?Set VPS_HOST to the server address}"
VPS_USER="${VPS_USER:-root}"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-api.glagolit.me}"
PUBLIC_SCHEME="${PUBLIC_SCHEME:-https}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
PHONE_SERIAL="${PHONE_SERIAL:-${ADB_SERIAL:-}}"
REMOTE_DOWNLOAD_DIR="${REMOTE_DOWNLOAD_DIR:-/var/www/glagolitsa-downloads}"
REMOTE_DEPLOY_DIR="${REMOTE_DEPLOY_DIR:-/root/glagolitsa-deploy}"
REMOTE_APK_KEEP="${REMOTE_APK_KEEP:-3}"
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"
BUILD_APK=true
DRY_RUN=false
VIA_PHONE=false

APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

usage() {
  cat <<'EOF'
Публикация Android update для тестеров.

  ./scripts/publish-android-update.sh
  ./scripts/publish-android-update.sh --version-name 0.2.0 --version-code 2
  ./scripts/publish-android-update.sh --api https://api.glagolit.me
  ./scripts/publish-android-update.sh --no-build
  ./scripts/publish-android-update.sh --dry-run
  ./scripts/publish-android-update.sh --via-phone
  ./scripts/publish-android-update.sh --via-phone --phone <adb-serial>

SSH: сначала прямой доступ к VPS; телефон нужен только если SSH с Mac недоступен
(или если явно передан --via-phone).

Переменные:
  VPS_HOST=<public-ip>
  VPS_USER=root
  SSH_KEY=~/.ssh/id_ed25519
  PUBLIC_DOMAIN=api.example.com
  PUBLIC_SCHEME=https
  PHONE_SERIAL=<adb-serial>
  REMOTE_APK_KEEP=3
EOF
}

while (($#)); do
  case "$1" in
    --version-name)
      VERSION_NAME="${2:?укажи versionName после --version-name}"
      shift
      ;;
    --version-code)
      VERSION_CODE="${2:?укажи versionCode после --version-code}"
      shift
      ;;
    --api)
      API_BASE_URL="${2:?укажи URL после --api}"
      shift
      ;;
    --no-build)
      BUILD_APK=false
      ;;
    --dry-run)
      DRY_RUN=true
      BUILD_APK=false
      ;;
    --via-phone)
      VIA_PHONE=true
      ;;
    --phone)
      VIA_PHONE=true
      PHONE_SERIAL="${2:?укажи adb serial после --phone}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      glagolitsa_err "Неизвестный аргумент: $1"
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ -z "$API_BASE_URL" ]]; then
  API_BASE_URL="$(glagolitsa_default_api_from_gradle "$ROOT_DIR")"
fi
if [[ -z "$API_BASE_URL" ]]; then
  API_BASE_URL="${PUBLIC_SCHEME}://${PUBLIC_DOMAIN}"
fi

SSH_OPTS=(
  -i "$SSH_KEY"
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o StrictHostKeyChecking=accept-new
)
REMOTE="$VPS_USER@$VPS_HOST"

probe_direct_ssh() {
  # BatchMode + короткий timeout: без пароля, без зависания на VPN/firewall.
  ssh "${SSH_OPTS[@]}" -o ConnectTimeout=5 -o ConnectionAttempts=1 \
    "$REMOTE" true >/dev/null 2>&1
}

enable_phone_ssh_proxy() {
  local reason="${1:-нужен USB-телефон}"
  init_android_env
  if [[ -z "$PHONE_SERIAL" ]]; then
    PHONE_SERIAL="$(glagolitsa_android_first_physical_device_serial || true)"
  fi
  if [[ -z "$PHONE_SERIAL" ]]; then
    glagolitsa_err "$reason"
    glagolitsa_err "USB-телефон не найден. Подключите телефон или: --via-phone --phone SERIAL"
    if command -v "$ADB" >/dev/null 2>&1 || [[ -x "${ADB:-}" ]]; then
      "$ADB" devices >&2 || true
    fi
    exit 1
  fi
  glagolitsa_log "SSH transport: USB phone $PHONE_SERIAL -> $VPS_HOST:22"
  if ! "$ADB" -s "$PHONE_SERIAL" shell -T "toybox nc -w 3 $VPS_HOST 22 </dev/null" >/dev/null 2>&1; then
    glagolitsa_err "Телефон $PHONE_SERIAL не достучался до $VPS_HOST:22"
    exit 1
  fi
  SSH_OPTS+=(
    -o "ProxyCommand=$ADB -s $PHONE_SERIAL shell -T toybox nc %h %p"
  )
}

configure_ssh_transport() {
  if [[ "$VIA_PHONE" == true ]]; then
    glagolitsa_log "SSH transport: forced via phone (--via-phone)"
    enable_phone_ssh_proxy "Принудительный режим --via-phone."
    return
  fi

  glagolitsa_log "Проверка прямого SSH $REMOTE ..."
  if probe_direct_ssh; then
    glagolitsa_log "SSH transport: direct $REMOTE (телефон не нужен)"
    return
  fi

  glagolitsa_warn "Прямой SSH на $REMOTE недоступен — fallback через USB-телефон"
  enable_phone_ssh_proxy "Прямой SSH недоступен, а телефон для ProxyCommand не подключён."
}

configure_ssh_transport

remote() {
  ssh "${SSH_OPTS[@]}" "$REMOTE" "$@"
}

sh_quote() {
  local value="$1"
  value=${value//\'/\'\\\'\'}
  printf "'%s'" "$value"
}

fetch_policy() {
  remote 'curl -fsS http://127.0.0.1:8080/api/client/update-policy'
}

compute_next_version() {
  local policy_json="$1"
  printf '%s' "$policy_json" | python3 -c '
import json, sys
p = json.load(sys.stdin)
a = p.get("android") or {}
latest = str(a.get("latest_version") or a.get("min_version") or "0.1.0")
build = int(a.get("latest_build") or a.get("min_build") or 1)
parts = latest.strip().lstrip("v").split(".")
nums = []
for part in parts:
    digits = ""
    for ch in part:
        if not ch.isdigit():
            break
        digits += ch
    nums.append(int(digits or 0))
while len(nums) < 3:
    nums.append(0)
nums[2] += 1
print(f"{nums[0]}.{nums[1]}.{nums[2]} {build + 1}")
'
}

gradle_property() {
  local key="${1:?key}"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$ROOT_DIR/gradle.properties" 2>/dev/null || true
}

semver_le() {
  local a="${1:?a}"
  local b="${2:?b}"
  python3 - "$a" "$b" <<'PY'
import sys


def parse(raw):
    core = raw.strip().lstrip("v").split("-", 1)[0].split("+", 1)[0]
    parts = []
    for part in core.split("."):
        digits = ""
        for ch in part:
            if not ch.isdigit():
                break
            digits += ch
        parts.append(int(digits or 0))
    return parts or [0]


a = parse(sys.argv[1])
b = parse(sys.argv[2])
n = max(len(a), len(b))
a += [0] * (n - len(a))
b += [0] * (n - len(b))
sys.exit(0 if a <= b else 1)
PY
}

bump_patch_version() {
  local version="${1:?version}"
  python3 - "$version" <<'PY'
import sys

parts = sys.argv[1].strip().lstrip("v").split("-", 1)[0].split("+", 1)[0].split(".")
nums = []
for part in parts:
    digits = ""
    for ch in part:
        if not ch.isdigit():
            break
        digits += ch
    nums.append(int(digits or 0))
while len(nums) < 3:
    nums.append(0)
nums[2] += 1
print(f"{nums[0]}.{nums[1]}.{nums[2]}")
PY
}

prepare_remote_downloads() {
  glagolitsa_log "Подготовка Caddy /downloads на VPS"
  local public_domain_q remote_download_dir_q
  public_domain_q="$(sh_quote "$PUBLIC_DOMAIN")"
  remote_download_dir_q="$(sh_quote "$REMOTE_DOWNLOAD_DIR")"
  remote "PUBLIC_DOMAIN=$public_domain_q REMOTE_DOWNLOAD_DIR=$remote_download_dir_q bash -s" <<'REMOTE'
set -euo pipefail
mkdir -p "$REMOTE_DOWNLOAD_DIR"
chmod 0755 "$REMOTE_DOWNLOAD_DIR"

if ! grep -q "$REMOTE_DOWNLOAD_DIR" /etc/caddy/Caddyfile 2>/dev/null; then
  backup="/etc/caddy/Caddyfile.backup-downloads-$(date +%Y%m%d-%H%M%S)"
  cp /etc/caddy/Caddyfile "$backup"
  cat >/etc/caddy/Caddyfile <<EOF
${PUBLIC_DOMAIN} {
    handle_path /downloads/* {
        root * ${REMOTE_DOWNLOAD_DIR}
        file_server
    }

    reverse_proxy 127.0.0.1:8080
}

:80 {
    handle_path /downloads/* {
        root * ${REMOTE_DOWNLOAD_DIR}
        file_server
    }

    reverse_proxy 127.0.0.1:8080
}
EOF
  caddy validate --config /etc/caddy/Caddyfile >/dev/null
  systemctl reload caddy
fi
REMOTE
}

publish_apk() {
  glagolitsa_log "Загрузка APK на VPS"
  local remote_deploy_dir_q remote_download_dir_q apk_name_q
  remote_deploy_dir_q="$(sh_quote "$REMOTE_DEPLOY_DIR")"
  remote_download_dir_q="$(sh_quote "$REMOTE_DOWNLOAD_DIR")"
  apk_name_q="$(sh_quote "$apk_name")"
  remote "mkdir -p $remote_deploy_dir_q $remote_download_dir_q"
  scp "${SSH_OPTS[@]}" "$APK_PATH" "$REMOTE:$REMOTE_DEPLOY_DIR/$apk_name"
  remote "install -m 0644 $remote_deploy_dir_q/$apk_name_q $remote_download_dir_q/$apk_name_q"
}

cleanup_remote_apks() {
  glagolitsa_log "Очистка старых APK на VPS (keep=$REMOTE_APK_KEEP)"
  local remote_deploy_dir_q remote_download_dir_q remote_apk_keep_q
  remote_deploy_dir_q="$(sh_quote "$REMOTE_DEPLOY_DIR")"
  remote_download_dir_q="$(sh_quote "$REMOTE_DOWNLOAD_DIR")"
  remote_apk_keep_q="$(sh_quote "$REMOTE_APK_KEEP")"
  remote "REMOTE_DEPLOY_DIR=$remote_deploy_dir_q REMOTE_DOWNLOAD_DIR=$remote_download_dir_q REMOTE_APK_KEEP=$remote_apk_keep_q bash -s" <<'REMOTE'
set -euo pipefail

cleanup_dir() {
  local dir="$1"
  local keep="$2"
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -type f -name 'glagolitsa-*.apk' -printf '%T@ %p\n' |
    sort -rn |
    awk -v keep="$keep" 'NR > keep { $1=""; sub(/^ /, ""); print }' |
    xargs -r rm -f
}

cleanup_dir "$REMOTE_DEPLOY_DIR" "$REMOTE_APK_KEEP"
cleanup_dir "$REMOTE_DOWNLOAD_DIR" "$REMOTE_APK_KEEP"
REMOTE
}

update_policy_env() {
  # One SSH call for all keys: partial multi-call upserts used to leave
  # VERSION/BUILD updated while DOWNLOAD_URL stayed stale (and restart never ran).
  glagolitsa_log "Обновление production.env policy (atomic)"
  local version_name_q version_code_q download_url_q
  version_name_q="$(sh_quote "$VERSION_NAME")"
  version_code_q="$(sh_quote "$VERSION_CODE")"
  download_url_q="$(sh_quote "$download_url")"
  remote "VERSION_NAME=$version_name_q VERSION_CODE=$version_code_q DOWNLOAD_URL=$download_url_q bash -s" <<'REMOTE'
set -euo pipefail
env_file=/etc/glagolitsa/production.env
cp "$env_file" "/etc/glagolitsa/production.env.backup-update-$(date +%Y%m%d-%H%M%S)"
python3 - <<'PY'
import os
from pathlib import Path

path = Path("/etc/glagolitsa/production.env")
updates = {
    "CLIENT_ANDROID_LATEST_VERSION": os.environ["VERSION_NAME"],
    "CLIENT_ANDROID_LATEST_BUILD": os.environ["VERSION_CODE"],
    "CLIENT_ANDROID_DOWNLOAD_URL": os.environ["DOWNLOAD_URL"],
}
lines = path.read_text().splitlines(keepends=True)
out = []
seen = set()
for line in lines:
    raw = line.rstrip("\n")
    if "=" in raw and not raw.lstrip().startswith("#"):
        key = raw.split("=", 1)[0]
        if key in updates:
            out.append(f"{key}={updates[key]}\n")
            seen.add(key)
            continue
    out.append(line if line.endswith("\n") else line + "\n")
for key, value in updates.items():
    if key not in seen:
        out.append(f"{key}={value}\n")
path.write_text("".join(out))
path.chmod(0o600)
for key in updates:
    for line in path.read_text().splitlines():
        if line.startswith(key + "="):
            print(line)
            break
    else:
        raise SystemExit(f"missing key after upsert: {key}")
PY
REMOTE
}

restart_and_verify() {
  glagolitsa_log "Restart glagolitsa-api"
  remote 'systemctl restart glagolitsa-api; sleep 2; systemctl is-active glagolitsa-api'

  glagolitsa_log "Проверка policy на VPS (must match published version)"
  local version_name_q version_code_q download_url_q apk_name_q remote_download_dir_q
  version_name_q="$(sh_quote "$VERSION_NAME")"
  version_code_q="$(sh_quote "$VERSION_CODE")"
  download_url_q="$(sh_quote "$download_url")"
  apk_name_q="$(sh_quote "$apk_name")"
  remote_download_dir_q="$(sh_quote "$REMOTE_DOWNLOAD_DIR")"
  remote "EXPECT_VERSION=$version_name_q EXPECT_BUILD=$version_code_q EXPECT_URL=$download_url_q bash -s" <<'REMOTE'
set -euo pipefail
python3 - <<'PY'
import json, os, subprocess, sys, time

expect_version = os.environ["EXPECT_VERSION"]
expect_build = int(os.environ["EXPECT_BUILD"])
expect_url = os.environ["EXPECT_URL"]

policy = None
last_err = None
for _ in range(10):
    try:
        raw = subprocess.check_output(
            ["curl", "-fsS", "http://127.0.0.1:8080/api/client/update-policy"],
            text=True,
        )
        policy = json.loads(raw)
        break
    except Exception as exc:  # noqa: BLE001 — retry until API is up
        last_err = exc
        time.sleep(1)
if policy is None:
    raise SystemExit(f"update-policy unreachable after restart: {last_err}")

android = policy.get("android") or {}
got_version = str(android.get("latest_version") or "")
got_build = int(android.get("latest_build") or 0)
got_url = str(android.get("download_url") or "")
print(json.dumps(android, ensure_ascii=False))
errors = []
if got_version != expect_version:
    errors.append(f"latest_version: got {got_version!r}, want {expect_version!r}")
if got_build != expect_build:
    errors.append(f"latest_build: got {got_build}, want {expect_build}")
if got_url != expect_url:
    errors.append(f"download_url: got {got_url!r}, want {expect_url!r}")
if errors:
    raise SystemExit("policy mismatch after restart:\n  " + "\n  ".join(errors))
print("policy OK")
PY
REMOTE

  glagolitsa_log "Проверка APK на диске и через Caddy"
  remote "APK_NAME=$apk_name_q REMOTE_DOWNLOAD_DIR=$remote_download_dir_q bash -s" <<'REMOTE'
set -euo pipefail
path="$REMOTE_DOWNLOAD_DIR/$APK_NAME"
[[ -f "$path" ]] || { echo "APK missing: $path" >&2; exit 1; }
ls -lh "$path"
# Prefer file-server on :80 downloads path; fall back to size check only.
code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  -L "http://127.0.0.1/downloads/$APK_NAME" || true)"
if [[ "$code" != "200" ]]; then
  echo "WARN: Caddy returned HTTP $code for /downloads/$APK_NAME (file exists on disk)" >&2
else
  curl -sSI --max-time 10 -L "http://127.0.0.1/downloads/$APK_NAME" | head -n 8
fi
REMOTE
}

policy_json="$(fetch_policy)"
read -r computed_version_name computed_version_code <<<"$(compute_next_version "$policy_json")"
local_version_name="$(gradle_property appVersionName)"
local_version_code="$(gradle_property appVersionCode)"

if [[ -z "$VERSION_NAME" ]]; then
  VERSION_NAME="$computed_version_name"
  if [[ -n "$local_version_name" ]] && semver_le "$VERSION_NAME" "$local_version_name"; then
    VERSION_NAME="$(bump_patch_version "$local_version_name")"
    glagolitsa_warn "server latest_version отстаёт от gradle.properties ($local_version_name); auto-bump versionName=$VERSION_NAME"
  fi
fi
if [[ -z "$VERSION_CODE" ]]; then
  VERSION_CODE="$computed_version_code"
  if [[ "$local_version_code" =~ ^[0-9]+$ ]] && [[ "$VERSION_CODE" =~ ^[0-9]+$ ]] && [[ "$VERSION_CODE" -le "$local_version_code" ]]; then
    VERSION_CODE=$((local_version_code + 1))
    glagolitsa_warn "server latest_build отстаёт от gradle.properties ($local_version_code); auto-bump versionCode=$VERSION_CODE"
  fi
fi

if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || [[ "$VERSION_CODE" -le 0 ]]; then
  glagolitsa_err "versionCode должен быть положительным числом: $VERSION_CODE"
  exit 1
fi
if ! [[ "$VERSION_NAME" =~ ^[0-9A-Za-z._-]+$ ]]; then
  glagolitsa_err "versionName должен содержать только 0-9, A-Z, a-z, '.', '_' или '-': $VERSION_NAME"
  exit 1
fi

apk_name="glagolitsa-${VERSION_NAME}-${VERSION_CODE}.apk"
download_url="${PUBLIC_SCHEME}://${PUBLIC_DOMAIN}/downloads/${apk_name}"

glagolitsa_log "Android update: version=$VERSION_NAME build=$VERSION_CODE"
glagolitsa_log "API для APK: $API_BASE_URL"
glagolitsa_log "Download URL: $download_url"

if [[ "$DRY_RUN" == true ]]; then
  glagolitsa_log "Dry run: сборка, загрузка APK и изменение VPS пропущены"
  exit 0
fi

if [[ "$BUILD_APK" == true ]]; then
  glagolitsa_log "Сборка APK с versionName=$VERSION_NAME versionCode=$VERSION_CODE"
  (
    cd "$ROOT_DIR"
    ./gradlew --no-build-cache --rerun-tasks :androidApp:assembleDebug \
      "-PapiBaseUrl=$API_BASE_URL" \
      "-PdevShortcutsEnabled=false" \
      "-PdevPrimaryUsername=" \
      "-PdevPrimaryPassword=" \
      "-PdevPrimaryLabel=" \
      "-PdevSecondaryUsername=" \
      "-PdevSecondaryPassword=" \
      "-PdevSecondaryLabel=" \
      "-PappVersionName=$VERSION_NAME" \
      "-PappVersionCode=$VERSION_CODE"
  )
fi

if [[ ! -f "$APK_PATH" ]]; then
  glagolitsa_err "APK не найден: $APK_PATH"
  exit 1
fi
ls -lh "$APK_PATH"

prepare_remote_downloads
cleanup_remote_apks
publish_apk
cleanup_remote_apks
update_policy_env
restart_and_verify

glagolitsa_log "Готово. На старом телефоне откройте: Настройки → Обновления → Проверить обновления"
