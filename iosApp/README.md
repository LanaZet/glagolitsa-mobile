# iosApp

Xcode shell для iOS. **Тот же monorepo**, что Android/desktop — не отдельный git-репозиторий.

## Документация

Всё iOS-специфичное лежит в [`docs/ios/`](../docs/ios/README.md), чтобы не мешать `docs/architecture/` (channels/calls):

| Документ | Зачем |
|----------|--------|
| [architecture](../docs/ios/architecture.md) | Shared.framework + Swift host + push/CallKit границы |
| [support-plan](../docs/ios/support-plan.md) | Фазы; **1 phase = 1 commit** на `feature/ios-support` |
| [device-install](../docs/ios/device-install.md) | Поставить тестовую сборку на iPhone **без App Store** |

## Статус

| Phase | Состояние |
|-------|-----------|
| P0 docs | done (`docs/ios/`) |
| P1 Xcode + KMP targets + actuals | next |
| P2+ messaging / push / calls | plan |

Пока Xcode-проект не добавлен (Phase 1): здесь только указатель на доки.

## Когда появится проект (Phase 1)

1. Раскомментировать / включить iOS targets в `shared/build.gradle.kts`
2. Xcode project в этой папке, embed `Shared` framework
3. Signing → Team → Run на device (см. device-install)
