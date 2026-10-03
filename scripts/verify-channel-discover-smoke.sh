#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# API integration smoke (no UI / no Maestro): public channel discover.
#
# Catches the class of bug pure unit tests miss:
#   - server missing GET /api/channels/search (404 on old binary)
#   - private group "testing" is NOT globally searchable
#   - public channel with slug IS findable by a non-member
#
#   ./scripts/verify-channel-discover-smoke.sh
#   BASE_URL=http://127.0.0.1:8080 ./scripts/verify-channel-discover-smoke.sh
#
# Defaults: local seed users Polo (creator) + Marco (stranger searcher).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

BASE_URL="${BASE_URL:-$E2E_LOCAL_BASE_URL}"
CREATOR_USERNAME="${CREATOR_USERNAME:-$E2E_LOCAL_RECIPIENT_USERNAME}"
CREATOR_PASSWORD="${CREATOR_PASSWORD:-$E2E_LOCAL_RECIPIENT_PASSWORD}"
SEARCHER_USERNAME="${SEARCHER_USERNAME:-$E2E_LOCAL_SENDER_USERNAME}"
SEARCHER_PASSWORD="${SEARCHER_PASSWORD:-$E2E_LOCAL_SENDER_PASSWORD}"

log() { printf '[channel-discover] %s\n' "$*"; }
fail() { printf '[channel-discover] FAIL: %s\n' "$*" >&2; exit 1; }

export BASE_URL CREATOR_USERNAME CREATOR_PASSWORD SEARCHER_USERNAME SEARCHER_PASSWORD

log "BASE_URL=$BASE_URL creator=@$CREATOR_USERNAME searcher=@$SEARCHER_USERNAME"

python3 <<'PY'
import json, os, sys, time, urllib.error, urllib.request

BASE = os.environ["BASE_URL"].rstrip("/")
CREATOR = (os.environ["CREATOR_USERNAME"], os.environ["CREATOR_PASSWORD"])
SEARCHER = (os.environ["SEARCHER_USERNAME"], os.environ["SEARCHER_PASSWORD"])


