# План реализации звонков

**Статус:** рабочий план, без коммитов кода  
**Дата:** 2026-08-03  
**Связанный документ:** [calls-stability.md](calls-stability.md)  
**Цель:** довести звонки до production-ready audio-first реализации, не закрывая путь к видео, групповым звонкам, межрегиональным маршрутам и будущим ИИ-субтитрам.

## Правила плана

1. Голос важнее видео.
2. E2EE и защита IP важнее выигрыша latency от direct P2P.
3. 1:1 звонок сразу реализуется как частный случай `call + participants`.
4. Групповые звонки v1 допускаются только внутри существующих group chats.
5. Межгород учитывается через route/region policy, а не через профиль пользователя или GPS.
6. ИИ-субтитры в ближайшие phases не реализуются; оставляется только архитектурное окно.
7. Любая диагностика redacted: без tokens, TURN credentials, media keys, IP, username/display name, plaintext transcript или audio chunks.

## Текущая точка старта

| Слой | Сейчас | Что мешает production calls |
|------|--------|-----------------------------|
| Server API | есть `/api/calls`, `/accept`, `/end`, `/connected`, `/token`, `/keys`, `/low-bandwidth`, `/api/ice/servers` | API 1:1-centric через `callee_id`; нет room/participants-first модели |
| DB | есть `calls`, `call_participants`, `call_key_offers` | `calls.callee_id` NOT NULL; history/authz завязаны на `caller_id/callee_id` |
| Client model | есть `CreateCallRequest`, `CallSession`, `CallMediaConfig` | нет group-ready полей: `chat_id`, `call_scope`, `participants`, `route_class` |
| Client controller | `CallController` создаёт/принимает/завершает звонки | нет реального LiveKit room connection и stats loop |
| UI | есть `CallScreen`, `CallsScreen`, входы из чата и профиля | нет media surfaces, route/quality notices, group participants sheet |
| Network monitor | есть базовые состояния сети | нет VPN, metered, candidate type, RTT, loss, jitter, bandwidth |
| Deployment | Postgres/Redis/MinIO | нет LiveKit, TURN, RTC DNS, health checks |

## Этап 0. Подготовка и фичефлаги

Цель: сделать так, чтобы звонки можно было включать слоями и откатывать без миграционного пожара.

Задачи:

1. Проверить текущие feature flags `calls_audio` и `calls_video`.
2. Добавить server/client flags:
   - `calls_rtc_livekit_enabled`;
   - `calls_audio_only_enabled`;
   - `calls_video_adaptive_enabled`;
   - `calls_group_ready_api_enabled`;
   - `calls_route_hints_enabled`;
   - `calls_unknown_request_gate_enabled`.
3. Зафиксировать конфиги RTC:
   - `RTC_REGION_ID`;
   - `LIVEKIT_URL`;
   - `LIVEKIT_API_KEY`;
   - `LIVEKIT_API_SECRET`;
   - `TURN_DOMAIN`;
   - `TURN_SHARED_SECRET` или equivalent short-lived credential secret.
4. Добавить kill switch: если RTC edge деградировал, UI должен скрыть start call buttons или показывать понятную ошибку.

Критерии готовности:

1. Можно включить только audio calls без video.
2. Можно выключить LiveKit calls без миграции DB назад.
3. Client update policy не предлагает неподдержанную функцию старым клиентам.

Проверки:

1. Unit tests для policy evaluation.
2. Manual check: flags выключены, UI не начинает новый call.

## Этап 1. RTC edge на новом сервере

Цель: поднять отдельный RTC слой до клиента, не смешивая media с Go API.

Задачи:

1. Добавить LiveKit service.
2. Подключить Redis для production LiveKit.
3. Настроить:
   - WSS signaling на 443;
   - WebRTC UDP range;
   - TURN/UDP 443;
   - TURN/TLS 443;
   - `rtc.use_external_ip` или корректный NAT/public IP config.
4. Добавить DNS:
   - `rtc-<region>.<domain>`;
   - `turn-<region>.<domain>`;
   - общий `rtc.<domain>` как будущий global entrypoint.
