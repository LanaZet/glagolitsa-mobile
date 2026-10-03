# iOS support plan

**Status:** active  
**Repo:** same monorepo `glagolitsa-mobile`  
**Branch:** `feature/ios-support`  
**Process:** **1 phase = 1 git commit** (optionally 1 PR); do not mix Android-only refactors  
**Goal now:** installable **test build on a physical iPhone** (no App Store)  
**Related:** [architecture.md](architecture.md) · [device-install.md](device-install.md)

---

## 0. Reference: how messengers do Apple

| Concern | Industry pattern | Our choice |
|---------|------------------|------------|
| Message push | APNs opaque / NSE local decrypt | Signal-style wake-only (already on server) |
| Calls | PushKit VoIP **+** CallKit mandatory | After messaging; never VoIP for chat |
| Client code | Often 100% native Swift | KMP + Compose UI + thin Swift shell |
| Secrets | Keychain | Keychain session + later libsignal store |
| Layout in monorepo | `iosApp` + shared library | JetBrains KMP default structure |

We do **not** split a second git repository; we isolate via **branch + `docs/ios/` + `iosApp/` + `iosMain`**.

---

## 1. Phases

### Phase 0 — Docs & isolation *(this commit)*

| Deliverable | Done when |
|-------------|-----------|
| `docs/ios/*` plan + architecture + device-install | linked from `docs/README.md` |
| iosApp README points to `docs/ios` | stub not misleading |
| Branch `feature/ios-support` in same repo | no separate remote |

**Non-goals:** enable Gradle iOS targets (that is Phase 1 — needs actuals or build breaks).

---

### Phase 1 — Foundation compile + device shell

**Commit message style:** `ios: phase 1 — KMP targets, actuals skeleton, Xcode host`

1. Enable `iosArm64` + `iosSimulatorArm64` in `shared/build.gradle.kts`, framework `Shared`.
2. Implement **all** `expect` → `iosMain` actuals (stubs allowed for crypto/calls/backup).
3. Dependencies: Ktor Darwin, SQLDelight native-driver, coroutines native.
4. `MainViewController()` / Compose entry in shared or iosApp bridge.
5. Xcode project under `iosApp/` that embeds framework.
6. Script or README steps: run on **connected iPhone** (dev signing).

**Acceptance:**

- [ ] `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` succeeds  
- [ ] Xcode Run on physical device shows app shell (login UI)  
- [ ] No App Store / TestFlight required yet  

---

### Phase 2 — Foreground messaging

**Commit:** `ios: phase 2 — session, DB, foreground DM path`

1. Keychain-backed `SecureSessionStore` (not stub file if still unsafe).
2. Persistent SQLDelight DB on device.
3. API base URL usable from phone (HTTPS staging or LAN).
4. Login / register / chat list / open chat with live server while app foregrounded.
5. Crypto: stub → real libsignal iOS when spike done; document if DM E2E still stubbed.

**Acceptance:**

- [ ] Login against staging/public API from device  
- [ ] Send/receive in foreground (channels or plaintext path if E2E not ready)  

---

### Phase 3 — Message push (APNs)

**Commit:** `ios: phase 3 — APNs token + local notify`

1. Register for remote notifications; upload token `platform=ios`.
2. Wire wake → `PushWakeCoordinator` → local generic notification.
3. Server: production/sandbox `APNS_*` for the bundle id.
4. Optional NSE as 3b (separate commit if large).

**Acceptance:**

- [ ] Force-quit app → send DM from other device → banner → open → message visible  
- [ ] Push payload capture has no plaintext  

---

### Phase 4 — Calls (CallKit + PushKit)

**Commit:** `ios: phase 4 — CallKit PushKit LiveKit`

1. VoIP token path separate from message APNs.
2. CallKit report on every VoIP push.
3. LiveKit iOS `CallMediaEngine` actual.
4. Privacy gate for unknown callers (shared policy).

**Acceptance:**

- [ ] Killed app → incoming call UI → accept media  
- [ ] Message path never uses VoIP topic  

---

### Phase 5 — Polish for wider testers (still no Store)

**Commit:** `ios: phase 5 — TestFlight-ready hardening`

1. TestFlight internal (optional) — still not public App Store.
2. Communication notifications / badge policy.
3. Crash hygiene (no message bodies in logs).
4. Performance pass on chat lists.

App Store submission remains a **later** product decision (not this plan’s exit).

---

## 2. Dependency graph

```
P0 docs ──► P1 compile + device shell ──► P2 FG messaging
                    │                          │
                    └── libsignal spike ───────┘
                                               ▼
                                         P3 APNs messages
                                               ▼
                                         P4 CallKit (after Android calls stable)
                                               ▼
                                         P5 TestFlight polish
```

---

## 3. Risks

| Risk | Mitigation |
|------|------------|
| Missing actuals break CI | Phase 1 lands all actuals; optional CI job only after P1 |
| libsignal iOS packaging | Stub crypto in P1–P2; dedicated spike commit |
| Background limits | Design around push, not permanent WS |
| VoIP misuse | VoIP only for real calls + CallKit |
| Device API host | Document LAN/staging in device-install.md |
| Accidental Android churn | iOS commits touch `iosApp/`, `iosMain/`, `docs/ios/`, shared gradle only as needed |

---

## 4. Commit checklist (every phase)

1. Only iOS-scoped files (+ minimal shared expect if unavoidable).  
2. Update `docs/ios/support-plan.md` checkboxes / history if status changes.  
3. One logical phase per commit.  
4. Do not amend published commits on shared branches without agreement.  

---

## 5. History

| Date | Phase | Note |
|------|-------|------|
| 2026-08-06 | P0 | `docs/ios/` created; branch `feature/ios-support` in monorepo |
