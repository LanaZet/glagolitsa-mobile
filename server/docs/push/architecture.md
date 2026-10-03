# Push Notifications Architecture

**Status:** design draft (research complete, implementation phased)  
**References:** Signal (wake-only FCM), Matrix (Push Gateway + UnifiedPush), WhatsApp-class store-and-forward  
**Privacy baseline:** push transport never sees plaintext message content or display names  
**Related:** `server/docs/security/auth-refresh-signal.md` (device vs session boundaries)

---

## 1. Goals

1. **Reliable delivery wake** — offline devices learn about new mailbox envelopes without holding a permanent TCP connection from the app process.
2. **Privacy by default** — Google FCM / Apple APNs / third-party push servers never receive message body, ciphertext, sender display name, or phone numbers.
3. **Correct multi-device fan-out** — push tokens are bound to stable `device_id`; zombie tokens die with zombie devices.
4. **Platform discipline** — respect FCM high-priority rules (user-visible outcome) and APNs VoIP / content-available constraints.
5. **Extensible transports** — primary FCM/APNs; optional UnifiedPush (Matrix-style) for no-GMS / self-host.

## 2. Non-goals (this design)

- Redesigning E2EE ratchet / sealed sender (orthogonal).
- Full Matrix homeserver federation or Element client stack.
- Guaranteeing peer-decrypt delivery receipts in push payloads (✓ semantics stay relay-level; see messaging delivery docs).
- Marketing / campaign push, topics, or in-app analytics via FCM.

---

## 3. Reference models (what we take)

### 3.1 Signal — primary privacy model

| Principle | Practice |
|-----------|----------|
| FCM is a **wake**, not a content pipe | Empty / opaque data message only |
| App builds UI after fetch + decrypt | Local `NotificationManager` |
| No secrets in OSPNS payload | Subpoena surface is token + timing at most |
| Fallback when FCM missing | WebSocket / foreground connectivity |

**Adopt:** wake-only data payloads; local notification after mailbox drain; never put ciphertext in FCM.

### 3.2 Matrix — gateway and format control

| Principle | Practice |
|-----------|----------|
| Homeserver does not call FCM directly | `POST /_matrix/push/v1/notify` → gateway → OSPNS |
| Privacy format | `event_id_only` (ids + counts, no body) |
| Token rejection loop | Gateway returns `rejected[]` → remove pusher |
| Optional de-Google | UnifiedPush + ntfy distributor |

**Adopt:** adapter interface as our “gateway”; reject/invalidate tokens; optional UnifiedPush simple-push endpoint; payload shape closer to event_id_only than full Matrix event content.

### 3.3 WhatsApp-class — scale and device hygiene

| Principle | Practice |
|-----------|----------|
| Store-and-forward mailbox | Ciphertext lives in per-device mailbox, not in push |
| Multi-device fan-out | One push path per registered device token |
| Token lifecycle | Register, refresh, invalidate on FCM/APNs error, revoke on logout/device revoke |
| Online skip | Do not push devices that already have live session |
| Collapse / coalesce under load | Avoid N high-priority wakes for N envelopes |

**Adopt:** mailbox remains source of truth; token↔device binding; online skip (already partial); collapse keys / quiet coalescing later.

---

## 4. Current state (Glagolitsa)

### 4.1 What exists

```
Relay accept
  → mailbox fan-out (per recipient device)
  → WS BroadcastEnvelope if recipient account online
  → else NotifyNewEnvelope (silent data push)
```

| Layer | Status |
|-------|--------|
| `notification.Service` | Router + preferences + PrivacyGuard + retry queue + delivery log |
| `FCMAdapter` | HTTP v1, **data-only** messages |
| `PrivacyGuard` | Blocks body/text/ciphertext/sender_name/… keys |
| Token API | `POST/DELETE /api/notification/tokens`, prefs GET/PATCH |
| Client | `PushTokenProvider` + `PushWakeCoordinator` → `drainPendingSync` |
| Online skip | Account-level: `!Hub.HasConnectedClients(userID)` |
| Device lifecycle | Stable `device_id`, stale purge, mailbox purge on revoke |

### 4.2 Gaps vs target