5. Настроить firewall/security groups.
6. Добавить health checks:
   - HTTP health LiveKit;
   - WSS connect;
   - TURN/UDP allocation;
   - TURN/TLS allocation.
7. Добавить metrics labels:
   - `rtc_region`;
   - `turn_region`;
   - `candidate_type`;
   - `candidate_protocol`;
   - `route_class`;
   - `vpn_detected`.

Критерии готовности:

1. Два test clients могут войти в одну LiveKit room.
2. TURN/UDP работает на 443.
3. TURN/TLS работает на 443 как fallback.
4. При закрытом UDP звонок всё ещё получает relay path.
5. Health check отличает “сервер жив” от “TURN реально не работает”.

Проверки:

1. Smoke test на обычной сети.
2. Smoke test с blocked UDP.
3. Smoke test через VPN.
4. Проверка логов: нет tokens, TURN credentials, media keys.

## Этап 2. Group-ready data model

Цель: убрать архитектурную зависимость от пары `caller/callee`.

Миграции:

1. В `calls` добавить:
   - `chat_id`;
   - `started_by_user_id`;
   - `call_scope` (`dm`, `group`, `ad_hoc`, `link`);
   - `livekit_room_id`;
   - `selected_region`;
   - `route_class`;
   - `policy_version`.
2. Сделать `callee_id` nullable или legacy-only.
3. В `call_participants` добавить:
   - `role`;
   - `invite_state`;
   - `media_state`;
   - `joined_at`;
   - `left_at`;
   - `device_id` как first-class participant dimension.
4. Добавить `call_invites`:
   - `call_id`;
   - `invited_user_id`;
   - `invited_by_user_id`;
   - `state`;
   - `created_at`;
   - `expires_at`.
5. Добавить индексы:
   - `call_participants(user_id, call_id)`;
   - `call_participants(call_id, invite_state)`;
   - `call_invites(invited_user_id, state, created_at)`;
   - `calls(chat_id, created_at)`;
   - legacy history index оставить до миграции clients.

Server changes:

1. `CreateCall` принимает `chat_id` и `initial_invitee_ids`.
2. Legacy `callee_id` request превращается в DM call wrapper:
   - найти/создать DM chat id;
   - `call_scope=dm`;
   - `initial_invitee_ids=[callee_id]`.
3. `AcceptCall`/`RejectCall` работает по participant/invite state, а не только по callee.
4. `ListCallHistory` читает через `call_participants`.
5. `applyCallPresence` работает по N participants.
6. Authorization проверяет:
   - user является participant или invited participant;
   - для group call user состоит в `chat_id`;
   - blocklist/privacy policy не запрещает звонок.

Client changes:

1. Добавить новые поля в `CallSession`.
2. Сохранить совместимость со старым ответом, пока server/client не обновлены вместе.
3. `CallController.startOutgoingCall` пока может принимать старый `calleeId`, но внутри model должен быть готов к `chatId`.

Критерии готовности:

1. Старый 1:1 звонок продолжает создаваться.
2. В DB 1:1 звонок представлен как `calls + 2 call_participants`.
3. History работает через participants.
4. Тесты показывают, что третий participant может быть invited на уровне модели без смены room.

Проверки:

1. Unit/integration tests server store.
2. Backward compatibility tests для `CreateCallRequest(callee_id=...)`.
3. Migration rollback strategy описана отдельно перед применением на production.

## Этап 3. Calling API hardening

Цель: сделать control plane устойчивым к гонкам, polling и multi-device.

Задачи:

1. Сделать `accept` idempotent:
   - первый accepted/joined device становится active;
   - остальные устройства того же user получают актуальный terminal/secondary state.
2. Добавить timeout job:
   - ringing -> missed;
   - stale connecting -> failed;
   - active без participants -> ended.
3. Добавить websocket events:
   - `call.created`;
   - `call.ringing`;
   - `call.accepted`;
   - `call.connected`;
   - `call.rejected`;
   - `call.ended`;
   - `call.participant_joined`;
   - `call.participant_left`;
   - `call.low_bandwidth`;
   - `call.route_degraded`.
