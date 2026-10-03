# Архитектура стабильных звонков

**Статус:** исследование и архитектурное решение  
**Дата:** 2026-08-03  
**Область:** мобильный клиент Glagolitsa, Go API, новый RTC-сервер  
**Цель:** удерживать аудиозвонок на самом слабом ещё пригодном интернете; включать видео только когда сеть достаточно здорова; автоматически или заранее возвращаться к режиму "только голос", если видео начинает угрожать звонку.

**План внедрения:** [calls-implementation-plan.md](calls-implementation-plan.md)

## Короткое решение

Звонки Glagolitsa должны быть audio-first: сначала стабильный голос, потом видео как улучшение.

Правильное поведение продукта:

1. Каждый звонок сначала стабилизирует аудио.
2. Если сеть долго держится в хорошем состоянии, приложение спокойно предлагает включить видео.
3. Если видео уже включено, а сеть ухудшается, приложение предупреждает пользователя и отключает видео до того, как начнут рушиться сигналинг или голос.
4. Если сеть критическая, приложение отключает всё неважное и тратит остаток канала на Opus-голос, переподключение и ICE/TURN-восстановление.

Технически это нужно делать как LiveKit/WebRTC media layer с локальным `CallQualityController`, который объединяет качество LiveKit, WebRTC-статистику, Android network path signals и состояние звонка в приложении. Сервер выдаёт короткоживущие room token, короткоживущую ICE/TURN-конфигурацию, принимает LiveKit webhooks и хранит метаданные звонка. Сервер не должен видеть plaintext-медиа или ключи шифрования звонка.

## Аудит текущего репозитория

В репозитории уже есть полезный каркас звонков:

| Область | Текущее состояние |
|---------|-------------------|
| Server API | `server/internal/api/handler.go` регистрирует `/api/calls`, `/api/calls/{id}/accept`, `/end`, `/connected`, `/token`, `/keys`, `/low-bandwidth` и `/api/ice/servers`. |
| Server calling module | `server/internal/calling/` уже выдаёт LiveKit token, собирает дефолтный media config, отдаёт ICE, хранит encrypted key offers, вызывает presence hooks и меняет статусы звонка. |
| DB | `server/internal/store/migrations/011_calling.sql` хранит calls, participants и encrypted per-device call key offers. |
| Client model/API | `shared/src/commonMain/kotlin/com/glagolitsa/model/Call.kt`, `ApiClient.kt` и `CallController.kt` уже знают про calls, tokens, ICE servers, low bandwidth mode, history и UI state. |
| Client UI | Уже есть `CallScreen`, `CallsScreen`, входы из чата/профиля и нижняя вкладка "Звонки". |
| Network monitor | Android `NetworkPathMonitor` сейчас сообщает только unavailable/constrained/available. Он ещё не отдаёт VPN, Wi-Fi/cellular, metered, bandwidth estimate, RTT, loss или media stats. |
| Media layer | Android: `LiveKitCallMediaEngine` подключается к комнате и ставит E2EE-ключ в `E2EEOptions`, если `CallController` его передал. iOS: `createCallMediaEngine()` пока возвращает `NoopCallMediaEngine`, аудио в комнату не публикуется. |
| Deployment | Локальный API: `server/docker-compose.yml` (Postgres/Redis/MinIO). RTC edge (LiveKit + coturn): `server/deploy/rtc/`. |

Вывод: реализацию нужно строить как развитие существующего calling module, а не переписывать всё заново.

## Выводы из внешнего исследования

Общий вывод WebRTC, LiveKit и китайских RTC-документов одинаковый: слабая сеть требует адаптивного звонка с приоритетом аудио.

Ключевые источники:

