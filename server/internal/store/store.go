// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"errors"
	"time"

	"glagolitsa/server/internal/jobs"
	"glagolitsa/server/internal/model"
)

var (
	ErrNotFound      = errors.New("not found")
	ErrAlreadyExists = errors.New("already exists")
	// ErrBusy — advisory lock not acquired (live-guard contention); map to HTTP 409.
	ErrBusy      = errors.New("resource busy")
	ErrForbidden = errors.New("forbidden")
	ErrGone      = errors.New("gone")
)

// CallKeyOfferRecord — зашифрованный ключ звонка (opaque blob, не расшифровывается сервером).
type CallKeyOfferRecord struct {
	SourceUserID   string
	SourceDeviceID string
	TargetUserID   string
	TargetDeviceID string
	EnvelopeType   int
	EncryptedKey   []byte
}

// Store — полный контракт хранилища монолита.
type Store interface {
	GetServerID() (string, error)

	// Profile (I-1)
	CreateProfile(userID, username string, user model.User) (model.User, error)
	GetProfile(userID string) (model.User, error)
	UpdateProfile(userID string, update model.UpdateProfileRequest) (model.User, error)
	SearchProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error)
	AutocompleteProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error)
	LookupProfileByUsername(username, excludeUserID string) (model.UserSearchHit, error)
	GetProfilesByIDs(userIDs []string, excludeUserID string) ([]model.UserSearchHit, error)
	GetUsername(userID string) (string, error)

	// Identity (I-2…I-7)
	CreateAccount(user model.User, passwordHash string) (model.AccountRecord, error)
	GetAccountByUsername(username string) (model.AccountRecord, model.User, error)
	GetAccountByID(userID string) (model.AccountRecord, error)
	ActivateAccount(userID string) error
	SetAccountRole(username, role string) error
	CreateSession(session model.SessionRecord) error
	FindSessionByID(sessionID string) (model.SessionRecord, error)
	FindSessionByRefreshHash(hash []byte) (model.SessionRecord, error)
	RenewSession(sessionID string, expiresAt time.Time, deviceID string) error
	RotateSession(oldSessionID, newSessionID string, newRefreshHash []byte, expiresAt time.Time, deviceID string) error
	RevokeSession(sessionID string) error
	RevokeUserSessions(userID string) error
	SetPasswordHash(userID, passwordHash string) error
	SetRecoveryKey(userID, hash, hint string) error
	ClearRecoveryKey(userID string) error
	VerifyRecoveryKeyHash(userID, hash string) (bool, error)
	FindAccountIDByRecoveryHash(hash string) (string, error)
	CreateRecoveryTicket(ticket model.RecoveryTicketRecord) error
	ConsumeRecoveryTicket(tokenHash string) (model.RecoveryTicketRecord, error)
	CreateTrustedRecoveryChallenge(challenge model.TrustedRecoveryChallengeRecord) error
	GetTrustedRecoveryChallenge(id string) (model.TrustedRecoveryChallengeRecord, error)
	ListPendingTrustedRecoveryChallenges(userID string) ([]model.TrustedRecoveryChallengeRecord, error)
	ApproveTrustedRecoveryChallenge(id, userID, deviceID string) error
	ClaimTrustedRecoveryTicket(id string, ticket model.RecoveryTicketRecord) (model.TrustedRecoveryChallengeRecord, error)
	SaveWebAuthnSession(session model.WebAuthnSessionRecord) error
	TakeWebAuthnSession(id string) (model.WebAuthnSessionRecord, error)
	SaveWebAuthnCredential(credential model.WebAuthnCredentialRecord) error
	ListWebAuthnCredentials(userID string) ([]model.WebAuthnCredentialRecord, error)
	CountWebAuthnCredentials(userID string) (int, error)
	UpdateWebAuthnCredential(credential model.WebAuthnCredentialRecord) error
	CountActiveDevices(accountID string) (int, error)
	IsActiveDevice(deviceID, accountID string) (bool, error)
	SetDeviceStatus(deviceID, accountID, status string) error
	ConfirmDevice(deviceID, accountID, confirmingDeviceID string) error
	RevokeDevice(deviceID, accountID string) error
	GetPendingDeviceCode(deviceID, accountID string) (string, time.Time, error)
	CreatePowChallenge(challengeID, challenge, clientIPHash string, difficulty int, expiresAt time.Time) error
	ConsumePowChallenge(challengeID, solution string) (bool, error)
	RecordAudit(event model.AuditEventInput) error
	IncrementRateLimitHits(userID string) error

	// Legacy aliases (seed, совместимость)
	CreateUser(user model.User, passwordHash string) (model.User, error)
	GetUserByUsername(username string) (model.User, string, error)
	GetUserByID(id string) (model.User, error)
	UpdateUserProfile(userID string, update model.UpdateProfileRequest) (model.User, error)
	SearchUsers(query, excludeUserID string, limit int) ([]model.User, error)

	CreateChat(chat model.Chat) (model.Chat, error)
	FindOrCreateDM(userID, otherUserID string) (model.Chat, error)
	ListChatsForUser(userID string) ([]model.Chat, error)
	GetChat(chatID, userID string) (model.Chat, error)
	UpdateChatAvatar(chatID, avatarURL string) error
	PermanentDeleteChat(chatID string) error

	ListMessages(chatID, userID string) ([]model.Message, error)
	ListMessagesPage(chatID, userID, beforeID string, limit int) ([]model.Message, bool, error)
	ListChannelPostsPage(chatID, userID, beforeID string, limit int, galleryOnly bool) ([]model.Message, bool, error)
	AddMessage(message model.Message) (model.Message, error)
	FindMessageByPendingID(chatID, pendingID string) (model.Message, error)
	FindMessage(chatID, messageID string) (model.Message, error)
	SetMessageReaction(messageID, userID, emoji string) ([]model.ReactionSummary, error)
	ClearMessageReaction(messageID, userID string) ([]model.ReactionSummary, error)
	ListMessageReactions(messageID, viewerID string) ([]model.ReactionSummary, error)

	RegisterDevice(registration DeviceRegistration) (storedPrekeyCount int, err error)
	ReplenishPrekeys(accountID, deviceID string, prekeys []OneTimePreKeyRecord) (storedCount int, err error)
	GetDeviceBundle(deviceID string) (DeviceKeyBundleRecord, error)
	ListUserDevices(accountID string, activeOnly bool) ([]model.UserDevice, error)
	RotateSignedPreKey(accountID, deviceID string, rotation SignedPreKeyRotation) error
	ListKeyChangeEvents(accountID string, limit int) ([]KeyChangeEventRecord, error)
	GetSafetyNumberMaterial(deviceID string) (model.SafetyNumberResponse, error)
	CountRemainingPrekeys(accountID, deviceID string) (int, error)
	DeviceOwnerAccountID(deviceID string) (string, error)
	PurgeDeviceKeys(deviceID, accountID string) error
	GetDeviceMailboxToken(deviceID, accountID string) (string, error)
	MailboxOwnerAccountID(mailboxToken string) (string, error)
	ResolveMailboxTokens(deviceID, accountID string) ([]string, error)
	RotateDeviceMailbox(deviceID, accountID string) (model.RotateMailboxResponse, error)

	EnqueueRelayEnvelopes(envelopes []RelayEnvelopeInput) ([]string, error)
	ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]QueuedEnvelopeRecord, error)
	AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error)
	UpdateChatPreview(chatID, preview string, at time.Time) error

	CreateAttachmentSlot(input CreateAttachmentInput) (AttachmentRecord, error)
	GetAttachment(attachmentID string) (AttachmentRecord, error)
	MarkAttachmentUploaded(attachmentID string, sizeBytes int64) error

	AppendChatEvent(event ChatEventInput) (model.ChatEvent, error)
	ListChatEvents(userID string, since time.Time, limit int) ([]model.ChatEvent, error)
	ListChatsUpdatedSince(userID string, since time.Time) ([]model.Chat, error)
	SoftDeleteMessage(chatID, messageID, userID string) (time.Time, error)
	RecordEnvelopeDelivery(envelopeID, recipientAccountID, mailboxToken string, sizeBucket int) error
	MarkEnvelopesFetched(envelopeIDs []string) error
	MarkEnvelopesAcked(envelopeIDs []string) error
	IncrementRelayEnvelopeCount(userID string, count int) error
	RelayEnvelopeCount24h(userID string) (int, error)
	PurgeExpiredQueue() (int, error)
	// TouchDeviceLastSeen marks a device as live (queue poll / authenticated use).
	TouchDeviceLastSeen(deviceID, accountID string) error
	// PurgeStaleDevices revokes abandoned extra devices and drops their relay
	// queues. The last remaining active device of an account is kept so
	// senders can still fan-out while the recipient is offline.
	PurgeStaleDevices() (int, error)
	// PurgeDeviceMailbox deletes queued envelopes for a device mailbox.
	PurgeDeviceMailbox(deviceID, accountID string) error

	// Jobs — Mattermost-style background queue.
	CreateJob(job jobs.Job) (jobs.Job, error)
	ClaimNextJob(jobType string, now time.Time) (jobs.Job, bool, error)
	SetJobSuccess(jobID string, progress int64) error
	SetJobError(jobID string, lastError string) error
	EnqueueScheduledJob(jobType string, notBefore time.Time) (bool, error)

	// Presence — privacy + social graph (ephemeral state in Redis).
	GetPresencePrivacy(userID string) (model.PresencePrivacySettings, error)
	SetPresencePrivacy(userID string, settings model.PresencePrivacySettings) error
	GetLastSeenAt(userID string) (*time.Time, error)
	SetLastSeenAt(userID string, at time.Time) error
	ListContactUserIDs(userID string) ([]string, error)
	GetChatMemberIDs(chatID, viewerID string) ([]string, error)
}

func NowUTC() time.Time {
	return time.Now().UTC()
}
