#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Live API messaging path (no UI / no Maestro): login pair → active devices → queue → relay → queue found → ack.
#
# Prefer the umbrella entrypoint:
#   ./scripts/test-messaging.sh --live-public
#   ./scripts/test-messaging.sh --live-local
#
# Direct:
#   ./scripts/verify-public-messaging-path.sh
#   BASE_URL=https://api.example.com SENDER_USERNAME=marco RECIPIENT_USERNAME=polo ./scripts/verify-public-messaging-path.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"
SENDER_USERNAME="${SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"

e2e_disable_proxy_for_local_api "$BASE_URL"
e2e_require_vars BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

log() { printf '[public-msg] %s\n' "$*"; }
fail() { printf '[public-msg] FAIL: %s\n' "$*" >&2; exit 1; }

export BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

python3 <<'PY'
import base64, json, os, sys, urllib.error, urllib.request

BASE = os.environ["BASE_URL"].rstrip("/")
SENDER = (os.environ["SENDER_USERNAME"], os.environ["SENDER_PASSWORD"])
RECIPIENT = (os.environ["RECIPIENT_USERNAME"], os.environ["RECIPIENT_PASSWORD"])
IS_LOCAL = any(host in BASE for host in ("localhost", "127.0.0.1", "10.0.2.2"))

def req(method, path, token=None, body=None, headers=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    if headers:
        h.update(headers)
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(f"{BASE}{path}", data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"error": raw}
        return e.code, payload

def login(user, password):
    code, data = req("POST", "/api/auth/login", body={"username": user, "password": password})
    if code != 200 or not data.get("token"):
        print(f"FAIL login @{user}: http={code} body={data}", file=sys.stderr)
        sys.exit(1)
    uid = (data.get("user") or {}).get("id")
    print(f"PASS login @{user} uid={uid}")
    return data["token"], uid

def devices(token, uid, who):
    code, data = req("GET", f"/api/users/{uid}/devices", token=token)
    if code != 200 or not isinstance(data, list):
        print(f"FAIL devices @{who}: http={code} body={data}", file=sys.stderr)
        sys.exit(1)
    active = [d for d in data if (d.get("device_status") or "active") == "active"]
    print(f"PASS devices @{who}: total={len(data)} active={len(active)}")
    if not active:
        print(f"FAIL devices @{who}: need >=1 active", file=sys.stderr)
        sys.exit(1)
    # Server returns devices ordered by created_at ASC today, so the last active
    # row is the closest match for a freshly installed local client.
    d = active[-1]
    if not d.get("mailbox_token") or not d.get("device_id"):
        print(f"FAIL devices @{who}: active missing mailbox/device", file=sys.stderr)
        sys.exit(1)
    print(f"PASS device @{who}: {d.get('device_id')}")
    return d

def ack_queue(token, device_id, envelope_ids):
    if not envelope_ids:
        return 0
    code, ack = req(
        "POST",
        "/api/messages/queue/ack",
        token=token,
        headers={"X-Device-Id": device_id},
        body={"envelope_ids": envelope_ids},
    )
    if code != 200:
        print(f"FAIL queue drain ack: http={code} body={ack}", file=sys.stderr)
        sys.exit(1)
    return ack.get("deleted") or 0

def drain_local_queue(token, device_id):
    if not IS_LOCAL:
        return
    total = 0
    for _ in range(20):
        code, q = req(
            "GET",
            "/api/messages/queue?limit=100",
            token=token,
            headers={"X-Device-Id": device_id},
        )
        if code != 200:
            print(f"FAIL queue drain poll: http={code} body={q}", file=sys.stderr)
            sys.exit(1)
        ids = [e.get("envelope_id") for e in (q.get("envelopes") or []) if e.get("envelope_id")]
        if not ids:
            break
        deleted = ack_queue(token, device_id, ids)
        total += deleted
        if deleted <= 0:
            break
    if total:
        print(f"PASS local queue drain deleted={total}")

def main():
    # health
    code, data = req("GET", "/api/health")
    if code != 200 or data.get("status") != "ok":
        print(f"FAIL health: http={code} body={data}", file=sys.stderr)
        sys.exit(1)
    print("PASS health")

    s_token, s_uid = login(*SENDER)
    r_token, r_uid = login(*RECIPIENT)

    # session reuse
    code, _ = req("GET", "/api/chats", token=s_token)
    if code != 200:
        print(f"FAIL session sender chats: http={code}", file=sys.stderr)
        sys.exit(1)
    print("PASS session sender chats")

    s_dev = devices(s_token, s_uid, SENDER[0])
    r_dev = devices(r_token, r_uid, RECIPIENT[0])
    drain_local_queue(r_token, r_dev["device_id"])

    # queue as app (recipient) empty-or-list
    code, q = req(
        "GET",
        "/api/messages/queue?limit=20",
        token=r_token,
        headers={"X-Device-Id": r_dev["device_id"]},
    )
    if code != 200:
        print(f"FAIL queue poll: http={code} body={q}", file=sys.stderr)
        sys.exit(1)
    before = {e.get("envelope_id") for e in (q.get("envelopes") or [])}
    print(f"PASS queue poll as app envelopes={len(before)}")

    # relay sender → recipient mailbox
    probe = base64.b64encode(b"public-msg-path-probe").decode()
    code, relay = req(
        "POST",
        "/api/messages/relay",
        token=s_token,
        headers={"X-Device-Id": s_dev["device_id"]},
        body={
            "envelopes": [{
                "mailbox_token": r_dev["mailbox_token"],
                "ciphertext": probe,
                "envelope_type": 1,
            }],
        },
    )
    if code not in (200, 201) or not (relay.get("enqueued") or relay.get("envelope_ids")):
        print(f"FAIL relay: http={code} body={relay}", file=sys.stderr)
        sys.exit(1)
    env_ids = relay.get("envelope_ids") or []
    print(f"PASS relay enqueued={relay.get('enqueued')} ids={env_ids}")

    # recipient sees envelope
    code, q2 = req(
        "GET",
        "/api/messages/queue?limit=20",
        token=r_token,
        headers={"X-Device-Id": r_dev["device_id"]},
    )
    if code != 200:
        print(f"FAIL queue after relay: http={code} body={q2}", file=sys.stderr)
        sys.exit(1)
    found = None
    for e in q2.get("envelopes") or []:
        if e.get("envelope_id") in env_ids or e.get("ciphertext") == probe:
            found = e
            break
    if not found:
        # also accept any new envelope with matching ciphertext
        for e in q2.get("envelopes") or []:
            if e.get("ciphertext") == probe:
                found = e
                break
    if not found:
        print(f"FAIL recipient queue missing probe envelope: {q2}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS recipient sees envelope id={found.get('envelope_id')}")

    eid = found["envelope_id"]
    deleted = ack_queue(r_token, r_dev["device_id"], [eid])
    if deleted < 1:
        print(f"FAIL ack deleted={deleted}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS ack deleted={deleted}")

    print("PASS public messaging path "
          f"({SENDER[0]} → {RECIPIENT[0]} @ {BASE})")

if __name__ == "__main__":
    main()
PY
