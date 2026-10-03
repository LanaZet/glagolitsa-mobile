#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# API integration smoke (no UI / no Maestro): client update policy + hard floor.
#
#   ./scripts/verify-client-update-smoke.sh
#   BASE_URL=http://127.0.0.1:8080 ./scripts/verify-client-update-smoke.sh
#
# Checks:
#   1) GET /api/client/update-policy returns installable URLs
#   2) Authenticated call with old X-App-Version is rejected (426)
#   3) Authenticated call with current/new version succeeds
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

BASE_URL="${BASE_URL:-$E2E_LOCAL_BASE_URL}"
USERNAME="${USERNAME:-$E2E_LOCAL_SENDER_USERNAME}"
PASSWORD="${PASSWORD:-$E2E_LOCAL_SENDER_PASSWORD}"

log() { printf '[client-update] %s\n' "$*"; }
fail() { printf '[client-update] FAIL: %s\n' "$*" >&2; exit 1; }

export BASE_URL USERNAME PASSWORD
log "BASE_URL=$BASE_URL user=@$USERNAME"

python3 <<'PY'
import json, os, sys, urllib.error, urllib.request

BASE = os.environ["BASE_URL"].rstrip("/")
USER = os.environ["USERNAME"]
PASSWORD = os.environ["PASSWORD"]


def req(method, path, token=None, body=None, headers=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    if headers:
        h.update(headers)
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(f"{BASE}{path}", data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"error": raw or e.reason}
        return e.code, payload
    except urllib.error.URLError as e:
        print(f"FAIL network {method} {path}: {e}", file=sys.stderr)
        sys.exit(1)


def main():
    code, policy = req("GET", "/api/client/update-policy")
    if code == 404:
        print(
            "FAIL GET /api/client/update-policy → 404. Rebuild/restart glagolitsa/server.",
            file=sys.stderr,
        )
        sys.exit(1)
    if code != 200 or not isinstance(policy, dict):
        print(f"FAIL update-policy: http={code} body={policy}", file=sys.stderr)
        sys.exit(1)

    android = policy.get("android") or {}
    store = (android.get("store_url") or "").strip()
    download = (android.get("download_url") or "").strip()
    if not store and not download:
        print(
            "FAIL policy has neither store_url nor download_url — user cannot install an update",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"PASS policy android min={android.get('min_version')} latest={android.get('latest_version')}")
    print(f"PASS installable url present store={bool(store)} download={bool(download)}")

    features = policy.get("features") or {}
    if not isinstance(features, dict):
        print(f"FAIL features must be object: {features}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS features keys={list(features.keys())}")

    code, login = req("POST", "/api/auth/login", body={"username": USER, "password": PASSWORD})
    if code != 200 or not login.get("token"):
        print(f"FAIL login: http={code} body={login}", file=sys.stderr)
        sys.exit(1)
    token = login["token"]
    print("PASS login")

    # Old client must be blocked when headers present.
    code, body = req(
        "GET",
        "/api/chats",
        token=token,
        headers={
            "X-App-Version": "0.0.1",
            "X-App-Build": "1",
            "X-App-Platform": "android",
        },
    )
    # Default server min is 0.1.0 → 0.0.1 is too old → 426
    if code not in (426, 403):
        # If env set min lower than 0.0.1, still OK if 200 — but document
        min_v = (android.get("min_version") or "").strip()
        if code == 200 and min_v in ("", "0.0.0", "0.0.1"):
            print("PASS old-client check skipped (server min allows 0.0.1)")
        else:
            print(
                f"FAIL expected 426 for old client, got http={code} body={body}",
                file=sys.stderr,
            )
            sys.exit(1)
    else:
        err = (body.get("code") or body.get("error") or "")
        if "client_update" not in str(err):
            print(f"FAIL expected client_update_required, body={body}", file=sys.stderr)
            sys.exit(1)
        print(f"PASS old client blocked http={code} code={err}")

    # Current/new client allowed.
    latest = (android.get("latest_version") or "0.1.0").strip() or "0.1.0"
    latest_build = int(android.get("latest_build") or 1)
    code, chats = req(
        "GET",
        "/api/chats",
        token=token,
        headers={
            "X-App-Version": latest,
            "X-App-Build": str(max(latest_build, 1)),
            "X-App-Platform": "android",
        },
    )
    if code != 200:
        print(f"FAIL current client chats: http={code} body={chats}", file=sys.stderr)
        sys.exit(1)
    print("PASS current client accepted")
    print("PASS client update smoke (API integration, installable policy)")


if __name__ == "__main__":
    main()
PY

log "OK"
