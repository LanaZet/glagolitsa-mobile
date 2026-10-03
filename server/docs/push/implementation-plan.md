# Push Notifications — Implementation Plan

**Parent design:** [architecture.md](./architecture.md)  
**Order:** privacy-safe Android reliability first → multi-device correctness → APNs → UnifiedPush → scale polish  
**Rule:** no phase merges without tests listed in its acceptance criteria

---

## Phase map

```
P0  Foundation lock-in (docs already; code hygiene)
 │
P1  Local UI after wake + FCM priority discipline     ← Signal UX + Firebase 2025
 │
P2  Device-scoped online skip + token↔device lifecycle ← WA multi-device
 │
P3  Payload minimize + collapse                        ← Signal empty poke
 │
P4  APNs adapter                                       ← iOS production
 │
P5  UnifiedPush / simple-push                          ← Matrix no-GMS path
 │
P6  Calls polish + metrics + retention                 ← ops maturity
```

Each phase is one logical PR series (can be 1–3 PRs). Prefer small PRs per phase.

---

## P0 — Foundation lock-in

**Goal:** freeze privacy invariants and document “what we already have”.

### Work

- [x] Architecture doc (`architecture.md`)
- [x] This plan
- [x] README pointer
- [x] Privacy allowlist + denylist tests (arXiv 2407.10589 classes)
- [x] Priority policy tests (Firebase 2025)

### Acceptance

- Docs linked from repo entrypoint
- Privacy unit tests green and exhaustive for `forbiddenPayloadKeys`

### Out of scope

- Feature code

---

## P1 — Local notification after wake + priority discipline

**Goal:** Signal-style user experience; stop silent-high abuse.

### Problem today

- `PushWakeCoordinator` only drains queue; **no `NotificationManager`**.
- FCM adapter forces `HIGH` for silent message types → deprioritization risk.
- User offline never sees a banner until manual open.

### Server

| Change | Detail |
|--------|--------|
| Priority for `new_message` | Use `PriorityHigh` **only** when product commits to client local UI (P1 client ships same PR stack) |
| `prekeys.low` | Force **normal** priority; never high |
| Adapter | Do not blindly force HIGH for all `silent`; map from `req.Priority` only |
| Optional flag | `silent=true` means “data message, no FCM notification block”, not “priority high” |

### Client (Android first)

| Component | Responsibility |
|-----------|----------------|
| `LocalNotificationPresenter` (expect/actual or androidMain) | Show system notification after drain finds new inbound |
| Notification channel | `messages` (default), `calls` (high importance) |
| Content | Default: «Глаголица» / «Новое сообщение»; enrich **only** from local decrypt + prefs |
| Dedup | Tag/id by chatId or envelope id; update not spam |
| Permissions | POST_NOTIFICATIONS (API 33+); graceful if denied (still drain) |
| `PushWakeCoordinator` | After sync, invoke presenter when new messages landed and app not in that chat foreground |

### Wiring

```
FCM onMessageReceived
  → PushWakeCoordinator.onPushReceived
  → repository.drainPendingSync()
  → if newInbound && !chatOpen → LocalNotificationPresenter.show(...)
```

### Tests

- Unit: presenter text respects prefs defaults (no preview)
- Unit: FCM adapter priority mapping table
- Unit: coordinator still routes prekeys without notification
- Manual: force-stop app → send DM → banner → tap opens chat

### Acceptance

- [x] Offline Android shows a local notification for a new DM (code path)
- [x] FCM network payload still has no body/name/ciphertext
- [x] `prekeys.low` is normal priority
- [x] High priority used only for paths that show UI or calls

### Shipped in

- Commit series on `feature/push-privacy-wake` (P1)

---

## P2 — Device-scoped online skip + token lifecycle

**Goal:** WA multi-device correctness; no zombie push.

### Server

1. **WS hub**  
   - Track connections as `(user_id, device_id)` (device id from auth claims / connect handshake).  
   - API: `IsDeviceConnected(userID, deviceID) bool`, `ConnectedDeviceIDs(userID) []string`.