4. Сохранить polling fallback на переходный период.
5. Добавить LiveKit webhooks:
   - participant joined;
   - participant left;
   - room finished;
   - track published/unpublished, если нужно для reconciliation.
6. `markCallConnected` принимать только после реального media connection на клиенте.
7. `GetCallToken` выдаёт короткоживущий token с opaque participant identity.

Критерии готовности:

1. Клиент больше не обязан polling `waitUntilAccepted()` каждую секунду.
2. Race “end vs accept vs connected” не ломает terminal state.
3. LiveKit room state и Go call state сходятся после webhook reconcile.

Проверки:

1. Unit tests на state machine.
2. Integration tests на duplicated accept/end.
3. Websocket reconnect test: клиент догоняет состояние после reconnect.

## Этап 4. Short-lived ICE/TURN credentials

Цель: убрать static TURN credentials до public calls.

Задачи:

1. `/api/ice/servers` выдаёт short-lived credentials.
2. Credentials scoped по user/device/session, где возможно.
3. TTL короткий, но достаточный для call setup и reconnect.
4. Token refresh path работает для долгих звонков.
5. Logs redaction проверяет:
   - `username`;
   - `credential`;
   - `urls`, если они включают secret;
   - LiveKit token.

Критерии готовности:

1. Static TURN credentials не уходят в client config.
2. Просроченный credential не ломает уже active call без понятного refresh path.
3. В debug logs нет secret material.

Проверки:

1. Unit tests для credential TTL/signature.
2. Integration test: token expires during long call, refresh succeeds.
3. Log snapshot test на redaction.

## Этап 5. Android LiveKit audio-only media engine

Цель: первый реальный звонок с микрофоном, без видео.

Задачи:

1. Повторно проверить latest stable `io.livekit:livekit-android` на момент реализации.
2. Добавить dependency в Gradle.
3. Добавить Android permissions:
   - microphone;
   - foreground service для звонка;
   - bluetooth/audio route, если требуется платформой.
4. Создать common interface `CallMediaEngine`:
   - `connect(token, url, options)`;
   - `publishMicrophone()`;
   - `muteMicrophone()`;
   - `setSpeaker()`;
   - `disconnect()`;
   - `events`;
   - `statsSnapshots`.
5. Android implementation подключает LiveKit room.
6. `CallController` вызывает `markCallConnected` только после:
   - room connected;
   - microphone publish started;
   - media path confirmed или first RTP progress observed.
7. UI:
   - mute;
   - speaker;
   - hangup;
   - reconnecting;
   - permission denied.
8. Background behavior:
   - foreground notification;
   - cleanup on app kill;
   - network switch handling.

Критерии готовности:

1. Два Android devices/emulators слышат друг друга.
2. Звонок не считается connected после одного token fetch.
3. Mute/speaker/hangup работают без утечки room.
4. Permission denied не оставляет hanging call.

Проверки:

1. Manual two-device audio smoke.
2. Network switch Wi-Fi -> cellular/VPN.
3. End call from caller and callee.
4. App background/foreground.

## Этап 6. Media E2EE и key exchange

Цель: не выпускать public audio/video calls без encrypted media.

Задачи:

1. Сгенерировать per-call media key на caller device.
2. Использовать существующий encrypted per-device key offer path.
3. Key offer создаётся только для accepted/joined devices, не для всех invited.
4. Ставить key в LiveKit E2EE provider до публикации media.
5. Rekey triggers:
   - participant join;
   - participant leave;
   - participant removed;
   - device switch;
   - verified safety number change.
6. Verified safety number changed:
   - verified contact: block call media setup до manual approval;
   - unverified contact: warning before media.
7. LiveKit egress/recording disabled для private calls.

Критерии готовности:

1. Go API и RTC edge не видят plaintext media.
2. Invited-but-not-joined participant не получает usable media key.
3. Left participant не может читать future media после rekey.
4. Changed verified safety number блокирует setup.

Проверки:

1. Unit tests key-offer eligibility.
2. Integration tests join/leave rekey.
3. Negative test: wrong/stale key cannot decrypt.
4. Log redaction tests.

## Этап 7. CallQualityPolicy и diagnostics

Цель: удерживать голос на слабой сети и заранее отключать видео.

Задачи:

1. Расширить `NetworkPathMonitor`:
   - VPN;
   - Wi-Fi/cellular;
   - metered;
   - constrained/captive;
   - available bandwidth estimate, если Android отдаёт.
2. Добавить stats loop каждые 2 секунды:
   - LiveKit connection quality;
   - room reconnect state;
   - RTT;
   - packet loss;
   - jitter;
   - available outgoing/incoming bitrate;
   - no-RTP-progress;
   - selected candidate type/protocol.
3. Реализовать `CallNetworkMode`:
   - `NO_PATH`;
   - `EMERGENCY_VOICE`;
   - `VOICE_ONLY`;
   - `VIDEO_PROBE`;
   - `VIDEO_LOW`;
   - `VIDEO_NORMAL`.
4. Реализовать hysteresis:
   - fast downgrade;
   - slow upgrade;
   - no repeated notices;
   - suppress video offers after repeated auto-off.
5. Diagnostics snapshots:
   - local only by default;
   - upload only by consent/debug flag;
   - redacted.

Критерии готовности:

1. При плохой сети policy уходит в `EMERGENCY_VOICE`/`VOICE_ONLY`.
2. При восстановлении сети video не предлагается мгновенно.
3. Diagnostics помогают понять candidate/protocol/route без раскрытия пользователя.

Проверки:

1. Unit tests для thresholds и hysteresis.
2. Simulated stats tests.
3. Manual weak-network matrix:
   - 3-5% loss, RTT 250 ms;
   - 8-15% loss, RTT 600 ms;
   - >20% loss или no RTP progress;
   - TURN/TLS relay only;
   - network recovery 30s.

## Этап 8. Adaptive video

Цель: видео становится улучшением, а не угрозой голосу.

Задачи:

1. Добавить camera permission и UI controls.
2. Не публиковать camera до stable audio.
3. Добавить `VIDEO_PROBE`:
   - маленький профиль;
   - короткое окно;
   - rollback на audio при плохих stats.
4. Использовать simulcast, Dynacast и adaptive stream, где поддерживается.
5. Добавить video ladder:
   - probe 160x90, 7 fps, 80 kbps;
   - low 320x180, 12 fps, 180 kbps;
   - normal 640x360, 20 fps, 500 kbps.
6. Автоматический downgrade:
   - pause/unpublish camera;
   - banner: `Сеть слабая. Отключаем видео, чтобы сохранить звонок.`
7. Upgrade только по подтверждению пользователя:
   - `Интернет стабильный. Можно включить видео.`

Критерии готовности:

1. Audio не деградирует при включении video.
2. Poor/Critical stats выключают video быстрее, чем ломается voice.
3. Пользователь может вручную включить video, но policy может откатить его ради звонка.

Проверки:

1. Video enable/disable smoke.
2. Weak network auto-off.
3. Repeated auto-off suppresses future prompts.
4. TURN/TLS video suppressed by default.

## Этап 9. Incoming calls, push и privacy gate

Цель: входящие звонки не раскрывают лишнее и не дают spam/full-screen path незнакомым людям.

Задачи:

1. Push payload только wake-only:
   - `type=incoming_call`;
   - opaque `call_id`;
   - collapse id;
   - без caller name, phone, avatar, token, room URL.
2. После wake client fetches call state and decrypts local user-visible copy.
3. Unknown/non-contact caller:
   - создаёт call/message request;
   - не получает full-screen ring;
   - не получает high-priority call UI до accepted request.
4. Accepted contacts/chats:
   - normal incoming call UI;
   - high priority only when it shows UI.
5. Android/iOS system call integration позже:
   - generic system label по умолчанию;
   - real names only opt-in;
   - не писать в system call log без решения.

Критерии готовности:

1. Unknown caller не может сразу разбудить full-screen call.
2. Push не содержит PII.
3. Blocklist и privacy settings применяются до ringing.

Проверки:

1. Push payload allowlist test.
2. Unknown caller request flow.
3. Accepted contact incoming flow.
4. Blocked caller cannot ring.

## Этап 10. Group call v1 внутри group chats

Цель: разрешить invite третьего в звонок, если звонок начат в existing group chat.

Задачи:

1. `POST /api/calls` принимает `chat_id` group chat.
2. Server создаёт one LiveKit room per `call_id`.
3. `POST /api/calls/{id}/invites` приглашает users только из того же group chat.
4. `accept invite` выдаёт token на тот же `livekit_room_id`.
5. UI:
   - bottom sheet `Участники звонка`;
   - `Позвать в звонок`;
   - active speaker row;
   - audio-only participants list;
   - video tiles только для active video publishers.
6. Rekey после фактического join/leave.
7. Limits:
   - audio group call 4-8 participants;
   - video publishers 2-4;
   - остальные audio-only.

Критерии готовности:

1. Двое в group chat могут позвать третьего без пересоздания room.
2. User outside group chat не может получить invite/token.
3. Новый participant не decrypt прошлое.
4. Left participant не decrypt будущее.

Проверки:

1. Three-device group call.
2. Non-member invite denied.
3. Join/leave rekey.
4. Group limits enforced.

## Этап 11. Межгород и route readiness

Цель: не закопать международные звонки в один жёсткий регион.

Задачи для v1:

1. Ввести `selected_region=primary` и `route_class=single_region`.
2. В token response добавить route metadata.
3. Metrics помечать `rtc_region`, `turn_region`, `candidate_type`, `candidate_protocol`.
4. Client quality policy учитывает:
   - `relay/tls`;
   - high RTT;
   - VPN;
   - `route_class`.

Задачи для v2:

1. Добавить региональный конфиг:
   - `region_id`;
   - `livekit_url`;
   - `turn_urls`;
   - `health`;
   - `capacity`.
2. Добавить privacy-safe `rtc_route_hints`:
   - coarse RTT buckets;
   - WSS reachable;
   - TURN/UDP reachable;
   - TURN/TLS reachable.
3. Реализовать `CallRouteSelector`:
   - minimize worst RTT for 1:1;
   - p75/p90 for group;
   - load penalty;
   - compliance deny/pin;
   - unhealthy region failover.
4. Добавить synthetic probes per region.
5. Не делать cascaded SFU до отдельного решения.

Критерии готовности:

1. Даже с одним регионом все metrics/DB/API уже region-aware.
2. Wrong/far route виден в diagnostics.
3. Переход к нескольким регионам не требует менять call model.

Проверки:

1. Intercity RTT 300-500 ms, loss < 3%: audio stable, delayed/suppressed video offer.
2. International RTT > 800 ms: audio-only by default.
3. Far RTC region selected: route degraded, next calls can prefer better region after probes.

## Этап 12. Future AI window без реализации ИИ

Цель: оставить место для live captions/translation, но не тащить ИИ в первый релиз звонков.

Сейчас делаем:

1. Не завязываем call transport только на audio/video tracks.
2. Оставляем encrypted realtime data/text lane для будущих caption events.
3. Caption/event payloads проектируем как encrypted per participant/device.
4. Не добавляем plaintext transcript fields в call history.
5. Rekey строим так, чтобы future caption keys ротировались вместе с call keys.

Сейчас не делаем:

1. STT provider.
2. LLM/translation provider.
3. LiveKit Agent translator.
4. Transcript storage.
5. UI subtitles.
6. Server-side plaintext transcript processing.

Критерии готовности:

1. Будущий `CallCaptionTranslator` можно добавить без переписывания `CallMediaEngine`.
2. Существующая E2EE модель не требует отдавать всем participants plaintext audio.
3. План не создаёт обязательную AI-зависимость для звонка.

## Этап 13. Release hardening

Цель: выпускать звонки постепенно и измеримо.

Задачи:

1. Feature rollout:
   - internal dogfood;
   - audio-only beta;
   - audio-only public small percentage;
   - adaptive video beta;
   - group call beta.
2. Observability:
   - call setup success rate;
   - time to media connected;
   - reconnect count;
   - selected candidate type;
   - route class;
   - downgrade count;
   - audio-only survival rate;
   - crash-free call sessions.
3. Privacy review:
   - logs;
   - metrics;
   - push payloads;
   - diagnostics upload;
   - LiveKit webhooks.
4. Capacity testing:
   - one-to-one audio;
   - one-to-one video;
   - group audio 4-8;
   - group video publishers 2-4;
   - TURN/TLS worst case.
5. Runbooks:
   - RTC edge down;
   - TURN broken;
   - high packet loss region;
   - token issuing failure;
   - LiveKit webhook lag;
   - bad release rollback.

Критерии готовности:

1. Есть kill switch.
2. Есть dashboards и alerting.
3. Есть support diagnostics flow без приватных данных.
4. Есть ручной сценарий проверки перед release.

## Минимальный первый production slice

В первый slice входит:

1. LiveKit + TURN на новом сервере.
2. Group-ready DB/API base, но без public group calls.
3. Android audio-only LiveKit media engine.
4. Short-lived tokens/ICE credentials.
5. E2EE media key path.
6. `VOICE_ONLY` и `EMERGENCY_VOICE`.
7. Privacy-safe diagnostics.
8. Incoming call wake/fetch flow без лишнего push payload.

В первый slice не входит:

1. Video.
2. Public group calls.
3. Multi-region routing beyond `selected_region=primary`.
4. Cascaded SFU.
5. ИИ-субтитры.
6. PSTN/SIP.
7. Recording/egress.

Definition of Done:

1. Два Android устройства держат 30-minute audio call.
2. При слабой сети звонок уходит в emergency voice, а не падает сразу.
3. VPN + TURN/TLS path работает хотя бы audio-only.
4. `markCallConnected` зависит от реального media path.
5. Server/RTC logs не содержат media keys, tokens, TURN credentials, IP peer или profile names.
6. E2EE включён для public build.

## Карта тестов

| Тип | Что проверяем |
|-----|---------------|
| Unit | state machine, quality policy, route selector, key-offer eligibility, feature flags |
| Store/API | migrations, legacy wrapper, participants history, invites, idempotent accept/end |
| Client unit | `CallController` transitions, no-repeat notices, diagnostics redaction |
| Android integration | permissions, foreground service, audio route, LiveKit connect/disconnect |
| Two-device | outgoing, incoming, accept, reject, end, reconnect |
| Weak network | loss, jitter, RTT, blocked UDP, TURN/TLS only |
| VPN | both on VPN, one on VPN, VPN switch during active call |
| Security | E2EE setup, rekey, verified safety number changes, unknown caller gate |
| Group | invite third, non-member denied, participant limits, join/leave rekey |
| International | far region, high RTT, route degraded, delayed video offer |

## Очередность PR

1. Docs and flags.
2. RTC deployment/config.
3. DB migration: group-ready calls.
4. Server store/API compatibility wrapper.
5. Websocket call events and timeout jobs.
6. Short-lived ICE/TURN credentials.
7. Android LiveKit dependency and `CallMediaEngine`.
8. Audio-only call lifecycle.
9. E2EE media key integration.
10. `CallQualityPolicy` and diagnostics.
11. Incoming call privacy gate.
12. Adaptive video.
13. Group call invites.
14. Route hints and multi-region readiness.
15. Future AI window only when product decides to start captions.

## Запрещённые сокращения

1. Не ship public calls без E2EE.
2. Не использовать static TURN credentials в public build.
3. Не считать звонок connected после token fetch.
4. Не строить DB/API только вокруг `caller_id/callee_id`.
5. Не включать P2P direct path в v1.
6. Не логировать raw WebRTC candidates, tokens, credentials или keys.
7. Не давать unknown caller full-screen ringing без accepted request.
8. Не делать video-first flow.
9. Не добавлять ИИ-бота, который слушает всех, без отдельного consent/indicator design.
