# iOS (Apple) — индекс

Документы **только про iOS**. Общая архитектура мессенджера остаётся в [`../architecture/`](../architecture/); push/privacy — в [`../../server/docs/push/`](../../server/docs/push/).

Всё живёт **в том же репо** (`glagolitsa-mobile`), ветка разработки: `feature/ios-support` (и последующие PR в `development`). Отдельный репозиторий под iOS **не** заводим — стандарт KMP monorepo: `shared/` + `iosApp/` + `androidApp/`.

## Документы

| Файл | Содержание |
|------|------------|
| [architecture.md](architecture.md) | Слои: iosApp shell ↔ shared framework ↔ Go API; push/CallKit границы |
| [support-plan.md](support-plan.md) | Фазы внедрения (P0…P5), критерии, риски |
| [device-install.md](device-install.md) | Тестовая сборка на физический iPhone **без App Store** |

## Код (тот же monorepo)

```
glagolitsa-dev/glagolitsa-mobile/
├── shared/
│   ├── src/commonMain/     # UI + logic (Compose)
│   ├── src/iosMain/        # expect/actual для Apple
│   └── src/androidMain/
├── iosApp/                 # Xcode shell (Swift + embed Shared.framework)
├── androidApp/
├── desktopApp/
└── docs/ios/               # ← этот каталог (доки не смешиваем с calls/channels)
```

Так же делают JetBrains KMP-шаблоны и production monorepo: платформенные app-модули рядом, shared — одна библиотека.

## Связь с общими доками

| Тема | Где истина |
|------|------------|
| DM / group / channel model | [`../architecture/conversations.md`](../architecture/conversations.md) |
| Calls product / privacy | [`../architecture/calls-stability.md`](../architecture/calls-stability.md) |
| Push wake-only / APNs adapter | [`../../server/docs/push/architecture.md`](../../server/docs/push/architecture.md) |
| Client overview | [`../architecture/overview.md`](../architecture/overview.md) |

## Правила работы

1. **Одна фаза → один commit** на ветке `feature/ios-support` (или stack PR).
2. Не класть длинные iOS runbook’и в `docs/architecture/` — только короткая ссылка из overview.
3. App Store / Review — **вне** текущего scope; цель — device + TestFlight-ready path (см. device-install).