| Тема | Вывод |
|------|-------|
| LiveKit deployment | Production LiveKit требует WSS/TLS, корректного public IP awareness, Redis для production и TURN. LiveKit отдельно пишет, что TURN/TLS даёт самое широкое покрытие для firewall-сетей, а при отсутствии load balancer порт должен быть 443. UDP всё ещё лучше для latency/congestion control, поэтому TURN/UDP на 443 тоже полезен. Источник: https://docs.livekit.io/transport/self-hosting/deployment/ |
| LiveKit quality events | LiveKit отдаёт connection quality на основе packet loss, latency и jitter. Состояния `Poor` и `Lost` можно использовать для degraded/reconnecting UX ещё до полного disconnect. Источник: https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/ |
| LiveKit video adaptation | Simulcast позволяет SFU отправлять слой, который подходит под bandwidth получателя; Dynacast прекращает публикацию неиспользуемых слоёв. Источник: https://docs.livekit.io/transport/media/advanced/ |
| LiveKit congestion handling | LiveKit умеет при congestion ставить video streams на паузу, сохраняя audio, и возвращать видео после восстановления сети. Источник: https://kb.livekit.io/articles/8062994967-managing-video-quality-during-network-congestion |
| Opus | Opus поддерживает динамический bitrate от 6 до 510 kbit/s. RFC 7587 указывает 8-12 kbit/s для narrowband speech и 16-20 kbit/s для wideband speech; VBR обычно лучший вариант для голоса. Opus поддерживает DTX и in-band FEC. Источник: https://www.rfc-editor.org/info/rfc7587/ |
| WebRTC stats | `RTCPeerConnection.getStats()` отдаёт runtime stats; candidate pair stats включают available incoming/outgoing bitrate и RTT-связанные метрики. Источники: https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/getStats и https://developer.mozilla.org/en-US/docs/Web/API/RTCIceCandidatePairStats |
| ICE restart | RFC 8445 описывает ICE restart как механизм смены media destinations и обновления candidate state; существующая data session при этом может продолжать жить. Источник: https://www.rfc-editor.org/info/rfc8445/ |
| Alibaba Cloud RTC | Их Android guidance для слабой сети рекомендует через network quality callbacks снижать video profile, переключаться на small stream, а в экстремальном режиме публиковать только audio stream, чтобы сохранить связь. Источник: https://help.aliyun.com/en/document_detail/2664047.html |
| Agora/Shengwang | Их in-call quality docs описывают callbacks каждые 2 секунды для uplink/downlink quality, RTC stats, local/remote audio/video stats и state changes. Источник: https://doc.shengwang.cn/doc/rtc/rn/advanced-features/in-call-quality |
| Tencent TRTC | Tencent публикует weak-network MOS/performance data и QoS/network quality concepts; в типах есть различение smooth vs clear video preference для слабой сети. Источники: https://cloud.tencent.com/document/product/647/56382 и https://trtc.io/zh/document/50768 |
| LiveKit Android SDK | На дату исследования Maven Central показывает `io.livekit:livekit-android:2.27.0`. Источник: https://central.sonatype.com/artifact/io.livekit/livekit-android |
| LiveKit group-ready SFU | LiveKit является horizontally-scaling WebRTC SFU. Документация LiveKit прямо говорит, что P2P хорошо работает только для 2-3 peers, а группы больше 2-3 требуют client-server model; SFU даёт масштабируемость и контроль отдельных audio/video tracks. Источник: https://docs.livekit.io/reference/internals/livekit-sfu/ |
| LiveKit participant model | Participant identity уникален внутри room; повторный join с тем же identity отключает предыдущий participant. Tokens содержат participant identity, room и permissions. Источники: https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/ и https://docs.livekit.io/frontends/reference/tokens-grants/ |
| LiveKit permissions/events | Backend может менять participant permissions, например grant/revoke `CanPublish`; SDK events включают `ActiveSpeakersChanged`, `ConnectionQualityChanged`, track publish/unpublish и metadata/attributes changes. Источники: https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/ и https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/ |
| LiveKit multi-region | Self-hosted LiveKit поддерживает distributed multi-region через Redis, region-aware/load-aware node selector и geo/latency-aware DNS; room пока должен помещаться на одном node. Источник: https://docs.livekit.io/transport/self-hosting/distributed/ |
| LiveKit Cloud regions | LiveKit Cloud по умолчанию подключает пользователей к closest edge; region pinning нужен для data residency/compliance, но отключает автоматический failover к ближайшему региону при outage. Источники: https://docs.livekit.io/deploy/admin/regions/ и https://docs.livekit.io/deploy/admin/regions/region-pinning/ |
| Twilio regions/GLL | Twilio прямо описывает, что дальний регион обработки даёт severe latency; Global Low Latency/edge selection выбирает ближайший edge/region для снижения audio latency. Источники: https://www.twilio.com/docs/global-infrastructure/understanding-twilio-regions и https://help.twilio.com/articles/223132167 |
| TURN latency tiers | Twilio Video networking docs дают полезную практическую иерархию: direct UDP is lowest latency, TURN/UDP adds latency, TURN/TLS 443 is degraded because TCP retransmissions add delay. Источник: https://www.twilio.com/docs/video/networking-considerations |
| Межрегиональные latency budgets | ITU-T G.114 рекомендует не превышать 400 ms one-way для planning; Cisco voice/video QoS материалы дают практические цели для realtime voice: latency около 150-300 ms, jitter 20-50 ms, loss до 1%. Источники: https://www.itu.int/dms_pubrec/itu-t/rec/g/T-REC-G.114-200305-I%21%21SUM-HTM-E.htm и https://www.cisco.com/c/en/us/support/docs/quality-of-service-qos/qos-video/212134-Video-Quality-of-Service-QOS-Tutorial.html |
| Китайские RTC-подходы к маршруту | Agora/Shengwang docs делают акцент на last-mile quality callbacks, weak-network fallback to audio-only и geofencing только при regulatory need, потому что фиксирование далёкого региона ухудшает experience. Источники: https://doc.shengwang.cn/api-ref/rtc/rn/API/toc_network и https://docs-legacy.agora.io/en/live-streaming-standard-legacy/region_cpp_rtc?platform=Windows |
| Китайские RTC-подходы к preflight | Shengwang/Agora рекомендует pre-call last-mile probe: через 2 секунды subjective quality score, через ~30 секунд объективные stats: bandwidth, packet loss, jitter, RTT. Источник: https://doc.shengwang.cn/doc/rtc/windows/basic-features/lastmile-quality |
| Китайские RTC-подходы к in-call stats | Shengwang/Agora in-call quality callbacks идут примерно каждые 2 секунды и отдельно отдают uplink/downlink quality, local/remote audio/video stats и state changes. Источник: https://doc.shengwang.cn/doc/rtc/rn/advanced-features/in-call-quality |
| Китайские RTC-подходы к global RTC | Alibaba RTC описывает global real-time intelligent scheduling и 1500+ edge nodes; Tencent TRTC заявляет global deployment optimization для 200+ стран/регионов и weak-network resistance; ZEGO описывает global RTC architecture как multi-cloud, edge nodes и intelligent scheduling. Источники: https://help.aliyun.com/document_detail/2640061.html, https://intl.cloud.tencent.com/zh/products/trtc и https://www.zegocloud.com/blog/build-seamless-experience-with-global-rtc-architecture |
| ByteDance/Volcengine global RTC | Volcengine разбивает глобализацию RTC на signaling globalization и media globalization, описывает nearest edge access, edge aggregation, overlay realtime transport network, multipath failover и edge-side signaling logic. Источник: https://developer.volcengine.com/articles/7599493615001272370 |
| Китайские RTC-подходы к traffic control | ZEGO traffic control dynamically adjusts video publish bitrate based on local/remote network state; без traffic control при нехватке bandwidth ожидаются freezes/stutter. Источник: https://doc-zh.zego.im/real-time-voice-web/communication/traffic-control |
| GitHub/LiveKit config | LiveKit OSS config прямо содержит region-aware node selector, TURN/UDP/TLS ports, short-lived TURN credentials, congestion control, TCP/TURN fallback и room/node limits. Источник: https://github.com/livekit/livekit/blob/master/config-sample.yaml |
| GitHub/Jitsi Octo | Jitsi решает глобальные конференции через region-based bridge selection и Octo/cascaded bridges: participant gets regional bridge, bridges связаны между собой. Источники: https://jitsi.github.io/handbook/docs/devops-guide/devops-guide-region/ и https://forger.sitiv.fr/apitech/jitsi-videobridge/src/commit/9e64a82087cdbe68b60818a9980f7bb6e19cf9ec/doc/octo.md?display=source |
| GitHub/mediasoup | mediasoup показывает низкоуровневую модель: router как conference room, SFU forwards RTP, выбирает spatial/temporal layers по network capability; для масштабирования есть multiple routers/workers/hosts и `pipeToRouter()`. Источник: https://mediasoup.org/documentation/v3/scalability/ |
| GitHub/Janus | Janus VideoRoom реализует publish/subscribe SFU room; Janus полезен как gateway/SIP/плагины, но для нашего mobile-first E2EE app-to-app продукта тяжелее, чем LiveKit. Источник: https://janus.conf.meetecho.com/docs/videoroom.html |
| LiveKit Agents/captions | LiveKit Agents может входить в LiveKit room как realtime participant, строить STT/LLM/TTS pipelines и публиковать realtime transcriptions через text streams topic `lk.transcription`; LiveKit прямо называет realtime translation use case. Источники: https://docs.livekit.io/agents/ и https://docs.livekit.io/agents/multimodality/text/ |
| OpenAI realtime/audio | OpenAI Audio API поддерживает transcription, streaming transcription events и translation endpoint; Realtime API поддерживает input audio transcription и turn detection. Источники: https://platform.openai.com/docs/api-reference/audio и https://platform.openai.com/docs/api-reference/realtime |

## Signal-подход к безопасности пользователя

Signal-подобная модель для звонков означает не только E2EE media. Нужно защищать пользователя от раскрытия IP, лишних server metadata, call spam и тихих identity changes.

Дополнительные источники Signal:

| Тема | Вывод |
|------|-------|
| IP/location privacy | Signal пишет, что P2P-звонок снижает latency, но может раскрыть IP удалённому peer; поэтому для входящих от не-контактов используется relay, а пользователь может включить relay for all calls. Источник: https://signal.org/blog/signal-video-calls/ |
| SFU + E2EE | Для масштабируемых group calls Signal использует SFU, потому что selective forwarding совместим с E2EE, в отличие от server mixing. Источник: https://signal.org/blog/how-to-build-encrypted-group-calls/ |
| Rekey on join/leave | Signal описывает правило: joining user не должен читать прошлое media, leaving user не должен читать будущее media; клиенты ротируют ключи при join/leave. Источник: https://signal.org/blog/how-to-build-encrypted-group-calls/ |
| Group call membership | Signal group calls требуют, чтобы участники состояли в одной группе; group calls поддерживаются до 75 участников. Источники: https://support.signal.org/hc/ru/articles/360052977792-%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%BE%D0%B2%D1%8B%D0%B5-%D0%B2%D0%B8%D0%B4%D0%B5%D0%BE%D0%B7%D0%B2%D0%BE%D0%BD%D0%BA%D0%B8 и https://signal.org/blog/call-links/ |
| Safety number | Signal safety number позволяет проверить безопасность messages and calls; для verified safety number любое изменение требует manual approval перед отправкой новых сообщений. Источник: https://support.signal.org/hc/en-us/articles/360007060632-What-is-a-safety-number-and-why-do-I-see-that-it-changed |
| Metadata minimization | Signal sealed sender и private profile материалы подчёркивают минимизацию данных, доступных сервису, и сокрытие части metadata вроде "кто кому пишет". Источники: https://signal.org/blog/sealed-sender/ и https://signal.org/blog/message-requests/ |
| Unknown caller UX | Signal message requests улучшают calls UX: если caller не в контактах, телефон не звонит, пока request не принят. Источник: https://signal.org/blog/message-requests/ |

