// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// Store — контракт Messaging Service (чаты, relay, вложения).
type Store interface {
	CreateChat(chat model.Chat) (model.Chat, error)
	FindOrCreateDM(userID, otherUserID string) (model.Chat, error)
	ListChatsForUser(userID string) ([]model.Chat, error)
	GetChat(chatID, userID string) (model.Chat, error)
	UpdateChatAvatar(chatID, avatarURL string) error
	GetGroupSettings(groupID string) (store.GroupSettings, error)
	GetGroupMember(groupID, userID string) (store.GroupMember, error)
	GetGroupMemberRole(groupID, userID string) (string, error)
	EnsureManagedGroup(chatID, preferredOwnerID string) error

	ListMessages(chatID, userID string) ([]model.Message, error)
	ListMessagesPage(chatID, userID, beforeID string, limit int) ([]model.Message, bool, error)
	// ListChannelPostsPage returns root posts (no thread_root). galleryOnly keeps posts with media metadata.
	ListChannelPostsPage(chatID, userID, beforeID string, limit int, galleryOnly bool) ([]model.Message, bool, error)
	AddMessage(message model.Message) (model.Message, error)
	FindMessageByPendingID(chatID, pendingID string) (model.Message, error)
	FindMessage(chatID, messageID string) (model.Message, error)
	SetMessageReaction(messageID, userID, emoji string) ([]model.ReactionSummary, error)
	ClearMessageReaction(messageID, userID string) ([]model.ReactionSummary, error)
	ListMessageReactions(messageID, viewerID string) ([]model.ReactionSummary, error)

	EnqueueRelayEnvelopes(envelopes []store.RelayEnvelopeInput) ([]string, error)
	ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]store.QueuedEnvelopeRecord, error)
	AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error)
	UpdateChatPreview(chatID, preview string, at time.Time) error

	CreateAttachmentSlot(input store.CreateAttachmentInput) (store.AttachmentRecord, error)
	GetAttachment(attachmentID string) (store.AttachmentRecord, error)
	MarkAttachmentUploaded(attachmentID string, sizeBytes int64) error

	AppendChatEvent(event store.ChatEventInput) (model.ChatEvent, error)
	ListChatEvents(userID string, since time.Time, limit int) ([]model.ChatEvent, error)
	ListChatsUpdatedSince(userID string, since time.Time) ([]model.Chat, error)
	SoftDeleteMessage(chatID, messageID, userID string) (time.Time, error)
	RecordEnvelopeDelivery(envelopeID, recipientAccountID, mailboxToken string, sizeBucket int) error
	MarkEnvelopesFetched(envelopeIDs []string) error
	MarkEnvelopesAcked(envelopeIDs []string) error
	IncrementRelayEnvelopeCount(userID string, count int) error
	RelayEnvelopeCount24h(userID string) (int, error)
	TouchDeviceLastSeen(deviceID, accountID string) error
	GetAccountByID(userID string) (model.AccountRecord, error)
	CountActiveDevices(accountID string) (int, error)
}
