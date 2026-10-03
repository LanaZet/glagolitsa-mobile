// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"context"
	"net/http"
	"os"
	"time"

	"glagolitsa/server/internal/blobstore"
	"glagolitsa/server/internal/calling"
	"glagolitsa/server/internal/clientupdate"
	"glagolitsa/server/internal/cluster"
	"glagolitsa/server/internal/group"
	"glagolitsa/server/internal/hooks"
	"glagolitsa/server/internal/identity"
	"glagolitsa/server/internal/keys"
	"glagolitsa/server/internal/linkpreview"
	"glagolitsa/server/internal/media"
	"glagolitsa/server/internal/messaging"
	"glagolitsa/server/internal/messaging/ws"
	"glagolitsa/server/internal/metrics"
	"glagolitsa/server/internal/notification"
	"glagolitsa/server/internal/presence"
	"glagolitsa/server/internal/profile"
	"glagolitsa/server/internal/store"
	"glagolitsa/server/internal/sync"
)

type Handler struct {
	frontendOrigin  string
	store           MonolithStore
	identity        *identity.Handler
	profile         *profile.Handler
	messaging       *messaging.Handler
	keys            *keys.Handler
	calling         *calling.Handler
	notification    *notification.Handler
	notificationSvc *notification.Service // diagnostics / push posture (no secrets)
	media           *media.Handler
	preview         *linkpreview.Handler
	groups          *group.Handler
	sync            *sync.Handler
	presence        *presence.Handler
	rateLimiter     *RateLimiter
	hub             *ws.Hub
	metrics         metrics.Interface
	cluster         cluster.Interface
	clientUpdate    clientupdate.Policy
	redisConfigured bool
	metricsEnabled  bool
	mux             *http.ServeMux
}

type Options struct {
	FrontendOrigin string
	Store          MonolithStore
	Blobs          blobstore.Store
	RateLimiter    *RateLimiter
	RedisURL       string
	MetricsEnabled bool
}