2. **Relay notify path**  
   - Today: one `NotifyNewEnvelope` per recipient **account** if no WS.  
   - Target: for each recipient **device** that received a mailbox row:
     - if device connected → skip push;
     - else → push tokens for that `device_id` only.

3. **Notify API reshape**

```go
// conceptual
NotifyNewEnvelope(recipientUserID string, devices []OfflineDeviceWake)
// OfflineDeviceWake { DeviceID, MailboxToken? }
```

   Or: `Send` filters tokens by `ExcludeDeviceIDs` / `OnlyDeviceIDs`.

4. **Lifecycle hooks**  
   - On `RevokeDevice` / stale purge: `RevokePushTokensForDevice(userID, deviceID)`.  
   - On logout: revoke current device token.  
   - On permanent FCM error: mark `invalid` (already partial via `MarkTokenFailure`).

### Client

- WS connect includes stable `device_id` if not already.
- Register push only for current device; re-register after re-enroll.

### Tests

- Two devices A online / B offline → push only B’s token
- Both online → zero push
- Device revoke → token status revoked, not listed as active
- Integration with existing multidevice relay tests

### Acceptance

- [ ] Desktop online no longer suppresses phone push
- [ ] Revoked device cannot receive push
- [ ] Existing multidevice mailbox tests still pass

### PR split

1. WS device tracking
2. Notify filter by device + tests
3. Revoke/purge token cleanup

---

## P3 — Minimal poke payload + collapse

**Goal:** pure Signal poke + less push spam.

### Work

- Reduce `NotifyNewEnvelope` payload to `{type, schema}` (drop envelope_id if client unused).
- Confirm client never depends on push envelope id (always full drain).
- FCM `collapse_key` / Android collapse = `glag_msg_wake` for message types.
- Optional server-side debounce: if push sent for `(user, device)` in last N seconds for `new_message`, skip (mailbox still holds data).

### Tests

- Privacy: payload keys allowlist test (not only denylist)
- Collapse key set on FCM HTTP body
- Debounce unit test with fake clock

### Acceptance

- [ ] Captured FCM data ≤ minimal schema
- [ ] Burst of 10 envelopes → ≤ 1–2 pushes per device in window

---

## P4 — APNs adapter

**Goal:** iOS production path (when iosApp graduates beyond stub).

### Work

- `APNsAdapter` with token auth (.p8), sandbox/production switch
- Message types:
  - `new_message`: `content-available: 1` + optional alert from server **only** generic strings (or pure background + local notify like Android)
  - Prefer: background wake + local notification after fetch (mirrors Android privacy)
  - `incoming_call`: VoIP push path (separate cert/topic) — can be P4b
- Register `platform=ios` tokens from client
- Invalid token handling (`410` / BadDeviceToken)

### Tests

- Adapter unit with httptest- PrivacyGuard still applied before APNs

### Acceptance

- [ ] iOS cold app receives wake and shows local generic notification
- [ ] No plaintext in APNs payload

---

## P5 — UnifiedPush (Matrix / Telegram simple-push)

**Goal:** no-GMS and self-host path.

### Work

- New platform or token type: `unifiedpush` / `simple_push`
- Client (Android FOSS flavor optional): obtain endpoint from distributor (ntfy, etc.), register with server
- Server adapter: `HTTP PUT` to endpoint with minimal body (`version=1` or empty JSON type wake)
- Document self-host: ntfy + Glagolitsa; no Matrix gateway required (we are not a homeserver) — **direct simple-push**, Telegram type 4 style

### Flow

```
App ←→ UP Distributor (on device)
Server → PUT https://ntfy/.../topic  (wake)
Distributor → app Broadcast → PushWakeCoordinator
```

### Tests

- Adapter PUT called with opaque body
- Invalid endpoint → token invalid after N failures

### Acceptance

- [ ] Device without GMS receives wake via ntfy self-host in dev doc
- [ ] Same PrivacyGuard path

---

## P6 — Calls polish, metrics, retention