| Gap | Risk |
|-----|------|
| **No local UI notification** after wake | FCM may deprioritize high/silent patterns; user sees nothing until app open |
| Silent path forces **HIGH** in FCM adapter | Aggravates deprioritization if no visible notification |
| `NotifyNewEnvelope` payload includes `envelope_id` + `mailbox_token` | Fine if opaque; still more than pure Signal empty poke |
| Online skip is **account**, not **device** | Second phone offline never woken if first phone is online on WS |
| APNs / Web Push adapters are log stubs | No iOS production path |
| No UnifiedPush | no-GMS users left out |
| Push token not auto-revoked on device revoke | Zombie FCM tokens after device purge |
| No collapse / coalesce | Burst of envelopes → burst of pushes |
| Calls path | Types exist; local call UI from push not fully specified here |

---

## 5. Target architecture

### 5.1 End-to-end flow

```
                    ┌── recipient device online (WS for that device)?
                    │         yes → WS envelope only (no push for that device)
Message accepted ───┤
                    │         no  → enqueue opaque wake
                    │                PrivacyGuard(payload)
                    │                → Push Router (per active token for offline devices)
                    │                     ├─ FCM data-only
                    │                     ├─ APNs content-available / alert
                    │                     └─ UnifiedPush PUT (optional)
                    ▼
              Device wakes
                    → sync mailbox / drain queue
                    → decrypt
                    → LocalNotificationPresenter (prefs: preview? sender?)
                    → user-visible notification when appropriate
```

### 5.2 Payload contract (OSPN-safe)

Allowed keys in any push `data` map (extend only with opaque ids):

| Key | Required | Notes |
|-----|----------|--------|
| `type` | yes | `new_message`, `message_sync`, `incoming_call`, `missed_call`, `prekeys.low`, … |
| `schema` | recommended | integer version, start at `1` |
| `collapse_id` | optional | coalesce key for providers that support it |
| `device_hint` | optional | target device_id if single-device wake |

**Forbidden forever** (enforced by `PrivacyGuard`, tests must stay red if relaxed):

- message body, preview text, ciphertext
- sender display name, phone, email
- private keys / session keys / auth tokens

**Signal-aligned minimal poke (preferred long-term for messages):**

```json
{ "type": "new_message", "schema": "1" }
```

Client always drains full mailbox; does not need envelope id in the push.

**Matrix-aligned optional poke (debugging / multi-queue):**

```json
{ "type": "new_message", "schema": "1", "mailbox_token": "<opaque>" }
```

Never put plaintext. Prefer dropping `envelope_id` if unused by client.

### 5.3 Priority policy

| Type | Priority | Must produce user-visible UI? |
|------|----------|--------------------------------|
| `new_message` / `message_sync` | high **only if** client will show local notification | yes (or app already foreground) |
| `prekeys.low` | normal | no — never high |
| `incoming_call` | voip / high | yes (call UI / full-screen intent) |
| `missed_call` | high | yes |
| security / new device | high | yes |

**Rule (Firebase 2025):** do not send high-priority data messages that routinely result in no visible notification.

### 5.4 Online skip (device-scoped — target)

Today: skip push if **any** WS for the user is connected.

Target:

1. Track WS connections with `(user_id, device_id)`.
2. When fanning out envelopes, for each **recipient device**:
   - if that device has active WS → no push for its token;
   - else → push that device’s tokens.
3. Account-level “all devices online” short-circuit remains an optimization.

This matches multi-device WA/Signal behavior: phone offline still wakes while desktop is open.

### 5.5 Token ↔ device lifecycle

| Event | Push action |
|-------|-------------|
| Login / enroll | Register/refresh FCM|APNs|UP token for `(user, device, platform)` |
| FCM token refresh | Upsert same row |
| OSPNS permanent failure (NotRegistered, BadDeviceToken) | Mark token `invalid` |
| Device revoke / unlink | Revoke all tokens for that `device_id` + stop mailbox fan-out |
| Stale device purge job | Same as revoke for push tokens |
| Logout | Revoke token for current device |

Push tokens without a live device are zombies; treat them like zombie mailboxes.

### 5.6 Adapters (Matrix gateway idea, local process)

