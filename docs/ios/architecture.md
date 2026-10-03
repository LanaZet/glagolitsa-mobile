# iOS architecture

**Status:** living  
**Scope:** Apple client shell + shared KMP framework + server push/calls contracts  
**Branch:** `feature/ios-support` (same monorepo)  
**Related:** [support-plan.md](support-plan.md) · [device-install.md](device-install.md) · [push architecture](../../server/docs/push/architecture.md)

---

## 1. Why a separate docs folder

Shared product architecture (conversations, channels, calls) is platform-agnostic and stays under `docs/architecture/`.

iOS-specific concerns (Xcode, APNs/PushKit, CallKit, Keychain access groups, NSE, device install) change on a different cadence and would clutter call/channel docs. Pattern used here:

| Path | Owns |
|------|------|
| `docs/architecture/*` | Cross-platform product & client/server shape |
| `docs/ios/*` | Apple-only plan, shell architecture, install |
| `server/docs/push/*` | FCM/APNs/UnifiedPush server truth |
| `iosApp/` | Xcode project + Swift entry |
| `shared/src/iosMain/` | Kotlin `actual` for Apple APIs |

Same repo, separate **namespace** — not a second git remote.

---

## 2. System shape (Apple)

```
┌──────────────────────────────────────────────────────────────┐
│ iosApp (Xcode / Swift)                                       │
│  · UIApplication / SwiftUI host                              │
│  · UNUserNotificationCenter (message alerts)                 │
│  · PushKit + CallKit (calls only; later phase)               │
│  · optional NSE / Share Extension (later)                    │
│  · signs & embeds Shared.framework                           │
└────────────────────────────┬─────────────────────────────────┘
                             │ Kotlin/Native export
                             │ ComposeUIViewController
┌────────────────────────────▼─────────────────────────────────┐
│ shared (framework baseName = Shared)                         │
│  commonMain: Compose UI, MessengerRepository, models         │
│  iosMain:    expect/actual — DB, Keychain session, HTTP,     │
│              crypto bridge, local notifications, pickers     │
└────────────────────────────┬─────────────────────────────────┘
                             │ HTTPS + WebSocket
┌────────────────────────────▼─────────────────────────────────┐
│ Go API (server/)                                             │
│  mailbox fan-out · notification.Service · APNsAdapter        │
│  calls · identity · media                                    │
└──────────────────────────────────────────────────────────────┘
```

**Rule of boundaries (Signal/WA-aligned):**

| Layer | May |
|-------|-----|
| **shared** | Decrypt, sync, prefs, decide notification *content* after local decrypt |
| **iosApp / system** | Register tokens, present CallKit UI, Keychain ACLs, extension lifecycle |
| **server** | Opaque wake push only; bind token ↔ `device_id`; never message body |

---

## 3. KMP targets

| Target | Role |
|--------|------|
| `iosArm64` | Physical iPhone / iPad |
| `iosSimulatorArm64` | Apple Silicon simulator |

`iosX64` is not used (deprecated in modern Kotlin/CMP toolchains).

Framework:

```kotlin
// shared/build.gradle.kts (phase 1+)
binaries.framework {
    baseName = "Shared"
    isStatic = true
}
```

Xcode build phase (or Gradle `embedAndSignAppleFrameworkForXcode`) produces/embeds `Shared.framework` into `iosApp`.

---

## 4. expect / actual map (iOS)

Everything declared `expect` in `commonMain` needs an `iosMain` `actual`. Grouping:

| Area | Types (indicative) | iOS approach (target) |
|------|--------------------|------------------------|
| Session | `SecureSessionStore` | Keychain (`AfterFirstUnlockThisDeviceOnly`) |
| DB | `DatabaseDriverFactory` | SQLDelight `native-driver` (+ encrypt later) |
| HTTP | `createHttpEngine`, `defaultBaseUrl` | Ktor Darwin; configurable API host for device |
| Crypto | `CryptoEngineFactory`, attachment/history crypto | libsignal iOS bridge; **stub OK until E2E phase** |
| Push | `PushTokenProvider`, `LocalMessageNotifier` | APNs device token + `UNUserNotificationCenter` |
| Calls | `createCallMediaEngine` | Noop → LiveKit iOS + CallKit |
| UI glue | pickers, back handler, secure window, QR | UIKit / PhotosUI / no-ops where needed |
| Platform | lifecycle, network path, installation id, log | Foundation / Network / UIKit |

Full inventory is maintained as actuals land (phase 1 commit lists files under `shared/src/iosMain/`).

---

## 5. Push (messages)

Reuse server design from [`server/docs/push/architecture.md`](../../server/docs/push/architecture.md):

1. Offline device → APNs **opaque** payload (`type`, `schema`, optional collapse).
2. Client wakes → `PushWakeCoordinator` → mailbox drain → decrypt in shared.
3. **Local** notification: default generic («Глаголица» / «Новое сообщение»); preview only after local decrypt + user prefs.
4. `PrivacyGuard` on server forbids body/sender/ciphertext in APNs.

```
APNs (message topic)
  → iosApp didReceiveRemoteNotification / NSE
  → Shared.PushWakeCoordinator
  → drain + decrypt
  → LocalMessageNotifier (UNNotification)
```

**Do not** use PushKit VoIP for ordinary messages (Apple policy / app kill risk).

### Notification Service Extension (later)

Optional NSE with `mutable-content: 1` for richer local processing under a 30s budget. Requires App Group + shared Keychain access group with the main app. Not required for first device messaging smoke if main app handles wake while backgrounded.

---

## 6. Calls (later phase)

| Channel | Use |
|---------|-----|
| APNs message | missed call local notify, non-ring events |
| **PushKit VoIP** | incoming call wake only |
| **CallKit** | system incoming UI; **must** `reportNewIncomingCall` on every VoIP push (iOS 13+) |

Payload stays opaque (`type=incoming_call`, `call_id`). Caller display name resolved **after** fetch + local policy (unknown caller gate — see calls docs).

Media: LiveKit iOS SDK behind `CallMediaEngine` actual (Android already has LiveKit path).

---

## 7. Security surface (Apple-specific)

| Concern | Approach |
|---------|----------|
| Session tokens | Keychain, not UserDefaults plaintext |
| DB at rest | SQLDelight file; SQLCipher/native encrypt when actual lands |
| E2EE keys | libsignal store in Keychain/file with device-only accessibility |
| Screenshot / app switcher | `SecureWindowEffect` actual (UITextField secure tricks / flag where useful) |
| Biometrics | `LocalAuthenticator` → LocalAuthentication |
| Extensions | shared Keychain access group + App Group container only for needed blobs |
| Notification forensics | default **generic** banner text (OS notification DB risk if previews enabled) |

---

## 8. Config & environments

| Item | Notes |
|------|--------|
| Bundle ID | e.g. `com.glagolitsa.mobile` (align with Android applicationId where possible) |
| API base URL | Device cannot use `10.0.2.2`; use LAN IP, staging HTTPS, or public API |
| APNs | Auth key `.p8`, Team ID, Key ID, bundle; sandbox vs production |
| Signing | Automatic Team signing for **dev device** install; no App Store required |

Server already has env-gated `APNsAdapter`; client registration uses `platform=ios` tokens.

---

## 9. What is explicitly out of this doc

- App Store Connect listing, Review guidelines package, ASC screenshots — **later**.
- macOS / Catalyst.
- UnifiedPush on iOS (not applicable like Android distributors).

---

## 10. Document history

| Date | Note |
|------|------|
| 2026-08-06 | Initial iOS architecture folder; monorepo + phase branch model |