def req(method, path, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
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


def login(user, password, label):
    code, data = req("POST", "/api/auth/login", body={"username": user, "password": password})
    if code != 200 or not data.get("token"):
        print(f"FAIL login @{user} ({label}): http={code} body={data}", file=sys.stderr)
        sys.exit(1)
    uid = (data.get("user") or {}).get("id")
    print(f"PASS login @{user} ({label}) uid={uid}")
    return data["token"], uid


def main():
    stamp = str(int(time.time()))
    pub_title = f"smoke-public-{stamp}"
    pub_slug = f"smoke_pub_{stamp}"
    priv_title = f"smoke-private-group-{stamp}"

    creator_token, _ = login(*CREATOR, "creator")
    searcher_token, _ = login(*SEARCHER, "searcher")

    # --- capability probe: old servers return 404 here ---
    code, data = req("GET", "/api/channels/search?q=smoke", token=searcher_token)
    if code == 404:
        print(
            "FAIL GET /api/channels/search → 404. "
            "Running server binary is too old or channels routes not registered. "
            "Rebuild/restart glagolitsa/server with channel discover endpoints.",
            file=sys.stderr,
        )
        sys.exit(1)
    if code != 200:
        print(f"FAIL search probe: http={code} body={data}", file=sys.stderr)
        sys.exit(1)
    if not isinstance(data, list):
        print(f"FAIL search probe: expected JSON array, got {type(data).__name__}: {data}", file=sys.stderr)
        sys.exit(1)
    print("PASS search endpoint present")

    # --- creator: private group (must NOT be globally discoverable) ---
    code, group = req(
        "POST",
        "/api/chats",
        token=creator_token,
        body={"title": priv_title, "member_ids": []},
    )
    if code not in (200, 201) or not group.get("id"):
        print(f"FAIL create private group: http={code} body={group}", file=sys.stderr)
        sys.exit(1)
    group_id = group["id"]
    group_type = group.get("type") or group.get("chat_type") or ""
    print(f"PASS create private group id={group_id[:8]}… type={group_type!r} title={priv_title}")

    # --- creator: public channel (must be discoverable) ---
    code, channel = req(
        "POST",
        "/api/channels",
        token=creator_token,
        body={
            "title": pub_title,
            "description": "channel discover smoke",
            "visibility": "public",
            "slug": pub_slug,
        },
    )
    if code == 404:
        print(
            "FAIL POST /api/channels → 404. Server missing channel create route.",
            file=sys.stderr,
        )
        sys.exit(1)
    if code not in (200, 201) or not channel.get("id"):
        print(f"FAIL create public channel: http={code} body={channel}", file=sys.stderr)
        sys.exit(1)
    ch_id = channel["id"]
    ch_vis = channel.get("visibility")
    ch_slug = channel.get("slug")
    ch_type = channel.get("type") or "channel"
    if ch_vis != "public" or not ch_slug:
        print(
            f"FAIL create public channel: need visibility=public and slug, got vis={ch_vis!r} slug={ch_slug!r}",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"PASS create public channel id={ch_id[:8]}… type={ch_type} slug={ch_slug}")

    # --- searcher: find by slug ---
    code, hits = req("GET", f"/api/channels/search?q={pub_slug}", token=searcher_token)
    if code != 200 or not isinstance(hits, list):
        print(f"FAIL search by slug: http={code} body={hits}", file=sys.stderr)
        sys.exit(1)
    by_id = {h.get("id"): h for h in hits if isinstance(h, dict)}
    if ch_id not in by_id:
        print(
            f"FAIL searcher did not find public channel by slug={pub_slug!r}; hits={hits}",
            file=sys.stderr,
        )
        sys.exit(1)
    hit = by_id[ch_id]
    if hit.get("visibility") != "public" or not hit.get("slug"):
        print(f"FAIL discover hit missing public fields: {hit}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS searcher finds public channel by slug ({len(hits)} hit(s))")

    # --- searcher: find by title fragment ---
    frag = "smoke-public"
    code, hits = req("GET", f"/api/channels/search?q={frag}", token=searcher_token)
    if code != 200 or not isinstance(hits, list):
        print(f"FAIL search by title: http={code} body={hits}", file=sys.stderr)
        sys.exit(1)
    if ch_id not in {h.get("id") for h in hits if isinstance(h, dict)}:
        print(
            f"FAIL searcher did not find public channel by title frag={frag!r}; hits={hits}",
            file=sys.stderr,
        )
        sys.exit(1)
    print("PASS searcher finds public channel by title")

    # --- searcher: private group title must NOT appear in channel discover ---
    code, hits = req("GET", f"/api/channels/search?q={priv_title}", token=searcher_token)
    if code != 200 or not isinstance(hits, list):
        print(f"FAIL search private group title: http={code} body={hits}", file=sys.stderr)
        sys.exit(1)
    leaked = [h for h in hits if isinstance(h, dict) and h.get("id") == group_id]
    if leaked:
        print(
            f"FAIL private group leaked into public channel search: {leaked}",
            file=sys.stderr,
        )
        sys.exit(1)
    # Also ensure no accidental hit just because title contains "smoke"
    leaked_title = [
        h for h in hits
        if isinstance(h, dict) and (h.get("title") or "") == priv_title
    ]
    if leaked_title:
        print(f"FAIL private group title in discover: {leaked_title}", file=sys.stderr)
        sys.exit(1)
    print("PASS private group not in public channel discover")

    # --- searcher: join public channel ---
    code, joined = req(
        "POST",
        f"/api/channels/slug/{pub_slug}/join",
        token=searcher_token,
    )
    if code not in (200, 201) or joined.get("id") != ch_id:
        print(f"FAIL join public channel: http={code} body={joined}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS searcher joined public channel via slug={pub_slug}")

    print("PASS channel discover smoke (API integration, no UI)")


if __name__ == "__main__":
    main()
PY

log "OK"