Что нужно улучшить в нашем плане:

| Риск для пользователя | Требование |
|-----------------------|------------|
| Remote peer узнаёт IP или примерную локацию | В v1 не делать direct P2P вообще: все calls идут через LiveKit/TURN relay/SFU. Добавить user setting `Всегда скрывать IP в звонках`, который включает relay-only/relay-preferred даже если позже появится P2P. |
| RTC-сервис видит лишнюю identity metadata | LiveKit participant identity должен быть opaque: `callParticipantId`/`deviceScopedParticipantId`, без username/display name/phone/email/avatar. Маппинг account/device -> participant остаётся только в Go API. |
| Server или RTC edge может прочитать media | Media E2EE обязателен до public calls. Go API и LiveKit получают только ciphertext media и encrypted key offers. Нельзя shipping video/audio calls без работающего E2EE, кроме явно помеченного internal debug build. |
| Invited-but-not-joined participant получает прошлое | Media key выдаётся только фактически accepted/joined devices, не всем invited participants. |
| Left participant читает будущее | Rekey при join/leave/device switch; старый key выводится из обращения после короткого grace window. |
| Safety number изменился незаметно | Если remote identity/safety number изменился и контакт был verified, call setup/key exchange блокируется до manual approval. Для unverified контакта показывать non-blocking warning на call screen до начала media. |
| Неизвестный пользователь может сразу звонить full-screen | Unknown/non-contact caller сначала создаёт message/call request. Full-screen incoming call и high-priority call push разрешены только для accepted contacts/chats или после явного разрешения. |
| Call push раскрывает caller/content третьей стороне | Push остаётся wake-only: `type=incoming_call`, opaque `call_id`/collapse id, без имени caller, номера, аватара, room, ciphertext или token. User-visible copy строится на устройстве после fetch/decrypt. |
| OS call log/cloud sync раскрывает контакты | Если позже используем Android Telecom/iOS CallKit, дефолтный system log label должен быть generic (`Glagolitsa call`/`Signal user`-style), реальные имена только opt-in. Не писать в системный call log без явного решения. |
| Diagnostics превращаются в слежку | Call diagnostics по умолчанию локальные и redacted: без IP peer, без full ICE candidates, без TURN credentials, tokens, media keys, usernames, display names. Upload только по consent/debug flag. |
| LiveKit egress/recording нарушает ожидание приватности | Egress/recording выключены на сервере для private calls. Если когда-нибудь появится recording, нужен явный participant-visible indicator и отдельная политика. |
| Abuse/rate-limit требует sender identity | Для звонков нужен rate limit и blocklist на Go API. Для unknown requests можно использовать delivery-token-like permission: право звонить появляется после accepted chat/profile exchange, а не просто по знанию user id. |

Security invariants:

1. Звонки не реализуются внутри Go API: Go API является control plane, отдельный RTC-сервис переносит только encrypted media.
2. Remote user не должен видеть IP другого пользователя.
3. RTC-сервис не должен видеть display name, username, phone/email, avatar или plaintext profile.
4. Server-side call history хранит минимум: ids, status, timestamps, duration bucket/seconds по продуктовой необходимости. Network-quality raw stats не пишутся в user history.
5. Media keys живут на клиентах; server хранит только encrypted key offers.
6. Verified safety number changes блокируют новый call media setup до approval.
7. Unknown callers не получают ringing/full-screen/high-priority path без accepted request.
8. Push, logs, metrics и diagnostics не содержат plaintext, ciphertext bodies, keys, tokens или stable social graph labels.

## Group-ready архитектура

Да, звонки можно и нужно сделать так, чтобы потом 1:1-звонок в групповом чате мог стать групповым без пересоздания всей системы. Главный принцип: **звонок не должен быть парой `caller/callee`; звонок должен быть room/session, а люди в нём должны быть rows в `call_participants`.**

Текущий код частично готов: таблица `call_participants` уже есть. Но верхний уровень пока 1:1:

| Место | Сейчас | Что плохо для будущих group calls |
|-------|--------|------------------------------------|
| `model.CreateCallRequest` | `callee_id` | нельзя начать звонок, привязанный к group chat без конкретного callee |
| `model.CallSession` | `caller_id`, `callee_id` | звонок представлен как пара, а не как комната с N участниками |
| `calls` table | `caller_id`, `callee_id` NOT NULL | DB будет мешать invite третьего участника как first-class participant |
| `AcceptCall`/`RejectCall` | только `callee` может accept/reject | в group call каждый invited participant принимает/отклоняет своё участие |
| `applyCallPresence` | participants = caller + callee | presence не видит N участников |
| call history query | `WHERE caller_id = $1 OR callee_id = $1` | история group call должна идти через `call_participants` или `chat_id` membership |

Целевая модель:

```text
calls
  id
  chat_id                    nullable for future call links; required for DM/group chat calls
  started_by_user_id
  call_scope                 dm | group | ad_hoc | link
  call_type                  audio | video
  status                     ringing | active | ended | ...
  livekit_room_id
  low_bandwidth_mode
  created_at / connected_at / ended_at

call_participants
  call_id
  user_id
  device_id
  role                       starter | invited | joined | listener | moderator
  invite_state               invited | ringing | accepted | rejected | missed | joined | left | removed
  media_state                audio_only | video_low | video_normal | muted
  joined_at / left_at

call_invites
  call_id
  invited_user_id
  invited_by_user_id
  state                      pending | accepted | declined | expired | revoked
  created_at / expires_at
```

Для 1:1-звонка это просто `calls` + два `call_participants`. Для group chat call это тот же `calls`, но `chat_id` указывает на group chat, а участников может быть сколько угодно в рамках лимита. То есть 1:1 и group call отличаются не архитектурой, а policy и UI.

### Сценарий: двое говорят и зовут третьего

Если звонок начат внутри group chat:

1. User A начинает звонок в `chat_id=group-1`.
2. Сервер создаёт `call_id` и LiveKit room один раз.
3. User B принимает и входит в тот же room.
4. A или B нажимает `Позвать в звонок`.
5. Сервер проверяет, что User C состоит в этом group chat и не заблокирован policy.
6. Сервер создаёт `call_invites(call_id, invited_user_id=C, invited_by_user_id=A/B)`.
7. C получает wake-only push / websocket event.
8. C принимает invite, получает short-lived LiveKit token на тот же `livekit_room_id`.
9. После фактического join клиенты делают rekey, чтобы C не мог расшифровать media до своего join.
10. Когда C выходит, оставшиеся участники снова делают rekey, чтобы C не мог читать будущий media.

Важно: room не меняется. Мы не переносим звонок из 1:1-room в group-room. Мы сразу создаём room как `call_id`, который способен вместить N participants.

Если звонок начат в DM, а нужно позвать третьего:

| Вариант | Решение |
|---------|---------|
| Безопасный v1 | Предложить создать group chat и начать новый group call там. Это проще для authorization, history и safety number UX. |
| Future ad-hoc | Разрешить `call_scope=ad_hoc` и `call_invites`, но тогда нужно отдельно решать membership, name/profile privacy, blocklist, history visibility и encrypted key distribution. |