func NewHandler(opts Options) http.Handler {
	store.SeedDevData(opts.Store)
	ctx := context.Background()

	m := metrics.Interface(metrics.Noop{})
	if opts.MetricsEnabled {
		m = metrics.Default()
	}

	hub := ws.NewHub(nil)
	hub.SetMetrics(m)
	hub.SetAccessGate(&socketAccessGate{store: opts.Store})
	env := os.Getenv("ENV")
	if env == "" {
		env = os.Getenv("GO_ENV")
	}
	hub.SetOriginPolicy(ws.OriginPolicyFromEnv(opts.FrontendOrigin, env))
	presenceCfg := presence.ConfigFromEnv()
	ephemeral := presence.EphemeralStore(presence.NewMemoryStore(presenceCfg))
	var clusterSvc cluster.Interface
	if opts.RedisURL != "" {
		if rs, err := presence.NewRedisStore(opts.RedisURL, presenceCfg); err == nil {
			ephemeral = rs
		}
		if cs, err := cluster.NewRedisCluster(opts.RedisURL, hub); err == nil {
			clusterSvc = cs
			hub.SetCluster(cs)
		}
	}
	presenceSvc := presence.NewService(ephemeral, opts.Store, opts.Store, hub, presenceCfg)
	hub.SetConnectionTracker(presenceSvc)

	notificationSvc := notification.NewService(opts.Store, notification.AdaptersFromEnv())
	notificationSvc.SetMetrics(m)
	// Mattermost HA: cluster-wide push debounce via Redis (opaque keys only).
	if opts.RedisURL != "" {
		if deb, err := notification.NewRedisDebouncer(opts.RedisURL); err == nil {
			notificationSvc.SetDebouncer(deb)
		}
	}
	notificationSvc.Start(ctx)

	mediaCfg := media.ConfigFromEnv()
	mediaSvc := media.NewService(opts.Store, opts.Blobs, mediaCfg)
	// Open media path (channel photos) needs membership/publisher checks.
	// Encrypted DM/group path does not use Access.
	mediaSvc.Access = media.NewGroupChatAccess(opts.Store)
	mediaSvc.Start(ctx)

	groupSvc := group.NewService(opts.Store, group.ConfigFromEnv())

	syncCfg := sync.ConfigFromEnv()
	syncSvc := sync.NewService(opts.Store, syncCfg)
	syncSvc.Start(ctx)

	messagingHandler := messaging.NewHandler(opts.Store, opts.Store, hub, opts.Blobs, notificationSvc)
	messagingHandler.Metrics = m
	messagingHandler.Hooks.AddMessageHook(hooks.AuditMessageHook{})

	profileHandler := profile.NewHandler(opts.Store)
	profileHandler.Audit = opts.Store

	callingHandler := calling.NewHandler(opts.Store, calling.ConfigFromEnv(), notificationSvc, presenceSvc)
	// Call control events over the same authenticated WebSocket fanout.
	callingHandler.SetEventPublisher(hub)
	// Background call sweep needs the same publisher/presence as call handlers.
	_ = wireJobServer(ctx, opts.Store, m, notificationSvc, syncSvc, mediaSvc, hub, presenceSvc)

	h := &Handler{
		frontendOrigin:  opts.FrontendOrigin,
		store:           opts.Store,
		identity:        identity.NewHandler(opts.Store, opts.Store),
		profile:         profileHandler,
		messaging:       messagingHandler,
		keys:            wireKeysHandler(opts.Store, notificationSvc),
		calling:         callingHandler,
		notification:    notification.NewHandler(notificationSvc),
		notificationSvc: notificationSvc,
		media:           media.NewHandler(mediaSvc),
		preview:         linkpreview.NewHandler(linkpreview.NewService()),
		groups:          group.NewHandler(groupSvc),
		sync:            sync.NewHandler(syncSvc),
		presence:        presence.NewHandler(presenceSvc),
		rateLimiter:     opts.RateLimiter,
		hub:             hub,
		metrics:         m,
		cluster:         clusterSvc,
		clientUpdate:    clientupdate.FromEnv(),
		redisConfigured: opts.RedisURL != "",
		metricsEnabled:  opts.MetricsEnabled,
		mux:             http.NewServeMux(),
	}
	if h.rateLimiter == nil {
		h.rateLimiter = NewRateLimiter()
	}

	// Health + observability (Mattermost /metrics + support-packet lite).
	h.mux.HandleFunc("GET /api/health", h.health)
	h.mux.HandleFunc("GET /api/diagnostics", h.diagnostics)
	if opts.MetricsEnabled {
		h.mux.Handle("GET /metrics", metrics.Handler())
	}
	h.mux.HandleFunc("GET /api/hello", h.hello)
	// Public client update policy (no auth) — installable store/download URLs.
	h.mux.HandleFunc("GET /api/client/update-policy", h.clientUpdatePolicy)

	// Identity — auth (I-2)
	h.mux.HandleFunc("POST /api/auth/register", h.withRateLimit(h.identity.Register))
	h.mux.HandleFunc("POST /api/auth/login", h.withRateLimit(h.identity.Login))
	h.mux.HandleFunc("POST /api/auth/refresh", h.withRefreshRateLimit(h.identity.Refresh))
	h.mux.HandleFunc("POST /api/auth/logout", h.withAuth(h.identity.Logout))

	// Identity — abuse protection (I-6)
	h.mux.HandleFunc("GET /api/auth/pow", h.withRateLimit(h.identity.CreatePowChallenge))

	// Identity — recovery (I-4)
	h.mux.HandleFunc("POST /api/recovery/setup", h.withAuth(h.identity.SetupRecovery))
	h.mux.HandleFunc("GET /api/recovery/status", h.withAuth(h.identity.RecoveryStatus))
	h.mux.HandleFunc("POST /api/recovery/verify", h.withRateLimit(h.identity.VerifyRecovery))
	h.mux.HandleFunc("POST /api/recovery/complete", h.withRateLimit(h.identity.CompleteRecovery))
	h.mux.HandleFunc("POST /api/recovery/trusted/start", h.withRateLimit(h.identity.StartTrustedRecovery))
	h.mux.HandleFunc("GET /api/recovery/trusted/pending", h.withAuth(h.identity.PendingTrustedRecovery))
	h.mux.HandleFunc("POST /api/recovery/trusted/approve", h.withAuth(h.identity.ApproveTrustedRecovery))
	h.mux.HandleFunc("POST /api/recovery/trusted/poll", h.withRateLimit(h.identity.PollTrustedRecovery))

	// Identity — device trust (I-3)
	h.mux.HandleFunc("POST /api/devices/{id}/confirm", h.withAuth(h.identity.ConfirmDevice))
	h.mux.HandleFunc("GET /api/devices/{id}/confirm-code", h.withAuth(h.identity.GetDeviceConfirmCode))
	h.mux.HandleFunc("DELETE /api/devices/{id}", h.withAuth(h.identity.RevokeDevice))

	// Identity — passkeys (I-5)
	h.mux.HandleFunc("POST /api/auth/webauthn/register/begin", h.withAuth(h.identity.WebAuthnRegisterBegin))
	h.mux.HandleFunc("POST /api/auth/webauthn/register/finish", h.withAuth(h.identity.WebAuthnRegisterFinish))
	h.mux.HandleFunc("POST /api/auth/webauthn/login/begin", h.withRateLimit(h.identity.WebAuthnLoginBegin))
	h.mux.HandleFunc("POST /api/auth/webauthn/login/finish", h.withRateLimit(h.identity.WebAuthnLoginFinish))
	h.mux.HandleFunc("POST /api/recovery/passkey/begin", h.withRateLimit(h.identity.WebAuthnRecoveryBegin))
	h.mux.HandleFunc("POST /api/recovery/passkey/finish", h.withRateLimit(h.identity.WebAuthnRecoveryFinish))

	// Profile (I-1)
	h.mux.HandleFunc("GET /api/profile/search", h.withAuth(h.withSearchRateLimit(h.profile.SearchProfiles)))
	h.mux.HandleFunc("GET /api/profile/autocomplete", h.withAuth(h.withSearchRateLimit(h.profile.AutocompleteProfiles)))
	h.mux.HandleFunc("GET /api/profile/lookup", h.withAuth(h.withSearchRateLimit(h.profile.LookupProfile)))
	h.mux.HandleFunc("GET /api/profile/me", h.withAuth(h.profile.GetMyProfile))
	h.mux.HandleFunc("PATCH /api/profile/me", h.withAuth(h.profile.UpdateMyProfile))

	// Profile — legacy aliases
	h.mux.HandleFunc("GET /api/users/search", h.withAuth(h.withSearchRateLimit(h.profile.SearchProfiles)))
	h.mux.HandleFunc("GET /api/users/autocomplete", h.withAuth(h.withSearchRateLimit(h.profile.AutocompleteProfiles)))
	h.mux.HandleFunc("GET /api/users/lookup", h.withAuth(h.withSearchRateLimit(h.profile.LookupProfile)))
	// Mattermost-style batch cards for chat-list avatars.
	h.mux.HandleFunc("POST /api/users/ids", h.withAuth(h.withSearchRateLimit(h.profile.GetUsersByIDs)))
	h.mux.HandleFunc("POST /api/profile/ids", h.withAuth(h.withSearchRateLimit(h.profile.GetUsersByIDs)))
	h.mux.HandleFunc("GET /api/users/me", h.withAuth(h.profile.GetMyProfile))
	h.mux.HandleFunc("PATCH /api/users/me", h.withAuth(h.profile.UpdateMyProfile))

	// Group Service — membership & permissions (no message content).
	h.mux.HandleFunc("POST /api/groups", h.withAuth(h.groups.CreateGroup))
	// Channels — product surface (Telegram-like) over conversation policies (Matrix-like).
	h.mux.HandleFunc("POST /api/channels", h.withAuth(h.groups.CreateChannel))
	h.mux.HandleFunc("GET /api/channels/search", h.withAuth(h.groups.SearchPublicChannels))
	h.mux.HandleFunc("GET /api/channels/slug-available", h.withAuth(h.groups.CheckChannelSlug))
	h.mux.HandleFunc("POST /api/channels/slug/{slug}/join", h.withAuth(h.groups.JoinChannelBySlug))
	h.mux.HandleFunc("GET /api/groups/{id}", h.withAuth(h.groups.GetGroup))
	h.mux.HandleFunc("PATCH /api/groups/{id}/settings", h.withAuth(h.groups.UpdateSettings))
	h.mux.HandleFunc("POST /api/groups/{id}/members", h.withAuth(h.groups.AddMember))
	h.mux.HandleFunc("DELETE /api/groups/{id}/members/{userId}", h.withAuth(h.groups.RemoveMember))
	h.mux.HandleFunc("PATCH /api/groups/{id}/members/{userId}", h.withAuth(h.groups.UpdateMemberRole))
	h.mux.HandleFunc("POST /api/groups/{id}/leave", h.withAuth(h.groups.LeaveGroup))
	h.mux.HandleFunc("DELETE /api/groups/{id}", h.withAuth(h.groups.DeleteGroup))
	h.mux.HandleFunc("POST /api/groups/{id}/invites", h.withAuth(h.groups.CreateInvite))
	h.mux.HandleFunc("GET /api/groups/{id}/invites", h.withAuth(h.groups.ListInvites))
	h.mux.HandleFunc("DELETE /api/groups/{id}/invites/{inviteId}", h.withAuth(h.groups.RevokeInvite))
	h.mux.HandleFunc("GET /api/group-invites/{token}", h.withAuth(h.groups.PreviewInvite))
	h.mux.HandleFunc("POST /api/group-invites/{token}/join", h.withAuth(h.groups.JoinByInvite))
	h.mux.HandleFunc("POST /api/groups/{id}/join-requests", h.withAuth(h.groups.RequestJoin))
	h.mux.HandleFunc("GET /api/groups/{id}/join-requests", h.withAuth(h.groups.ListJoinRequests))
	h.mux.HandleFunc("POST /api/groups/{id}/join-requests/{requestId}/approve", h.withAuth(h.groups.ApproveJoinRequest))
	h.mux.HandleFunc("POST /api/groups/{id}/join-requests/{requestId}/reject", h.withAuth(h.groups.RejectJoinRequest))
	h.mux.HandleFunc("POST /api/groups/{id}/members/{userId}/mute", h.withAuth(h.groups.MuteMember))
	h.mux.HandleFunc("DELETE /api/groups/{id}/members/{userId}/mute", h.withAuth(h.groups.UnmuteMember))
	h.mux.HandleFunc("POST /api/groups/{id}/bans/{userId}", h.withAuth(h.groups.BanMember))
	h.mux.HandleFunc("DELETE /api/groups/{id}/bans/{userId}", h.withAuth(h.groups.UnbanMember))
	h.mux.HandleFunc("POST /api/groups/{id}/pinned", h.withAuth(h.groups.PinItem))
	h.mux.HandleFunc("DELETE /api/groups/{id}/pinned/{itemId}", h.withAuth(h.groups.UnpinItem))
	h.mux.HandleFunc("GET /api/groups/{id}/pinned", h.withAuth(h.groups.ListPinned))
	h.mux.HandleFunc("GET /api/groups/{id}/audit", h.withAuth(h.groups.ListAudit))
	h.mux.HandleFunc("GET /api/groups/{id}/key-epoch", h.withAuth(h.groups.KeyEpoch))

	// Messaging — chats
	h.mux.HandleFunc("GET /api/chats", h.withAuth(h.messaging.ListChats))
	h.mux.HandleFunc("POST /api/chats", h.withAuth(h.messaging.CreateChat))
	h.mux.HandleFunc("POST /api/chats/dm", h.withAuth(h.messaging.CreateDM))
	h.mux.HandleFunc("GET /api/chats/{id}", h.withAuth(h.messaging.GetChat))
	h.mux.HandleFunc("PATCH /api/chats/{id}", h.withAuth(h.messaging.UpdateChat))
	h.mux.HandleFunc("GET /api/chats/{id}/messages", h.withAuth(h.messaging.ChatMessages(http.MethodGet)))
	h.mux.HandleFunc("POST /api/chats/{id}/messages", h.withAuth(h.messaging.ChatMessages(http.MethodPost)))
	// Channel feed / gallery (root posts only; gallery filters metadata.media).
	h.mux.HandleFunc("GET /api/chats/{id}/posts", h.withAuth(h.messaging.ListChannelPosts))
	h.mux.HandleFunc("GET /api/chats/{id}/gallery", h.withAuth(h.messaging.ListChannelPosts))
	h.mux.HandleFunc("DELETE /api/chats/{id}/messages/{messageId}", h.withAuth(h.messaging.DeleteMessage))
	h.mux.HandleFunc("PUT /api/messages/{id}/reactions", h.withAuth(h.messaging.SetReaction))
	h.mux.HandleFunc("DELETE /api/messages/{id}/reactions", h.withAuth(h.messaging.ClearReaction))
	h.mux.HandleFunc("POST /api/messages/read", h.withAuth(h.messaging.MarkRead))

	// Messaging — transport (ciphertext only)
	h.mux.HandleFunc("POST /api/messages/relay", h.withAuth(h.withRateLimit(h.messaging.RelayMessage)))
	h.mux.HandleFunc("POST /api/messages/send", h.withAuth(h.withRateLimit(h.messaging.SendMessageRelay)))
	h.mux.HandleFunc("GET /api/messages/queue", h.withAuth(h.withPollRateLimit(h.messaging.ListMessageQueue)))
	h.mux.HandleFunc("POST /api/messages/queue/ack", h.withAuth(h.withPollRateLimit(h.messaging.AckMessageQueue)))

	// Sync Service — event log + delta + device recovery (no plaintext).
	h.mux.HandleFunc("GET /api/sync/events", h.withAuth(h.sync.ListEvents))
	h.mux.HandleFunc("GET /api/sync/delta", h.withAuth(h.sync.Delta))
	h.mux.HandleFunc("POST /api/sync/ack", h.withAuth(h.sync.Ack))
	h.mux.HandleFunc("GET /api/sync/snapshot", h.withAuth(h.sync.GetSnapshot))
	h.mux.HandleFunc("POST /api/sync/snapshot", h.withAuth(h.sync.SaveSnapshot))
	h.mux.HandleFunc("POST /api/sync/device", h.withAuth(h.sync.RegisterDevice))
	h.mux.HandleFunc("POST /api/sync/push", h.withAuth(h.sync.PushEvent))
	// Legacy sync (time-based chat events + chats).
	h.mux.HandleFunc("GET /api/sync", h.withAuth(h.sync.LegacySync))

	// Notification — privacy-safe push tokens & preferences
	h.mux.HandleFunc("POST /api/notification/tokens", h.withAuth(h.notification.RegisterToken))
	h.mux.HandleFunc("DELETE /api/notification/tokens/{id}", h.withAuth(h.notification.RevokeToken))
	h.mux.HandleFunc("GET /api/notification/preferences", h.withAuth(h.notification.GetPreferences))
	h.mux.HandleFunc("PATCH /api/notification/preferences", h.withAuth(h.notification.UpdatePreferences))

	// Presence — ephemeral state (Redis TTL) + privacy filter
	h.mux.HandleFunc("POST /api/presence/heartbeat", h.withAuth(h.presence.Heartbeat))
	h.mux.HandleFunc("POST /api/presence/typing/start", h.withAuth(h.presence.TypingStart))
	h.mux.HandleFunc("POST /api/presence/typing/stop", h.withAuth(h.presence.TypingStop))
	h.mux.HandleFunc("POST /api/presence/recording/start", h.withAuth(h.presence.RecordingStart))
	h.mux.HandleFunc("POST /api/presence/recording/stop", h.withAuth(h.presence.RecordingStop))
	h.mux.HandleFunc("GET /api/presence/users", h.withAuth(h.presence.ListUsers))
	h.mux.HandleFunc("GET /api/presence/chat/{id}", h.withAuth(h.presence.GetChatPresence))
	h.mux.HandleFunc("GET /api/presence/privacy", h.withAuth(h.presence.GetPrivacy))
	h.mux.HandleFunc("POST /api/presence/privacy", h.withAuth(h.presence.UpdatePrivacy))
	// Legacy aliases
	h.mux.HandleFunc("GET /api/users/{id}/presence", h.withAuth(h.presence.LegacyGetUserPresence))
	h.mux.HandleFunc("PATCH /api/presence/me", h.withAuth(h.presence.LegacyUpdateMyPresence))
	h.mux.HandleFunc("POST /api/presence/typing", h.withAuth(h.presence.LegacySendTyping))
	// Media Service — encrypted blobs only; metadata in Postgres, bytes in object storage.
	h.mux.HandleFunc("POST /api/media/upload-slots", h.withAuth(h.withUploadRateLimit(h.media.CreateUploadSlot)))
	h.mux.HandleFunc("PUT /api/media/files/{id}", h.withAuth(h.withUploadRateLimit(h.media.UploadFile)))
	h.mux.HandleFunc("GET /api/media/files/{id}", h.withAuth(h.media.DownloadFile))
	h.mux.HandleFunc("GET /api/media/files/{id}/meta", h.withAuth(h.media.GetFileMeta))
	h.mux.HandleFunc("DELETE /api/media/files/{id}", h.withAuth(h.media.DeleteFile))
	h.mux.HandleFunc("GET /api/media/cdn/{id}", h.media.CDNRedirect)
	h.mux.HandleFunc("POST /api/link-previews", h.withAuth(h.withPreviewRateLimit(h.preview.FetchPreview)))
	// Legacy attachment aliases (этап 6.1).
	h.mux.HandleFunc("POST /api/attachments", h.withAuth(h.withUploadRateLimit(h.media.LegacyCreateAttachment)))
	h.mux.HandleFunc("PUT /api/attachments/{id}", h.withAuth(h.withUploadRateLimit(h.media.LegacyUploadAttachment)))
	h.mux.HandleFunc("GET /api/attachments/{id}", h.withAuth(h.media.LegacyDownloadAttachment))
	h.mux.HandleFunc("GET /api/ws", h.hub.Handle)

	// Keys
	h.mux.HandleFunc("POST /api/devices", h.withAuth(h.keys.RegisterDevice))
	h.mux.HandleFunc("GET /api/devices/{id}/bundle", h.withAuth(h.keys.GetDeviceBundle))
	h.mux.HandleFunc("POST /api/devices/{id}/prekeys", h.withAuth(h.keys.ReplenishPrekeys))
	h.mux.HandleFunc("GET /api/devices/{id}/prekeys/count", h.withAuth(h.keys.CountRemainingPrekeys))
	h.mux.HandleFunc("POST /api/devices/{id}/signed-prekey/rotate", h.withAuth(h.keys.RotateSignedPreKey))
	h.mux.HandleFunc("GET /api/devices/{id}/safety-number", h.withAuth(h.keys.GetSafetyNumber))
	h.mux.HandleFunc("POST /api/devices/{id}/mailbox/rotate", h.withAuth(h.keys.RotateMailbox))
	h.mux.HandleFunc("GET /api/users/{id}/devices", h.withAuth(h.keys.ListUserDevices))
	h.mux.HandleFunc("GET /api/users/{id}/key-changes", h.withAuth(h.keys.ListKeyChanges))

	// Calling — dispatcher only, no media storage
	h.mux.HandleFunc("POST /api/calls", h.withAuth(h.calling.CreateCall))
	h.mux.HandleFunc("GET /api/calls/history", h.withAuth(h.calling.ListCallHistory))
	h.mux.HandleFunc("GET /api/calls/active", h.withAuth(h.calling.GetActiveCallForChat))
	// LiveKit webhook is not user-auth; verified via HMAC/API key (no tokens logged).
	h.mux.HandleFunc("POST /api/calls/livekit/webhook", h.calling.LiveKitWebhook)
	h.mux.HandleFunc("GET /api/calls/{id}", h.withAuth(h.calling.GetCall))
	h.mux.HandleFunc("POST /api/calls/{id}/accept", h.withAuth(h.calling.AcceptCall))
	h.mux.HandleFunc("POST /api/calls/{id}/reject", h.withAuth(h.calling.RejectCall))
	h.mux.HandleFunc("POST /api/calls/{id}/end", h.withAuth(h.calling.EndCall))
	h.mux.HandleFunc("POST /api/calls/{id}/connected", h.withAuth(h.calling.MarkConnected))
	h.mux.HandleFunc("POST /api/calls/{id}/invites", h.withAuth(h.calling.CreateInvite))
	h.mux.HandleFunc("POST /api/calls/{id}/invites/accept", h.withAuth(h.calling.AcceptInvite))
	h.mux.HandleFunc("POST /api/calls/{id}/join", h.withAuth(h.calling.JoinCall))
	h.mux.HandleFunc("GET /api/calls/{id}/token", h.withAuth(h.calling.GetCallToken))
	h.mux.HandleFunc("POST /api/calls/{id}/keys", h.withAuth(h.calling.SubmitCallKeys))
	h.mux.HandleFunc("GET /api/calls/{id}/keys", h.withAuth(h.calling.ListCallKeys))
	h.mux.HandleFunc("POST /api/calls/{id}/low-bandwidth", h.withAuth(h.calling.EnableLowBandwidth))
	h.mux.HandleFunc("GET /api/ice/servers", h.withAuth(h.calling.GetICEServers))

	return h.withMetrics(h.withRequestLogging(h.withCORS(h.mux)))
}

func (h *Handler) withMetrics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.metrics.IncHTTPInFlight()
		defer h.metrics.DecHTTPInFlight()
		recorder := &responseRecorder{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.finalStatus()
		route := metricsRouteLabel(r, status)
		h.metrics.IncrementHTTPRequest(route, r.Method, metrics.StatusLabel(status))
		h.metrics.ObserveHTTPDuration(route, time.Since(start).Seconds())
	})
}

func metricsRouteLabel(r *http.Request, status int) string {
	if r.Pattern != "" {
		return r.Pattern
	}
	switch status {
	case http.StatusNotFound:
		return "unmatched"
	case http.StatusMethodNotAllowed:
		return "method_not_allowed"
	default:
		return "unmatched"
	}
}

func wireKeysHandler(store MonolithStore, notifier *notification.Service) *keys.Handler {
	handler := keys.NewHandler(store)
	if notifier != nil {
		handler.Notifier = notifier
	}
	return handler
}

func (h *Handler) withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", h.frontendOrigin)
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		w.Header().Set(
			"Access-Control-Allow-Headers",
			"Content-Type, Authorization, X-Device-Id, X-Content-Hash, X-Upload-Id, X-Upload-Offset, X-Upload-Complete, X-Chunk-SHA256",
		)

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}