### Work

- Incoming call: high-priority + full-screen intent / CallStyle (Android 12+)
- Missed call local notification
- Metrics: `push_sent`, `push_failed`, `push_skipped_online`, `push_token_invalidated`
- Retention job: trim `notification_delivery_log` older than N days
- Retry: distinguish permanent vs transient errors (no infinite retry on invalid token)

### Acceptance

- [ ] Dashboards or log queries documentable in ops runbook
- [ ] Log table growth bounded

---

## Dependency graph

```
P0 ──────────────────────────────┐
P1 (local UI + priority) ─────────┼──► production Android reliable
P2 (device skip + tokens) ────────┤
P3 (minimal + collapse) ──────────┘   can follow P1 closely
P4 (APNs) ── needs ios client readiness; after P1 patterns
P5 (UnifiedPush) ── after P1; independent of P4
P6 ── anytime after P1–P2
```

**Ship order for public VPS Android users:** **P1 → P2 → P3**, then P4/P5 as platform needs appear.

---

## Concrete file touch list (expected)

### P1

| Area | Files (indicative) |
|------|--------------------|
| Server | `notification/fcm.go`, `notification/service.go`, `notification/*_test.go` |
| Client | `shared/.../push/LocalNotificationPresenter*.kt`, `PushWakeCoordinator.kt`, `androidMain` notification channel, `AndroidManifest` POST_NOTIFICATIONS |
| App | `GlagolitsaFirebaseMessagingService.kt` (foreground service? only if needed for long drain) |

### P2

| Area | Files |
|------|-------|
| Server | `messaging/ws/hub.go`, `messaging/relay.go`, `notification/service.go`, `identity`/`devices` revoke hooks, store notification methods |
| Tests | `message_relay_multidevice_test.go`, new push filter tests |

### P3

| Area | Files |
|------|-------|
| Server | `NotifyNewEnvelope`, FCM message builder |
| Client | assert drain ignores missing envelope_id |

### P4–P5

| Area | Files |
|------|-------|
| Server | `notification/apns.go`, `notification/unifiedpush.go`, `adapter.go` |
| Client | iOS push registration; Android UP optional source set |

---

## Explicit non-goals per early phases

| Do not do in P1–P3 | Why |
|--------------------|-----|
| Put sender name in FCM even if prefs allow | Prefs apply **after** local decrypt only |
| Treat push ack as delivery ✓ | ✓ = relay accept; separate workstream |
| Rewrite mailbox / sealed sender | Orthogonal |
| Depend on Google notification payload (`notification:{}` block with body) | Privacy + E2EE product stance |

---

## Rollout checklist (each phase)

1. Feature flag or env default-on only when safe (P5 off by default).
2. Unit + integration green in CI.
3. Manual offline phone test against `api.glagolit.me` or staging.
4. Verify FCM payload with log-only adapter or Firebase console diagnostics.
5. Deploy server before client if server is backward-compatible; client-first only when server already accepts new tokens/fields.
6. No drive-by refactors outside phase file list.

---

## Open decisions (resolve before/during phase)

| # | Decision | Default proposal |
|---|----------|------------------|
| D1 | Keep `mailbox_token` in poke or pure empty? | P3 drops it unless client uses filter |
| D2 | Show notification if user disabled system permission? | Drain only; no crash |
| D3 | Foreground chat open: show banner? | No |
| D4 | Groups/channels same as DM for P1? | Yes, same generic body |
| D5 | Web desktop push | Defer (Web Push P4/P5+) |
| D6 | Debounce window | 10s starting point |

---

## Suggested first implementation slice

**Next coding session:** **P1 only**

1. Fix server priority (prekeys normal; silent ≠ high).
2. Android local notification after `drainPendingSync`.
3. Tests + manual USB phone validation against public API.

P2 immediately after if multi-device phone+desktop testing fails “phone silent while desktop open”.

---

## Document history

| Date | Note |
|------|------|
| 2026-07-23 | Initial phased plan from architecture audit |