Рекомендация: **сначала поддержать group calls только внутри уже существующих group chats**. Это ближе к Signal-модели: все участники уже состоят в одной группе, сервер может проверить membership, а UX понятный.

### Permissions и роли

Для будущих больших звонков надо не только "все говорят", но и режимы:

| Role | LiveKit permission | Применение |
|------|--------------------|------------|
| joined speaker | `can_publish=true`, `can_subscribe=true`, `can_publish_data=true` | обычный участник звонка |
| listener/viewer | `can_publish=false`, `can_subscribe=true`, `can_publish_data=true` | будущий режим больших комнат или слабой сети |
| moderator | app role + backend roomAdmin action | mute/remove/invite approval |
| invited pending | no token yet | человек приглашён, но не в room |

LiveKit умеет update participant permissions, включая grant/revoke publish; revoke `CanPublish` автоматически unpublishes tracks. Это полезно для future moderation, push-to-talk/listener mode и принудительного video-off при перегрузке.

### UI для group calls

Первый group-ready UI не обязан быть полноценным Zoom. Достаточно заложить компоненты:

1. Bottom sheet `Участники звонка`.
2. `Позвать в звонок` внутри group chat call.
3. Active speaker row: кто сейчас говорит.
4. Маленькие tiles только для участников с video; audio-only участники показываются avatar/name из локального чата, не из LiveKit metadata.
5. Если сеть плохая, сначала скрывать remote video неактивных speaker, потом все video, оставляя audio.

LiveKit events `ActiveSpeakersChanged`, track publish/unpublish и connection quality подходят для этого UI без собственной media-системы.

### E2EE и rekey для group calls

Правило должно быть Signal-like:

1. Invite сам по себе не даёт media key.
2. Media key выдаётся только после accept/join authorization.
3. Новый participant не может decrypt прошлое.
4. Ушедший/removed participant не может decrypt будущее.
5. Rekey срабатывает на join, leave, remove, device switch и verified safety number change.
6. Key offers идут per-device через существующий libsignal path, server хранит только ciphertext.

В v1 для group calls можно использовать простой per-sender media key rotation:

```text
participant joins/leaves
  -> каждый оставшийся publishing client генерирует новый media key
  -> рассылает encrypted key offers всем current joined devices
  -> через короткое grace window начинает шифровать новым key
```

Это дороже, чем sophisticated group key agreement, но понятно, безопасно и хорошо ложится на существующий `call_key_offers`.

### Data model migration guidance

Чтобы не закопаться в будущем, ближайшая реализация 1:1 должна быть "group-ready":

1. Добавить `chat_id`, `started_by_user_id`, `call_scope` в `calls`.
2. Сделать `callee_id` nullable/deprecated или оставить только как denormalized legacy field для DM history.
3. Перевести authorization/history/presence на `call_participants`.
4. Добавить `invite_state`, `role`, `media_state` в `call_participants`.
5. Добавить `call_invites`.
6. API переименовать концептуально:
   - `POST /api/calls` принимает `chat_id` и optional `initial_invitee_ids`;
   - `POST /api/calls/{id}/invites`;
   - `POST /api/calls/{id}/invites/{invite_id}/accept`;
   - `POST /api/calls/{id}/participants/me/leave`;
   - `POST /api/calls/{id}/participants/{user_id}/remove` для moderator/admin.
7. Старый `callee_id` API можно оставить wrapper-ом: DM call превращается в `chat_id=dmChatId`, `initial_invitee_ids=[callee]`.

### Лимиты

Начальные лимиты должны быть жёсткими:

| Stage | Лимит | Причина |
|-------|------|---------|
| v1 audio group call | 4-8 joined users | проще debug, меньше bandwidth/CPU |
| v1 video group call | 2-4 video publishers, остальные audio-only | слабые сети/VPN и мобильные устройства |
| later | 16+ audio / 8+ video | после telemetry и серверного capacity testing |

Signal сейчас поддерживает group calls до 75 участников, но это не значит, что нам надо начинать с 75. Нам важнее правильная модель и стабильность на VPN.

## Междугородний и межрегиональный звонок

Здесь "междугородний" означает app-to-app звонок между пользователями в разных городах, странах или сетевых регионах. Это не PSTN-тариф и не SIP-телефония. Главная проблема такого звонка: media может пойти через слишком далёкий RTC edge или TURN relay, и тогда даже хороший домашний интернет будет звучать плохо из-за лишних round trips.

Принцип: **выбирать media region по измеренной сетевой близости, а не по географии профиля, номеру телефона, языку, городу в анкете или GPS.** VPN делает IP-геолокацию ненадёжной, а приватность пользователя важнее точной карты.

### Что берём из китайнета и GitHub

| Источник | Как они решают | Что берём нам |
|----------|----------------|---------------|
| Shengwang/Agora | `lastmile` preflight перед звонком и in-call callbacks каждые ~2 секунды: uplink/downlink quality, stats, local/remote audio/video state | Делаем лёгкий preflight/probe cache и постоянный stats loop; video offer только после доказанной сети |
| Tencent TRTC | глобальная RTC-сеть, оптимизация регионов, weak-network алгоритмы, заявленный фокус на удержании звонка при сильной потере пакетов | Не обещать магию без собственной глобальной сети, но повторить продуктовый принцип: audio survives first, video degrades first |
| Alibaba RTC | global intelligent scheduling + edge nodes + audio 3A + weak-network algorithms | `CallRouteSelector` должен стать обязательной частью control plane, а не nice-to-have |
| ZEGO | multi-cloud/edge/intelligent scheduling для 200+ стран, traffic control с динамической настройкой publish bitrate | Route policy + traffic control должны работать вместе: сначала правильный edge, потом адаптация bitrate/layers |
| Volcengine/ByteDance | nearest edge access, edge aggregation, overlay realtime transport network, multipath failover, signaling logic pushed closer to edge | Для future multi-region нельзя ограничиваться только media SFU; signaling, websocket events и token issuing тоже должны иметь региональную стратегию |
| LiveKit OSS | distributed setup через Redis, region-aware/load-aware node selector, TURN/UDP/TLS, congestion control, fallback knobs | Базовый путь для нас: LiveKit + Redis + region-aware selector; свою media-систему не пишем |
| Jitsi Octo | участники могут получать bridge в своём регионе, bridges каскадируются между собой | Future v3 для больших международных group calls; в v1/v2 не тащим, потому что это резко усложняет E2EE/rekey/ops |
| mediasoup | room/router model, SFU не транскодирует, а выбирает spatial/temporal layers; масштабирование через routers/workers/hosts | Подтверждает нашу модель `room + participants + tracks`; video должен быть layer-based, а не “один поток всем” |
| Janus | VideoRoom как publish/subscribe SFU; сильная сторона - gateway/SIP/plugins | Не основной путь для private app calls; держать в голове, если позже понадобится SIP/PSTN bridge |

Вывод: лучшие системы делают три слоя, и нам надо копировать именно слои, а не бренд SDK.

1. **Route selection до звонка:** какой RTC/TURN region даст меньше боли.
2. **Last-mile/in-call measurement:** что реально происходит у клиента сейчас.
3. **Traffic control:** мгновенно резать видео, подписки и bitrate, чтобы голос жил.

Открытые GitHub-проекты показывают ещё один важный предел: если room живёт на одном SFU node, международный group call всегда будет компромиссом. Jitsi Octo/cascaded bridges решают это сложнее, но дороже по эксплуатации. Поэтому наш путь: сначала один правильно выбранный region на room, потом multi-region cluster, и только после реальной необходимости думать о cascaded SFU для больших международных групп.

