# Документация Glagolitsa Mobile

Индекс архитектурных документов. Корневой README: [`../README.md`](../README.md).

## Архитектура

| Документ | Содержание |
|----------|------------|
| [architecture/overview.md](architecture/overview.md) | Обзор системы: слои client/server, crypto paths |
| [architecture/conversations.md](architecture/conversations.md) | DM / group / channel — сравнение и инварианты |
| [architecture/channels.md](architecture/channels.md) | Каналы: policies, roles, media paths |
| [architecture/calls-stability.md](architecture/calls-stability.md) | Звонки: audio-first архитектура, слабая сеть/VPN, безопасность |
| [architecture/calls-implementation-plan.md](architecture/calls-implementation-plan.md) | Звонки: подробный порядок внедрения, тесты, rollout |
| [architecture/calls-release-runbook.md](architecture/calls-release-runbook.md) | Звонки: rollout, kill switches, runbooks |
| [architecture/calls-real-server-checklist.md](architecture/calls-real-server-checklist.md) | Звонки: чеклист до smoke на реальном сервере |
| [../server/deploy/rtc/README.md](../server/deploy/rtc/README.md) | RTC edge: LiveKit + TURN deploy, DNS, health |
| [../server/docs/channels/architecture.md](../server/docs/channels/architecture.md) | Server: group/channel module, API, migrations |

## iOS (отдельная папка, тот же monorepo)

Платформенные доки Apple не смешиваются с channels/calls. Код iOS лежит в этом же дереве: `iosApp/` и `shared/src/iosMain/`.

| Документ | Содержание |
|----------|------------|
| [ios/README.md](ios/README.md) | Индекс iOS |
| [ios/architecture.md](ios/architecture.md) | Shell ↔ Shared.framework ↔ API |
| [ios/support-plan.md](ios/support-plan.md) | Фазы (1 phase = 1 commit), критерии |
| [ios/device-install.md](ios/device-install.md) | Сборка на физический iPhone без App Store |

## Прочее (уже в репо)

| Документ | Содержание |
|----------|------------|
| [media-cache.md](media-cache.md) | Client E2E media cache |
| [../server/docs/push/architecture.md](../server/docs/push/architecture.md) | Push |
| [../server/docs/security/](../server/docs/security/) | Auth / security notes |
| [../SECURITY.md](../SECURITY.md) | Что зашифровано, а что нет |

## Лицензия

**[Glagolitsa Source Available License](../LICENSE)** — исходники открыты, некоммерческое использование с обязательной ссылкой на автора (Svetlana Zavatskaia). Коммерческое использование — по отдельному разрешению. См. корневой README § «Лицензия и права» и [`NOTICE`](../NOTICE).
