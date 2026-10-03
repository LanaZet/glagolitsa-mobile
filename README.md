# Глаголица

Мессенджер для переписки и звонков, в том числе на дальние расстояния. Клиенты на Android, iOS и desktop, сервер на Go — в этом же репозитории.

Публичного хоста в комплекте нет. Сервер поднимается у себя: локально через Docker или на своей машине. Домен `api.glagolit.me` в коде — имя, под которое собирается свой деплой, а не обещание, что сервис уже работает.

## Что умеет

| | Шифрование | Где работает |
|--|------------|--------------|
| Личные сообщения и закрытые группы | Сквозное, Signal Protocol (libsignal) | Android; iOS, если подключён LibSignal. Desktop-криптодвижок пока заглушка |
| Каналы | Нет. Сервер видит рассылку | Все клиенты |
| Звонок 1:1 | На Android медиа-ключ создаётся на устройстве и ставится в LiveKit E2EE. Поверх этого ещё DTLS-SRTP до SFU | Android публикует звук в комнату. На iOS экран звонка есть, медиаслой комнаты ещё не подключён |
| Дальняя связь | TURN, в том числе UDP/443 и TURNS/443, чтобы звонок проходил через NAT и сети, где режут обычный WebRTC | Когда поднят RTC edge из `server/deploy/rtc/` |

Канал — это не секретный чат. Звонок без своего LiveKit и TURN не «просто заработает по интернету»: клиенту нужен сервер, который выдаёт токен комнаты и ICE.

## Стек

- Kotlin Multiplatform, Compose Multiplatform
- Ktor к Go API
- libsignal для личных и групповых сообщений
- LiveKit + coturn для звонков
- Postgres, по желанию Redis и файловое хранилище вложений

## Структура

```
glagolitsa-mobile/
├── shared/       общий UI, API, крипто, звонки
├── androidApp/   Android
├── iosApp/       iOS
├── desktopApp/   desktop
├── server/       Go API, миграции, деплой, RTC
├── maestro/      необязательные UI-смоки
└── docs/         архитектура
```

## Запуск у себя

Нужны Docker, JDK 17 и Android SDK (для телефона или эмулятора).

```bash
./scripts/run-and-debug.sh --server-only   # Postgres + локальный API
./scripts/run-and-debug.sh                 # то же + Android
./scripts/run-and-debug.sh --ios           # симулятор, API http://127.0.0.1:8080
```

| Клиент | Локальный API |
|--------|----------------|
| Android-эмулятор | `http://10.0.2.2:8080` |
| iOS Simulator | `http://127.0.0.1:8080` |
| Свой сервер | `-PapiBaseUrl=https://your.api` или `apiBaseUrl` в `local.properties` |

`local.properties` в git не входит. Туда же, если нужно, кладётся `apiFallbackIp` — запасной адрес API, когда DNS имени не отвечает. В репозитории этого адреса нет.

Push на Android: скопируйте `androidApp/google-services.json.example` в `androidApp/google-services.json` и подставьте свой Firebase-проект. Без этого файла сборка проходит, пуши не регистрируются. Настоящий `google-services.json` не коммитится.

Звонки на своей машине: [server/deploy/rtc/README.md](server/deploy/rtc/README.md). Пример окружения API: [server/deploy/production.env.example](server/deploy/production.env.example). Скрипты деплоя не знают ваш IP: `VPS_HOST` и `PUBLIC_HOST` нужно передать самим.

Локальные учётки для отладки — Marco / Polo (`scripts/lib/dev-accounts.sh`). В release-сборку они не зашиваются.

## Проверки

| Слой | Команда |
|------|---------|
| Unit-тесты клиента и Go-сервер | `./scripts/pr-check.sh` |
| Только клиент | `./scripts/test-unit.sh` |
| Только сервер | `./scripts/test-server.sh` |
| Живой локальный API | `./scripts/test-messaging.sh --live-local` |

Серверные тесты берутся из `server/` этого репозитория. UI-смоки Maestro необязательны: [maestro/README.md](maestro/README.md).

## Лицензия и права

Код распространяется по **[Glagolitsa Source Available License](LICENSE)**. Это source-available лицензия в духе [Mattermost Source Available License](https://docs.mattermost.com/product-overview/faq-mattermost-source-available-license.html), не OSI open source (не MIT, не Apache, не GPL).

| Можно | Нельзя без отдельного разрешения |
|-------|----------------------------------|
| Читать, изучать, форкать исходники | Продавать код или продукт на его основе |
| Некоммерчески запускать и менять | Поднимать как платный / hosted-сервис |
| Разработка, оценка, тесты (в т.ч. в компании) | Production в коммерческих целях |
| Распространять при сохранении этой лицензии | Убирать копирайт / выдавать код за свой |

**Обязательная ссылка на автора** — Svetlana Zavatskaia / Glagolitsa (`https://github.com/LanaZet/glagolitsa-mobile`): в исходниках (этот `LICENSE` + заголовки файлов), а в приложении — экран «О приложении».

Коммерческая лицензия: [github.com/LanaZet](https://github.com/LanaZet).

### Важное про libsignal и готовые сборки

В репозитории подключён `libsignal` **0.96.4**:

- Android: `org.signal:libsignal-android` / `org.signal:libsignal-client`;
- iOS: `LibSignalClient` из `https://github.com/signalapp/libsignal.git`, tag `v0.96.4`.

`libsignal` распространяется под **GNU AGPL-3.0**. Исходники этого репозитория можно публиковать под Glagolitsa Source Available License, пока `libsignal` не включён в сам репозиторий и подтягивается как внешняя зависимость при сборке.

Готовые APK / IPA / desktop-сборки, которые включают `libsignal`, нельзя распространять как продукт только под Glagolitsa Source Available License: для такой сборки нужно выполнить условия AGPL-3.0 для всей связанной программы, убрать/заменить `libsignal`, либо получить отдельное разрешение у правообладателя `libsignal`.

Это не меняет лицензию оригинального кода Глаголицы, но ограничивает то, как можно распространять бинарные сборки с AGPL-зависимостью внутри.

Шрифт Philosopher лежит в `shared/src/commonMain/composeResources/font/` и распространяется под **SIL Open Font License 1.1**; текст лицензии находится рядом со шрифтами: [`OFL.txt`](shared/src/commonMain/composeResources/font/OFL.txt).

Заголовок в начале исходников:

```text
// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.
```

- [`LICENSE`](LICENSE) · [`NOTICE`](NOTICE) · [`SECURITY.md`](SECURITY.md)
- Проставить вручную: `./scripts/add-license-headers.sh` · проверка: `--check`
- На новых файлах: `./scripts/install-git-hooks.sh` один раз после клона

## Документация

Индекс: **[docs/README.md](docs/README.md)**

| Тема | Документ |
|------|----------|
| Обзор | [docs/architecture/overview.md](docs/architecture/overview.md) |
| DM / group / channel | [docs/architecture/conversations.md](docs/architecture/conversations.md) |
| Каналы | [docs/architecture/channels.md](docs/architecture/channels.md) |
| Звонки | [docs/architecture/calls-stability.md](docs/architecture/calls-stability.md) |
| RTC edge | [server/deploy/rtc/README.md](server/deploy/rtc/README.md) |
| Что зашифровано | [SECURITY.md](SECURITY.md) |
| iOS | [docs/ios/README.md](docs/ios/README.md) |