Отдельная мысль из китайских global RTC архитектур: международный звонок это не только media path. Если signaling идёт в один центральный датацентр, пользователь может долго входить в комнату, поздно получать call events и плохо переживать failover. Поэтому `CallRouteSelector` должен со временем выбирать не только `livekit_url`, но и ближайший websocket/signaling ingress. Go API остаётся источником правды, но edge может проксировать звонковые события и preflight быстрее.

### ИИ-субтитры и перевод: окно на будущее

В ближайшую реализацию ИИ не входит. Сейчас нужно только оставить для него архитектурное окно: не выбрать такие media/data модели, которые потом сделают live captions и перевод субтитрами невозможными или небезопасными.

ИИ-субтитры полезны особенно для международных звонков, но архитектурно это отдельный optional layer поверх звонка, а не часть критического audio path. Если переводчик падает, звонок должен продолжать жить.

Главный принцип: **ИИ-перевод не должен тайно ломать E2EE.** Если audio отдаётся в cloud STT/LLM/translation, это уже дополнительный получатель голоса. Пользователь должен видеть это явно и включать осознанно.

Что делаем сейчас, без реализации ИИ:

1. Не завязываем звонок только на audio/video tracks; оставляем encrypted realtime data/text lane для будущих caption events.
2. Не логируем и не сохраняем plaintext media/transcript fields в call history.
3. Держим participant identity opaque, чтобы будущие subtitles не тащили username/display name в RTC service.
4. Закладываем rekey на join/leave так, чтобы будущие caption keys ротировались вместе с call keys.
5. Не добавляем STT/LLM provider, agent service, transcript storage или UI captions в первый slice.

| Режим | Как работает | Приватность | Когда использовать |
|-------|--------------|-------------|--------------------|
| `OFF` | captions/translation выключены | максимальная | дефолт для private calls |
| `LOCAL_CAPTIONS` | устройство transcribes свой микрофон on-device и шлёт encrypted caption segments другим участникам | лучший вариант, если качество модели достаточно | будущий privacy-first режим |
| `CLIENT_CLOUD_OWN_AUDIO` | каждый клиент отправляет только свой microphone stream/chunks в STT/translation provider и публикует encrypted text в call | AI видит только речь того пользователя, который включил captions | реалистичный первый public режим |
| `SERVER_AGENT_TRANSLATOR` | отдельный LiveKit Agent/Bot joins room, слушает tracks, переводит и публикует text streams | AI/server слышит участников, поэтому нужен явный opt-in/indicator | enterprise/debug/явные переводческие комнаты |

Рекомендация для будущей реализации Glagolitsa: **не начинать с server agent, который слушает всех.** Если дойдём до ИИ, первым public режимом должен быть `CLIENT_CLOUD_OWN_AUDIO`: каждый участник сам решает, можно ли его речь отправлять в STT/translation. Остальные получают только уже зашифрованный текст.

Поток данных для privacy-first captions:

```text
speaker device
  mic PCM after local capture
  -> VAD/chunker 1-3s
  -> STT source language
  -> translation target languages
  -> caption segment
  -> encrypt per call participant/device
  -> deliver as realtime text/data event

receiver device
  decrypt caption segment
  -> align by speaker + segment time
  -> render subtitles
```

Future слой, который сейчас только резервируем в архитектуре:

```text
CallCaptionTranslator
  local microphone fork
  VAD / chunker
  speech-to-text adapter
  translation adapter
  caption segment encryptor
  caption delivery adapter
  local subtitle renderer

CaptionDelivery
  encrypted LiveKit data/text stream if verified safe
  or existing app E2EE message/data path
```

`CallMediaEngine` продолжает отвечать за голос/видео. `CallCaptionTranslator` получает только копию локального микрофона или explicit subscribed track в agent-mode. Он не должен управлять звонком, ICE, камерой, room lifecycle или quality mode.

Caption segment:

```json
{
  "type": "call_caption_segment",
  "call_id": "opaque",
  "speaker_participant_id": "opaque",
  "segment_id": "monotonic-or-random",
  "source_language": "ru",
  "target_language": "en",
  "is_final": false,
  "start_ms": 123400,
  "end_ms": 125100,
  "text_ciphertext": "...",
  "model_family": "local|openai|deepgram|other",
  "created_at_ms": 125200
}
```

`model_family` можно хранить только coarse/debug. Не логировать plain text, audio chunks, prompts или full provider responses.

Consent и UX:

1. По умолчанию ИИ-субтитры выключены.
2. Включение captions объясняет, куда пойдёт аудио: `На устройстве` или `Через облачный ИИ`.
3. Если участник включает cloud captions для своей речи, другие видят индикатор: `Анна включила ИИ-субтитры для своей речи`.
4. Если включается server/agent translator, все участники видят: `ИИ-переводчик присоединился к звонку`.
5. Unknown/non-contact calls не должны автоматически включать ИИ.
6. Для группового звонка каждый участник выбирает preferred subtitle language локально.
7. Субтитры не сохраняются в историю по умолчанию. Сохранение transcript - отдельный явный opt-in для всех или policy для enterprise-режима.

Короткие UI-тексты:

| Ситуация | Текст |
|----------|-------|
| Включение локальных субтитров | `Субтитры обрабатываются на устройстве.` |
| Включение cloud captions | `Ваш голос будет отправляться в ИИ для субтитров.` |
| Перевод включён | `Переводим субтитры на русский.` |
| ИИ отстаёт | `Субтитры задерживаются из-за сети.` |
| ИИ недоступен | `Субтитры временно недоступны. Звонок продолжается.` |

Security requirements:

| Риск | Требование |
|------|------------|
| Cloud provider получает голос без согласия | отправлять только речь участника, который включил cloud captions; для server agent нужен явный visible join |
| Plaintext transcript попадает в Go API/LiveKit logs | captions передавать encrypted; server logs redacted; provider responses не логировать |
| Caption text становится новой утечкой metadata | не сохранять по умолчанию; diagnostics только counters/latency/error codes |
| AI hallucination меняет смысл | UI должен отличать subtitles от verified speech; для final captions показывать аккуратно, interim менее уверенно |
| Перевод ломает звонок по CPU/сети | captions имеют lower priority than audio; при слабой сети снижать частоту/interim updates или отключать translation |
| Group rekey | caption encryption keys ротируются вместе с media/call participant keys на join/leave |
| Provider lock-in | `SpeechToTextProvider` и `TranslationProvider` должны быть adapter interfaces |

ИИ-субтитры нельзя считать источником правды для юридически значимых решений. Это вспомогательная функция разговора.

Caption traffic должен уступать голосу:

1. В `EMERGENCY_VOICE` выключать interim captions, оставлять только редкие final segments или полностью pause translation.
2. В `VOICE_ONLY` разрешать final captions, но без word-by-word streaming, если uplink слабый.
3. В `VIDEO_LOW`/`VIDEO_NORMAL` можно включать interim captions и более частую синхронизацию.
4. Если STT latency > 5-7 секунд, UI показывает задержку и перестаёт рисовать captions как live.
5. Если provider errors идут подряд, captions выключаются до ручного retry; звонок не reconnect-ится из-за AI.

Data model для будущего, не для текущей миграции:

```text
call_caption_settings
  call_id
  participant_id
  mode                         off | local | client_cloud | server_agent
  spoken_language              auto | ru | en | ...
  subtitle_language            ru | en | ...
  save_transcript              false by default
  created_at / updated_at

call_ai_sessions
  id
  call_id
  mode
  provider_family              local | openai | deepgram | other
  status                       starting | active | degraded | ended
  started_by_participant_id
  visible_to_participants      true
  started_at / ended_at
```

