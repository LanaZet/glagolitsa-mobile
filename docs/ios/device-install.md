# iOS: тестовая установка (Simulator + device, без App Store)

**Status:** active (Simulator via run-and-debug; physical device via Xcode)
**Goal:** debug/test on **Simulator** or **physical** iPhone
**Not in scope:** App Store Connect public listing, Review

---

## 0. Быстрый путь: Simulator + локальный сервер

**Тот же** Docker Postgres + Go API, что и для Android-эмулятора. Отдельный сервер для iOS не нужен.

| Клиент | URL к API на Mac |
|--------|------------------|
| host / curl | `http://localhost:8080` |
| Android emulator | `http://10.0.2.2:8080` |
| **iOS Simulator** | `http://127.0.0.1:8080` |

```bash
# полный цикл: Postgres + Go + iOS Simulator + install + launch
./scripts/run-and-debug.sh --ios

# только app (сервер уже крутится)
./scripts/run-and-debug.sh --ios --app-only

# Android + iOS на одном сервере
./scripts/run-and-debug.sh --both

# только сервер (как раньше)
./scripts/run-and-debug.sh --server-only
```

Переменные: `IOS_SIMULATOR_NAME` (default `iPhone 17 Pro`), `IOS_API_BASE_URL`.

Клиент на симуляторе сам выбирает `http://127.0.0.1:8080` (см. `ApiClient.ios.kt`).

---

## 1. Что нужно (physical device)

| Требование | Зачем |
|------------|--------|
| Mac + Xcode (актуальный stable) | сборка и deploy |
| Apple ID (бесплатный **или** paid Developer) | подпись |
| Кабель / network debug к iPhone | установка |
| Включено **Developer Mode** на iPhone (iOS 16+) | запуск dev builds |
| Доверие компьютеру / developer app | первый запуск |

**Paid Apple Developer ($99/y)** — удобнее (7-day re-sign vs 7-day free provisioning, devices list, later TestFlight). Для «поставить себе на телефон» часто хватает free Apple ID + automatic signing, с ограничениями.

---

## 2. Модель (monorepo)

```
feature/ios-support          ← git branch, same remote origin
   shared/ → Shared.framework
   iosApp/ → .app signed for your Team → install to device
```

Никакого отдельного git-репо. Remote тот же, что у Android.

---

## 3. После Phase 1 (ожидаемый flow)

### 3.1 Один раз

1. Открыть `iosApp/iosApp.xcodeproj` (путь уточнится в Phase 1).  
2. Signing & Capabilities → **Your Team**, unique Bundle ID если нужно.  
3. Подключить iPhone, выбрать его как run destination.  
4. На телефоне: Settings → Privacy & Security → **Developer Mode** → On.  

### 3.2 Сборка framework + app

Типичный KMP flow:

```bash
# из корня monorepo — framework для device (пример; точная task в Phase 1)
./gradlew :shared:linkDebugFrameworkIosArm64

# либо Xcode Run, который сам зовёт embedAndSignAppleFrameworkForXcode
```

В Xcode: **Product → Run** (▶) на выбранный iPhone.

### 3.3 Если «Untrusted Developer»

iPhone → Settings → General → VPN & Device Management → trust your developer certificate → открыть app снова.

---

## 4. API с телефона

Эмулятор Android ходит на `10.0.2.2`. **iPhone так не умеет.**

| Среда | `defaultBaseUrl` / override |
|-------|------------------------------|
| Локальный Go на Mac | `http://<LAN-IP-Mac>:8080` (тот же Wi‑Fi; ATS может требовать exception для cleartext) |
| Staging / public HTTPS | `https://api.…` (предпочтительно) |

Cleartext HTTP на iOS требует `NSAppTransportSecurity` exception в Info.plist — только для dev; prod = HTTPS.

---

## 5. Push на device (Phase 3+)

1. Push capability на App ID.  
2. APNs key `.p8` на сервере (`APNS_*`).  
3. Real device **обязателен** для remote push (симулятор ограничен).  
4. Sandbox APNs для dev builds.

До Phase 3 push можно не настраивать: foreground messaging достаточен для UI smoke.

---

## 6. TestFlight (опционально, Phase 5)

Внутреннее распространение тестерам **без** публичного Store:

1. Archive в Xcode.  
2. Upload в App Store Connect.  
3. Internal Testing group.  

Это всё ещё не «релиз в App Store». Описано отдельно, когда дойдём до Phase 5.

---

## 7. Troubleshooting (черновик)

| Симптом | Что проверить |
|---------|----------------|
| No signing team | Xcode → Settings → Accounts |
| Could not launch | Developer Mode; trust certificate |
| Framework not found | Gradle link task; FRAMEWORK_SEARCH_PATHS |
| Network fail to API | LAN IP, ATS, server bind `0.0.0.0` |
| App kills on VoIP | Only after Phase 4; must CallKit-report |

---

## 8. History

| Date | Note |
|------|------|
| 2026-08-06 | Placeholder until Xcode project exists (Phase 1) |
