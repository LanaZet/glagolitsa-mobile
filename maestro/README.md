# Maestro smoke policy

Maestro is kept for product paths that only a real app session can cover:

- registration and login/device-gate UI
- opening chats, entering text, and seeing optimistic UI updates
- cold start / relaunch session restore (stay logged in, not kicked to password)
- a minimal dual-device release/nightly smoke for delivery/read regressions

Maestro is **not** the primary proof of message delivery correctness. Delivery,
relay queue processing, encryption, local persistence, and delivered/read status
transitions are covered by repository tests + server Go tests + live **API**
scripts (no UI).

Do **not** add a second public-API unit suite that mirrors those tests. Live API
coverage reuses the scripts below only.

## What to run (one table — no duplicate suites)

| Goal | Command | Hits public API? | Maestro? |
|------|---------|------------------|----------|
| **Messaging proof (default / PR)** | `./scripts/test-messaging.sh` or `./scripts/pr-check.sh` | No | No |
| Client unit only | `./scripts/test-unit.sh` | No | No |
| Server Go tests only | `./scripts/test-server.sh` | No | No |
| Live DM path API (public) | `./scripts/test-messaging.sh --live-public` | Yes | **No** |
| Live DM path API (local docker) | `./scripts/test-messaging.sh --live-local` | No (127.0.0.1) | **No** |
| Full stack without UI | `./scripts/test-messaging.sh --all` | Yes (+ local) | **No** |
| UI smoke: auth / open chat | `./scripts/run-maestro-e2e.sh` | Per build API | Yes |
| UI session stay logged in | `maestro test maestro/flows/session-restore-stay-logged-in.yaml -e USERNAME=... -e PASSWORD=...` | Per build API | Yes |
| UI DM dual-device (release/nightly) | `REQUIRE_VPS=1 BASE_URL=https://api.glagolit.me ./scripts/run-maestro-message-dual-e2e.sh` | Yes | Yes |
| Release/nightly UI bundle | `./scripts/run-e2e-smoke.sh delivery` | Per machine | Yes |

Primary live messaging check is **API-only** (`verify-public-messaging-path.sh` via `test-messaging.sh`), not Maestro.

Defaults for users/passwords and public URL: `scripts/e2e-defaults.sh`.

Server repo path: `SERVER_DIR` (default `../glagolitsa/server`).

## Notes

- APK must target the same API as `BASE_URL` only when you run **UI** Maestro.
- Public API path needs **active** devices on both accounts; pending/revoked-only accounts fail even if unit tests are green.
- `REQUIRE_VPS=1` applies to **dual UI** e2e only.
- Skip server in a checkout without Go/sibling: `SKIP_SERVER_TESTS=1 ./scripts/pr-check.sh`.

## Quick messaging (no UI)

```bash
./scripts/test-messaging.sh              # unit + server
./scripts/test-messaging.sh --live-local
./scripts/test-messaging.sh --live-public
```

## Quick local UI smoke (optional)

```bash
./scripts/run-maestro-e2e.sh
```

## Release/nightly delivery smoke (optional UI)

```bash
./scripts/run-e2e-smoke.sh delivery
```