`call_caption_settings` и `call_ai_sessions` не добавлять в ближайшие migration. Это описание контракта на будущее. `call_caption_segments` в DB не заводить для private calls по умолчанию. Если появится сохранение transcripts, это отдельная encrypted storage feature с отдельным consent и retention.

### Что ломает межгород

| Проблема | Почему важно | Что делаем |
|----------|--------------|------------|
| Далёкий SFU | оба клиента тащат media до одного room node; если node далеко, растёт mouth-to-ear delay | выбираем room region через `CallRouteSelector` до выдачи token |
| TURN hairpin | клиент в Казани через VPN в Германии может попасть на TURN в Москве и потом на SFU в Европе | TURN и SFU должны быть регионально согласованы; если selected candidate `relay`, relay должен быть рядом с выбранным edge |
| TCP/TLS relay | TURN/TLS 443 спасает connectivity, но TCP retransmission добавляет задержку и jitter | на `relay/tcp` или `relay/tls` оставлять audio-first и сильно ограничивать video |
| VPN exit не там, где пользователь | "ближайший по IP" может быть не ближайшим по реальной задержке | принимать решение по probe RTT до RTC/TURN endpoints |
| Первый участник создал room не там | в LiveKit room живёт на выбранном node; поздний перенос active room дорогой | room region выбирается до первого join, по caller + target chat/callee hints |
| Compliance/data residency | иногда нельзя гнать трафик через любой регион | `region_policy` должен уметь pin/deny regions, даже если это хуже по latency |

### Route model

Добавить в control plane понятие маршрута звонка:

```text
call_route
  call_id
  selected_region             ru-msk | ru-spb | eu-fra | tr-ist | ...
  selected_sfu_node_id         opaque node id, nullable until allocation
  route_class                 local | intercity | international | forced_region | degraded
  region_policy               auto | pinned | deny_list | compliance
  selection_reason            lowest_p95_rtt | compliance | fallback | single_region
  caller_probe_rtt_ms
  invitee_probe_rtt_ms        nullable until invitee answers
  selected_turn_region
  selected_at
```

Эти поля не должны хранить IP, GPS или точный город пользователя. Достаточно region id и coarse RTT buckets. Для приватности diagnostics пишем как `rtt_bucket=100-200ms`, `candidate=relay_udp`, `region=eu-fra`.

Client route hints:

```json
{
  "hint_version": 1,
  "network_kind": "wifi|cellular|vpn|unknown",
  "vpn_detected": true,
  "regions": [
    {"id": "ru-msk", "wss_rtt_bucket_ms": "50-100", "turn_udp": "ok", "turn_tls": "ok"},
    {"id": "eu-fra", "wss_rtt_bucket_ms": "100-200", "turn_udp": "blocked", "turn_tls": "ok"}
  ],
  "measured_at": "client monotonic timestamp or coarse server time"
}
```

Нельзя отправлять raw IP, GPS, SSID, carrier IMSI, VPN provider name или полный traceroute. Нам нужна не слежка за маршрутом, а грубая оценка: какой edge живой и какой transport доступен.

### Как выбирать регион

В v1, если у нас один RTC-сервер, выбора почти нет. Тогда архитектура должна честно ставить `selected_region=primary`, `route_class=single_region`, а качество спасать audio-first, TURN/UDP 443, TURN/TLS 443 и агрессивным video-off.

В v2 с несколькими RTC-регионами:

1. Клиент держит короткий cache последних probe RTT до `rtc` и `turn` endpoints: например `ru-msk`, `eu-fra`, `tr-ist`.
2. При создании звонка caller отправляет не IP, а `rtc_route_hints`: region -> RTT bucket/protocol reachability.
3. Для DM сервер берёт последние hints caller + callee devices, если они свежие; если callee hints нет, выбирает регион по caller, но может сделать conservative policy.
4. Для group chat сервер выбирает регион по median/minimax RTT: минимизировать худшую задержку для большинства joined/invited users, а не только для starter.
5. Если есть compliance/pinning, сначала применить legal policy, потом latency policy.
6. Если выбранный region unhealthy или перегружен, failover в следующий регион и сразу выставить `route_class=degraded`, чтобы клиент не предлагал video раньше времени.
7. LiveKit token получает конкретный `livekit_url`/region и room id; участник не выбирает room сам.

Практическая scoring-функция:

```text
score(region) =
  p95_rtt_to_caller
  + p95_rtt_to_invitees_weighted
  + turn_penalty_if_only_tls
  + load_penalty
  + compliance_penalty_or_infinite
```

Для 1:1 можно выбирать регион с минимальным `max(caller_rtt, callee_rtt)`, чтобы не было ситуации "одному идеально, второму ад". Для group call лучше минимизировать p75/p90 по активным участникам и отдельно ограничивать video для дальних участников.

Для international group call:

1. Не пытаться сделать всем идеально, это физически невозможно.
2. Выбрать room region по большинству активных speaker/listener, а дальним участникам дать более жёсткий subscription cap.
3. Если группа расколота на два больших региона, future option - cascaded SFU/Octo-like topology, но только после отдельного дизайна E2EE, rekey и observability.
4. Если звонок маленький и участники сильно далеко, приоритет остаётся у голоса, а не у справедливого видео.

### Политика качества для межгорода

Междугородний звонок не обязан иметь маленький RTT. Поэтому thresholds должны учитывать route class:

| Route class | Audio target | Video policy |
|-------------|--------------|--------------|
| `local` | обычные thresholds | video offer после 20-30s good stats |
| `intercity` | терпим RTT выше, но строже следим за jitter/loss | video offer только после 30-45s good stats |
| `international` | audio-first, Opus wideband если loss низкий; иначе narrowband/emergency | video по умолчанию не предлагать, только manual enable через `VIDEO_PROBE` |
| `forced_region` | объяснить degraded route в diagnostics/support, не пользователю | video suppressed, пока stats не excellent |
| `degraded` | держать только голос, быстро ICE restart/relay retry | video off до стабильного recovery window |

Для голоса важнее не абсолютный RTT, а чтобы не было рваного jitter/loss. Если RTT 350-500 ms, но loss низкий и jitter стабильный, разговор будет с паузами, но живой. Если RTT 120 ms, но loss 15% и jitter скачет, надо резать видео и спасать Opus.

Дополнительные межгородние правила:

1. Если estimated one-way delay близок к 400 ms или RTT > 800 ms, не предлагать video автоматически.
2. Если route использует `relay/tls`, считать video unsafe до доказанного sustained excellent состояния.
3. Для international/VPN route поднятие видео требует более длинного `VIDEO_PROBE`, минимум 45-60 секунд healthy window.
4. Если два участника рядом друг с другом, но оба далеко от primary region, это главный сигнал, что нужен local/regional RTC edge.
5. Если один участник далеко от всех, не ухудшать звонок всем: он получает lower remote video/no video, но общий room остаётся в регионе большинства.

### Deployment для межгорода

Минимальная стратегия:

1. Один primary RTC region на новом сервере.
2. Сразу делать DNS и config так, будто регионов будет несколько: `rtc-ru-msk.<domain>`, `turn-ru-msk.<domain>`, общий `rtc.<domain>` как latency/geo-aware entrypoint позже.
3. TURN credentials должны быть region-scoped и short-lived.
4. Metrics должны иметь labels: `rtc_region`, `turn_region`, `route_class`, `candidate_type`, `candidate_protocol`, `vpn_detected`, `network_mode`.
5. Не хранить raw IP/user location в call history.

Следующая стратегия:

1. Добавить второй RTC/TURN region там, где field diagnostics покажет реальные маршруты пользователей, а не там, где кажется красиво на карте.
2. Включить LiveKit distributed multi-region: Redis, homogeneous nodes, region-aware selector, region-aware DNS/load balancer.
3. Health checker должен проверять не только `/health`, но и synthetic WebRTC/TURN path: WSS connect, UDP reachability, TURN/UDP allocation, TURN/TLS fallback.
4. Для planned maintenance использовать draining: active rooms не рубить, новые rooms не создавать на draining node.

### UX для межгорода

Пользователю не нужно видеть "Frankfurt p95 RTT 220 ms". Ему нужны короткие действия:

| Ситуация | Текст |
|----------|-------|
| Дальний маршрут, голос живой | `Маршрут дальний. Сохраняем стабильный голос.` |
| Видео пока опасно | `Видео может ухудшить звонок. Пока держим голос.` |
| VPN/TURN-TLS сильно портит маршрут | `Сеть тянет только голос. Другой VPN может улучшить качество.` |
| Регион недоступен и был failover | `Маршрут нестабилен. Переподключаемся.` |

Не надо показывать город/страну edge в обычном UI. Это support/debug информация.

## Целевая архитектура

```
Android / будущий iOS client
  CallScreen
  CallController
  CallMediaEngine
    LiveKit Room
    microphone track
    optional camera track
    E2EE key provider
    stats collector
  CallQualityController
    path signals
    LiveKit connection quality
    WebRTC stats
    route class
    hysteresis policy
    user notices
  Future CallCaptionTranslator
    optional STT/translation
    encrypted caption delivery
    subtitle renderer

Go API
  call metadata
  call authz
  chat-scoped call membership
  call invites
  token issuing
  short-lived ICE/TURN config
  route/region selection
  encrypted key offers
  future encrypted caption routing/settings
  LiveKit webhooks

RTC edge
  regional LiveKit SFU
  Redis
  regional TURN/UDP 443
  regional TURN/TLS 443
  WebRTC UDP range
  WSS signaling
```

Ответственность компонентов:

| Компонент | Ответственность |
|-----------|-----------------|
| `CallController` | Product lifecycle звонка: create, ring, accept, reject, end, history, active UI state. Он не должен управлять тонкими media adaptation details. |
| `CallMediaEngine` | Platform media implementation: подключиться к LiveKit, публиковать микрофон, публиковать/снимать камеру, настраивать encoding, ставить E2EE key, управлять audio route, отдавать media stats/events. Сначала Android, позже iOS. |
| `CallQualityController` | Чистая policy engine: превратить stats в `CallNetworkMode`, принять решение downgrade/upgrade, debounce UI notices, вызвать camera off/on suggestions. Большая часть должна жить в common Kotlin, платформенными остаются adapters. |
| Future `CallCaptionTranslator` | Зарезервированный optional AI layer: локальная/облачная STT, перевод субтитров, шифрование caption segments и subtitle rendering. Не входит в ближайший slice и не участвует в удержании media path. |
| `CallRouteSelector` | Server-side policy: выбрать RTC/TURN region до выдачи token, учитывая RTT hints, VPN/TURN reachability, load, health, compliance и chat membership. |
| Go calling handler | Call authz, chat membership, invites, room/token issuing, DB state, presence, encrypted key exchange, low-bandwidth flags для участников. |
| LiveKit | SFU, congestion control, simulcast/dynacast, reconnection, media transport. |
| TURN | Last-resort relay для VPN, symmetric NAT, mobile carrier NAT, enterprise firewalls и сетей, где direct UDP не проходит. |

## Режимы сети

Нужен маленький finite state machine. Downgrade должен быть быстрым; upgrade должен быть медленным и требовать стабильного хорошего окна.

| Режим | Назначение | Local publish | Remote subscribe | Поведение для пользователя |
|-------|------------|---------------|------------------|----------------------------|
| `NO_PATH` | Нет validated/media path | ничего | ничего | показываем offline/reconnecting |
| `EMERGENCY_VOICE` | Интернет едва пригоден | только Opus voice, 8-16 kbps target если SDK позволяет, DTX/FEC, без камеры | только audio | "Сеть очень слабая, сохраняем голос" |
| `VOICE_ONLY` | Дефолтный стабильный режим | Opus voice, 16-24 kbps, без камеры | audio, без remote video | обычный аудиозвонок |
| `VIDEO_PROBE` | Проверка, безопасно ли видео | microphone + tiny/low video, 160p-180p, low FPS | low layer preferred | скрытый или короткий переходный режим |
| `VIDEO_LOW` | Слабое, но пригодное видео | microphone + 180p/240p, 10-15 fps, 100-250 kbps | только low layer | видео видно, при необходимости "экономим сеть" |
| `VIDEO_NORMAL` | Здоровая сеть | microphone + 360p/480p, 15-24 fps, 300-700 kbps | adaptive layer | предлагаем/разрешаем нормальное видео |

Начинать нужно с `VOICE_ONLY`. Даже если пользователь нажал "видеозвонок", клиент сначала поднимает аудио, затем входит в `VIDEO_PROBE`, когда media path уже живой. Так камера не помешает первичной стабилизации голоса.

## Политика качества

Входные сигналы, по возможности каждые 2 секунды:

| Сигнал | Источник |
|--------|----------|
| path availability, VPN, Wi-Fi/cellular, metered, captive/constrained | расширенный Android `ConnectivityManager`/`NetworkCapabilities` через `NetworkPathMonitor` |
| LiveKit connection quality | `RoomEvent.ConnectionQualityChanged`, `Participant.connectionQuality` |
| media connection state | LiveKit room/participant events |
| available outgoing/incoming bitrate | WebRTC stats, если LiveKit SDK их отдаёт |
| RTT/current candidate pair | WebRTC stats |
| packet loss и jitter | RTP inbound/outbound stats и LiveKit quality |
| audio concealed samples / jitter-buffer delay, если доступны | WebRTC audio stats |
| selected candidate type/protocol | WebRTC candidate-pair/local-candidate stats; важно для `relay/tcp/tls` |
| user action | пользователь сам включил видео, выключил видео, закрыл notice |

Черновые thresholds для v1. Они намеренно консервативные и должны уточняться по field telemetry.

| Класс | Trigger if sustained | Action |
|-------|----------------------|--------|
| Critical | `Lost` quality, no RTP progress 3-5s, RTT > 1500 ms, packet loss > 20%, или estimated outgoing bitrate < 30 kbps | force `EMERGENCY_VOICE`, сразу снять камеру, запустить reconnect/ICE restart path, показать warning |
| Poor | `Poor` quality, RTT 600-1500 ms, packet loss 8-20%, jitter > 80-120 ms, outgoing bitrate < 120 kbps | войти в `VOICE_ONLY`, предупредить до/во время отключения видео |
| Marginal | RTT 250-600 ms, packet loss 3-8%, outgoing bitrate 120-350 kbps | держать audio; разрешать только `VIDEO_LOW`, если пользователь настаивает и audio metrics приемлемы |
| Good | `Good/Excellent`, RTT < 250 ms, packet loss < 3%, outgoing bitrate > 500 kbps в течение 20-30s | предложить "Включить видео" |
| Excellent | stable good metrics 60s, достаточно CPU/battery | разрешить `VIDEO_NORMAL` |

Hysteresis:

1. Downgrade после 2 плохих samples или сразу на `Lost`.
2. Upgrade только после 10-15 подряд хороших samples.
3. Не показывать одно и то же предупреждение чаще одного раза на смену режима.
4. Если пользователь сам выключил видео, не предлагать его снова в этом же звонке, пока он не откроет video controls.
5. Если приложение дважды автоматически отключило видео за один звонок, suppress automatic video offers до конца звонка.