```
notification.Service
  ├── FCMAdapter        (Android GMS)
  ├── APNsAdapter       (iOS)
  ├── WebPushAdapter    (optional)
  └── UnifiedPushAdapter (simple PUT wake — Telegram type 4 / Matrix UP)
```

Each adapter:

- accepts opaque string map + priority + silent flag;
- maps to provider-specific envelope;
- returns typed errors: `ErrTokenInvalid` vs transient;
- never logs payload content beyond `type` and token id.

### 5.7 Client responsibilities

1. **Register token** after login and on `onNewToken`.
2. **On push:** `PushWakeCoordinator` → drain mailbox / prekeys / call path.
3. **LocalNotificationPresenter** (new): after successful sync/decrypt of new inbound messages while backgrounded, show system notification according to prefs:
   - default: title `Глаголица`, body `Новое сообщение` (already in `SanitizeUserVisibleText`);
   - if `show_sender_name` / `show_message_preview` and content available **only after local decrypt**, enrich locally — never from FCM.
4. **Dedup** notifications by message/envelope id when user opens chat.
5. **Foreground:** suppress duplicate banners if chat already open (policy TBD).

### 5.8 Collapse / coalesce (phase 2+)

- Server: at most one outstanding high-priority message wake per `(user, device)` within a short window (e.g. 5–15s), or use FCM `collapse_key` / APNs `apns-collapse-id` = `msgwake`.
- Muted chats: rare badge-only wakes (Telegram `MESSAGE_MUTED` pattern) — later.

---

## 6. Threat model (short)

| Adversary | Mitigation |
|-----------|------------|
| OSPNS operator / legal process on push records | Wake-only opaque payload; no content |
| Malicious app on same device | OS sandbox; not our PNS design |
| Compromised app server | Can still push wake spam; cannot invent ciphertext without keys |
| Stale tokens → wrong device | Token bind to device_id; revoke on device death |
| Battery / Doze abuse | Priority policy + visible UI + coalesce |

Push proves **“server wanted this device awake”**, not **“peer decrypted”**. Delivery ticks stay independent.

---

## 7. Config and ops

| Env / config | Purpose |
|--------------|---------|
| `FCM_PROJECT_ID` | Android adapter |
| `FCM_CREDENTIALS_JSON` / `FCM_CREDENTIALS_FILE` | Service account |
| `APNS_*` (future) | Key id, team id, bundle, .p8 |
| `PUSH_UNIFIED_ENABLED` (future) | Simple-push adapter |
| Delivery log retention | Cap table growth (job later) |

Health: count `delivery_failed`, invalid tokens, retry depth — expose via existing metrics if present.

---

## 8. Testing strategy

| Level | Cases |
|-------|--------|
| Unit | PrivacyGuard rejects forbidden keys; payload schema only allows opaque fields |
| Unit | Priority mapping: prekeys ≠ high; message high only with UI contract |
| Unit | Device-scoped online skip matrix |
| Integration | Relay + offline → push adapter called; online device → not called |
| Integration | Device revoke → token revoked |
| Client unit | PushWakeCoordinator routes types; LocalNotificationPresenter respects prefs |
| Manual / e2e | Kill app → send DM → system notification → open → message visible |

---

## 9. Success metrics

1. Offline Android receives message notification without opening app first.
2. FCM payload capture (mitm / log) contains no plaintext / names / ciphertext.
3. Desktop online + phone offline → phone still wakes (device-scoped skip).
4. Revoked device stops receiving pushes within one lifecycle event.
5. No sustained FCM deprioritization pattern in Firebase diagnostics (post-ship).

---

## 10. Implementation status (branch `feature/push-privacy-wake`)

| Phase | Status |
|-------|--------|
| P0 Docs + privacy allowlist | done |
| P1 Local UI + FCM priority | done |
| P2 Device-scoped skip + token revoke | done |
| P3 Minimal poke + collapse + debounce | done (in notification.Service / FCMAdapter) |
| P4 APNs adapter | done (env-gated) |
| P5 UnifiedPush | done (platform + migration 028) |
| P6 Metrics + log retention | done |

## 11. Document history

| Date | Note |
|------|------|
| 2026-07-23 | Initial architecture from Signal/Matrix/WA research + codebase audit |
| 2026-07-23 | Phases P0–P6 implemented on feature/push-privacy-wake |