## VPN и ограниченные сети

Большинство звонков ожидаемо будет идти через VPN. VPN нужно считать отдельным первоклассным типом сети.

Обязательная server/network setup:

1. В production использовать собственные STUN/TURN, а не public Google STUN. Public STUN может ломаться под VPN, DNS filtering или regional restrictions.
2. Отдать LiveKit signaling через `wss://rtc.<domain>` на 443.
3. Отдать TURN/TLS на 443 с корректным certificate на `turn.<domain>`.
4. По возможности отдать TURN/UDP на 443, потому что UDP лучше TCP relay для WebRTC latency/congestion.
5. Держать открытым обычный WebRTC UDP port range для нормальных сетей.
6. `/api/ice/servers` должен возвращать short-lived credentials, а не static env credentials, до любого public launch.
7. Client retry order: normal ICE -> relay preferred после быстрого failure -> relay-only для известных плохих VPN/firewall paths.
8. Локально сохранять selected candidate diagnostics и загружать privacy-safe call diagnostics только по consent/debug flag.
9. Не включать direct P2P path в v1; remote peer IP privacy важнее выигрыша latency. Если P2P появится позже, он должен быть opt-in или разрешён только для trusted contacts при выключенной настройке "всегда скрывать IP".

VPN-specific client behavior:

| Условие | Поведение |
|---------|-----------|
| Android сообщает `TRANSPORT_VPN`, и первый media connection fails | быстрый retry с relay-preferred ICE/TURN. |
| selected candidate is `relay/tcp` или `relay/tls` | cap video до `VIDEO_LOW`, если sustained quality не excellent. |
| DNS resolution fails для RTC/TURN domain | использовать direct-IP fallback pattern как в `ApiClient.android.kt` только если TLS SNI/cert всё ещё совпадают; иначе fail closed и diagnostics. |
| VPN меняется во время active call | снять камеру, сохранить audio, trigger reconnect/ICE restart, потом заново оценить video. |

## Media configuration

Audio:

1. Opus mono.
2. Стартовать с 16-24 kbps voice.
3. Использовать DTX и in-band FEC, если это доступно через SDK/SDP configuration.
4. Держать echo cancellation, noise suppression и auto gain включёнными.
5. Policy всегда prioritizes audio packets over video. Если что-то должно пострадать, первым страдает video.

Video:

1. Не публиковать camera, пока audio не connected и quality controller не разрешил video.
2. Использовать simulcast для camera video, где поддерживается.
3. Включить Dynacast.
4. Использовать adaptive stream/subscriber-layer selection.
5. Начальный camera profile должен быть низким: 320x180 или 320x240, 10-15 fps.
6. Поднимать до 360p/480p только после sustained good stats.
7. При poor/critical network отключать или pause local camera publishing, а не просто бесконечно снижать качество.

LiveKit token media config должен стать versioned:

```json
{
  "policy_version": 1,
  "e2ee": true,
  "audio_first": true,
  "low_bandwidth_auto": true,
  "route_class": "local|intercity|international|forced_region|degraded",
  "selected_region": "ru-msk",
  "video_upgrade_requires_good_seconds": 30,
  "video_downgrade_bad_samples": 2,
  "audio": {
    "codec": "opus",
    "mono": true,
    "bitrate_kbps": 24,
    "emergency_bitrate_kbps": 12,
    "dtx": true,
    "fec": true
  },
  "video_ladder": [
    {"mode": "probe", "width": 160, "height": 90, "fps": 7, "bitrate_kbps": 80},
    {"mode": "low", "width": 320, "height": 180, "fps": 12, "bitrate_kbps": 180},
    {"mode": "normal", "width": 640, "height": 360, "fps": 20, "bitrate_kbps": 500}
  ]
}
```

## UX-правила

Русский текст в UI должен быть коротким и объяснять действие, а не обучать сетевым деталям.

| Ситуация | Текст |
|----------|-------|
| Здоровый аудиозвонок, видео вероятно безопасно | `Интернет стабильный. Можно включить видео.` |
| Видео отключается | `Сеть слабая. Отключаем видео, чтобы сохранить звонок.` |
| Emergency voice | `Сеть очень слабая. Сохраняем только голос.` |
| Reconnecting | `Переподключаемся...` |
| Media path failed | `Не удалось удержать соединение. Попробуйте другой VPN или сеть.` |

Поведение:

1. Не показывать modal для automatic downgrade, если пользователь не находится в video controls; лучше call-surface banner/toast.
2. Video upgrade всегда требует подтверждения пользователя.
3. Video downgrade может быть automatic; выживание audio важнее сохранения camera state.
4. На call screen нужен маленький quality indicator, но не raw loss/RTT numbers по умолчанию.
5. Advanced diagnostics можно открыть в debug builds или support flow.

## Дорожная карта

Подробный рабочий план вынесен отдельно: [calls-implementation-plan.md](calls-implementation-plan.md).

Порядок принципиален:

1. Поднять production-ready RTC edge: LiveKit, Redis, TURN/UDP 443, TURN/TLS 443, WSS, health checks и региональные имена даже для одного сервера.
2. Перевести data model и API с пары `caller/callee` на `call + participants + invites`, сохранив старый 1:1 API как compatibility wrapper.
3. Подключить Android LiveKit media engine для audio-only и считать звонок connected только после реального media path.
4. Включить E2EE media/key exchange до public audio/video calls.
5. Добавить `CallQualityPolicy`: `VOICE_ONLY`, `EMERGENCY_VOICE`, diagnostics и быстрый video-off.
6. Только после стабильного audio добавить video publishing, `VIDEO_PROBE`, `VIDEO_LOW`, `VIDEO_NORMAL` и controlled video upgrade.
7. Добавить websocket call events, incoming-call privacy gate, route hints и multi-region readiness.
8. Групповые звонки включать сначала только внутри existing group chats; ad-hoc/link calls оставить на будущее.
9. ИИ-субтитры не реализовывать в первых phases; оставить encrypted data/text lane и privacy boundaries.

Первый production slice должен доказать только одно: **аудиозвонок держится на слабой сети/VPN лучше, чем текущий polling/token-only каркас**. Видео и ИИ не должны попасть в критический путь первого slice.

## Риски и открытые решения

| Риск | Mitigation |
|------|------------|
| E2EE support details отличаются между LiveKit Android versions | Сначала prototype на current stable SDK; key provider держать за `CallMediaEngine`. |
| TURN/TLS over TCP сохраняет connectivity, но увеличивает latency | Использовать как last resort; держать audio only, если sustained stats не excellent. |
| Static TURN credentials leak | Заменить на short-lived credentials до любого public build. |
| Video auto-off может удивить пользователя | Давать короткий понятный текст и сделать повторное включение video user-confirmed. |
| Polling call state wasteful | Добавить websocket call events рано после media prototype. |
| VPN/DNS failures отличаются по provider | Добавить privacy-safe diagnostics и relay-only retry path. |

## Рекомендуемый первый slice

Минимальный полезный первый slice:

1. Развернуть LiveKit + TURN/TLS + TURN/UDP на новом сервере.
2. Добавить Android LiveKit media engine для audio-only calls.
3. Сделать `markCallConnected` зависимым от реального LiveKit media connection, а не только от успешного token fetch.
4. Добавить `CallQualityPolicy` с `VOICE_ONLY` и `EMERGENCY_VOICE`.
5. Добавить diagnostics snapshots.
6. Только после этого добавлять video publishing и video upgrade/downgrade policy.

Такой порядок сначала доказывает главную цель: стабильный голос на плохой сети. Видео становится адаптивным улучшением, а не зависимостью звонка.
