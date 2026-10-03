// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/jobs"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/notification"
)

type MemoryStore struct {
	mu       sync.RWMutex
	serverID string

	// messageID -> userID -> emoji
	reactions map[string]map[string]string

	users                     map[string]*userRecord
	usersByUsername           map[string]string
	usersByEmail              map[string]string
	sessions                  map[string]*memSessionRecord
	sessionsByRefresh         map[string]string
	powChallenges             map[string]*powChallengeRecord
	recoveryTickets           map[string]*model.RecoveryTicketRecord
	trustedRecoveryChallenges map[string]*model.TrustedRecoveryChallengeRecord
	webauthnSessions          map[string]*model.WebAuthnSessionRecord
	webauthnCredentials       map[string][]model.WebAuthnCredentialRecord
	keyChangeEvents           map[string][]KeyChangeEventRecord
	chatEvents                []memChatEvent
	envelopeDelivery          map[string]memDeliveryRecord
	messagingLimits           map[string]int
	userPresence              map[string]memPresenceRecord
	presencePrivacy           map[string]model.PresencePrivacySettings
	deletedMessages           map[string]time.Time
	chats                     map[string]*model.Chat
	messages                  map[string][]model.Message
	devices                   map[string]*deviceRecord
	messageQueue              map[string][]queuedEnvelopeRecord
	attachments               map[string]*attachmentRecord
	mediaFiles                map[string]*mediaRecord
	relayQueue                RelayQueueDelegate

	calls            map[string]*model.CallSession
	callParticipants map[string][]memCallParticipant
	callKeyOffers    map[string][]memCallKeyOffer
	callInvites      map[string][]memCallInvite

	pushTokens           map[string]*memPushToken
	notificationPrefs    map[string]notification.Preferences
	notificationDelivery []memNotificationDelivery
	notificationRetries  map[string]*memRetryJob

	groupSettings       map[string]GroupSettings
	groupMembers        map[string]map[string]GroupMember
	groupInvitesByToken map[string]GroupInvite
	groupJoinRequests   map[string]GroupJoinRequest
	groupBans           map[string]map[string]GroupBan
	groupPinned         map[string][]GroupPinnedItem
	jobs                map[string]jobs.Job
	groupAudit          map[string][]GroupAuditEvent
	groupsCreated24h    map[string]int
	groupsWindowStart   map[string]time.Time

	syncEvents       []SyncEventRecord
	syncEventSeq     int64
	syncDevices      map[string]map[string]SyncDeviceRecord
	syncSnapshots    map[string][]SyncSnapshotRecord
	syncOfflineQueue map[string]SyncOfflineQueueRecord
}

type memSessionRecord struct {
	model.SessionRecord
	revoked    bool
	replacedBy string
}

type powChallengeRecord struct {
	challengeID string
	challenge   string
	difficulty  int
	solution    string
	expiresAt   time.Time
}

type RelayQueueDelegate interface {
	EnqueueRelayEnvelopes(envelopes []RelayEnvelopeInput) ([]string, error)
	ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]QueuedEnvelopeRecord, error)
	AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error)
}

type userRecord struct {
	User            model.User
	PasswordHash    string
	TrustTier       string
	AccountStatus   string
	AccountRole     string
	RecoveryKeyHash string
	RecoveryKeyHint string
}

func NewMemory() *MemoryStore {
	return &MemoryStore{
		serverID:                  uuid.NewString(),
		users:                     make(map[string]*userRecord),
		usersByUsername:           make(map[string]string),
		usersByEmail:              make(map[string]string),
		sessions:                  make(map[string]*memSessionRecord),
		sessionsByRefresh:         make(map[string]string),
		powChallenges:             make(map[string]*powChallengeRecord),
		recoveryTickets:           make(map[string]*model.RecoveryTicketRecord),
		trustedRecoveryChallenges: make(map[string]*model.TrustedRecoveryChallengeRecord),
		webauthnSessions:          make(map[string]*model.WebAuthnSessionRecord),
		webauthnCredentials:       make(map[string][]model.WebAuthnCredentialRecord),
		keyChangeEvents:           make(map[string][]KeyChangeEventRecord),
		chatEvents:                make([]memChatEvent, 0),
		envelopeDelivery:          make(map[string]memDeliveryRecord),
		messagingLimits:           make(map[string]int),
		userPresence:              make(map[string]memPresenceRecord),
		presencePrivacy:           make(map[string]model.PresencePrivacySettings),
		deletedMessages:           make(map[string]time.Time),
		chats:                     make(map[string]*model.Chat),
		messages:                  make(map[string][]model.Message),
		devices:                   make(map[string]*deviceRecord),
		messageQueue:              make(map[string][]queuedEnvelopeRecord),
		attachments:               make(map[string]*attachmentRecord),
		calls:                     make(map[string]*model.CallSession),
		callParticipants:          make(map[string][]memCallParticipant),
		callKeyOffers:             make(map[string][]memCallKeyOffer),
		callInvites:               make(map[string][]memCallInvite),
	}
}

func (s *MemoryStore) GetServerID() (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.serverID, nil
}

// Ping — diagnostics; memory store is always healthy.
func (s *MemoryStore) Ping(ctx context.Context) error {
	_ = ctx
	return nil
}

type memCallParticipant struct {
	CallID      string
	UserID      string
	DeviceID    string
	Role        string
	InviteState string
	MediaState  string
	JoinedAt    *time.Time
	LeftAt      *time.Time
}

type memCallInvite struct {
	ID              string
	CallID          string
	InvitedUserID   string
	InvitedByUserID string
	State           string
	CreatedAt       time.Time
	ExpiresAt       *time.Time
}

type memCallKeyOffer struct {
	SourceUserID   string
	SourceDeviceID string
	TargetUserID   string
	TargetDeviceID string
	EnvelopeType   int
	EncryptedKey   []byte
	CreatedAt      time.Time
}

func (s *MemoryStore) SetRelayQueue(delegate RelayQueueDelegate) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.relayQueue = delegate
}

func (s *MemoryStore) CreateUser(user model.User, passwordHash string) (model.User, error) {
	account, err := s.CreateAccount(user, passwordHash)
	if err != nil {
		return model.User{}, err
	}
	_ = s.ActivateAccount(account.ID)
	return s.GetUserByID(account.ID)
}

func (s *MemoryStore) GetUserByUsername(username string) (model.User, string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	id, ok := s.usersByUsername[strings.ToLower(strings.TrimSpace(username))]
	if !ok {
		return model.User{}, "", ErrNotFound
	}
	record := s.users[id]
	return record.User, record.PasswordHash, nil
}

func (s *MemoryStore) GetUserByID(id string) (model.User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	record, ok := s.users[id]
	if !ok {
		return model.User{}, ErrNotFound
	}
	return record.User, nil
}

func (s *MemoryStore) UpdateUserProfile(userID string, update model.UpdateProfileRequest) (model.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.users[userID]
	if !ok {
		return model.User{}, ErrNotFound
	}
	if update.DisplayName != nil {
		record.User.DisplayName = *update.DisplayName
	}
	if update.Status != nil {
		record.User.Status = *update.Status
	}
	if update.Bio != nil {
		record.User.Bio = *update.Bio
	}
	if update.AvatarURL != nil {
		record.User.AvatarURL = *update.AvatarURL
	}
	if update.Presence != nil {
		record.User.Presence = *update.Presence
	}
	if update.Nickname != nil {
		record.User.Nickname = *update.Nickname
	}
	if update.Position != nil {
		record.User.Position = *update.Position
	}
	s.users[userID] = record
	return record.User, nil
}

func (s *MemoryStore) SearchUsers(query, excludeUserID string, limit int) ([]model.User, error) {
	hits, err := s.searchProfileHits(query, excludeUserID, limit, false)
	if err != nil {
		return nil, err
	}
	return searchHitsToUsers(hits), nil
}

func (s *MemoryStore) searchProfileHits(query, excludeUserID string, limit int, prefixOnly bool) ([]model.UserSearchHit, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	query = strings.ToLower(strings.TrimSpace(query))
	hits := make([]model.UserSearchHit, 0)
	for _, record := range s.users {
		if record.User.ID == excludeUserID {
			continue
		}
		if record.AccountStatus != model.AccountStatusActive {
			continue
		}
		username := record.User.Username
		display := strings.ToLower(strings.TrimSpace(record.User.DisplayName))
		matches := strings.HasPrefix(username, query) ||
			(!prefixOnly && strings.Contains(username, query)) ||
			(!prefixOnly && display != "" && strings.HasPrefix(display, query))
		if matches {
			hits = append(hits, userSearchHitFromRecord(record))
		}
	}
	sort.Slice(hits, func(i, j int) bool {
		if hits[i].Username == query {
			return true
		}
		if hits[j].Username == query {
			return false
		}
		return hits[i].Username < hits[j].Username
	})
	if limit > 0 && len(hits) > limit {
		hits = hits[:limit]
	}
	return hits, nil
}

func (s *MemoryStore) CreateChat(chat model.Chat) (model.Chat, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if chat.Title == "" {
		return model.Chat{}, errors.New("title is required")
	}
	if chat.Type == "" {
		chat.Type = model.ChatTypeGroup
	}
	s.chats[chat.ID] = &chat
	s.messages[chat.ID] = nil
	return chat, nil
}

func (s *MemoryStore) UpdateChatAvatar(chatID, avatarURL string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	chat, ok := s.chats[chatID]
	if !ok {
		return ErrNotFound
	}
	chat.AvatarURL = avatarURL
	s.chats[chatID] = chat
	return nil
}

func (s *MemoryStore) FindOrCreateDM(userID, otherUserID string) (model.Chat, error) {
	if userID == otherUserID {
		return model.Chat{}, errors.New("cannot create dm with yourself")
	}
	account, err := s.GetAccountByID(otherUserID)
	if err != nil {
		return model.Chat{}, err
	}
	if account.AccountStatus != model.AccountStatusActive {
		return model.Chat{}, ErrNotFound
	}
	otherUser, err := s.GetUserByID(otherUserID)
	if err != nil {
		return model.Chat{}, err
	}

	dmKey := makeDMKey(userID, otherUserID)
	s.mu.RLock()
	for _, chat := range s.chats {
		if chat.DMKey == dmKey {
			copy := *chat
			s.mu.RUnlock()
			return s.personalizeDMMemory(copy, userID), nil
		}
	}
	s.mu.RUnlock()

	newChat := model.Chat{
		ID:        uuid.NewString(),
		Title:     otherUser.Username,
		Type:      model.ChatTypeDM,
		DMKey:     dmKey,
		MemberIDs: []string{userID, otherUserID},
		CreatedAt: NowUTC(),
	}
	return s.CreateChat(newChat)
}

func (s *MemoryStore) ListChatsForUser(userID string) ([]model.Chat, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	chats := make([]model.Chat, 0)
	for _, chat := range s.chats {
		if chatHasMember(*chat, userID) {
			copy := s.personalizeDMMemory(*chat, userID)
			s.attachOwnerIDLocked(&copy)
			chats = append(chats, copy)
		}
	}

	sort.Slice(chats, func(i, j int) bool {
		if chats[i].LastMessageAt.Equal(chats[j].LastMessageAt) {
			return chats[i].CreatedAt.After(chats[j].CreatedAt)
		}
		return chats[i].LastMessageAt.After(chats[j].LastMessageAt)
	})

	return chats, nil
}

func (s *MemoryStore) GetChat(chatID, userID string) (model.Chat, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	chat, ok := s.chats[chatID]
	if !ok {
		return model.Chat{}, ErrNotFound
	}
	if !chatHasMember(*chat, userID) {
		return model.Chat{}, ErrForbidden
	}
	copy := s.personalizeDMMemory(*chat, userID)
	s.attachOwnerIDLocked(&copy)
	return copy, nil
}

func (s *MemoryStore) attachOwnerIDLocked(chat *model.Chat) {
	if chat.Type != model.ChatTypeGroup && chat.Type != model.ChatTypeChannel {
		return
	}
	for _, member := range s.groupMembers[chat.ID] {
		if member.Role == GroupRoleOwner {
			chat.CreatorID = member.UserID
			return
		}
	}
}

func (s *MemoryStore) ListMessages(chatID, userID string) ([]model.Message, error) {
	messages, _, err := s.ListMessagesPage(chatID, userID, "", 10_000)
	return messages, err
}

func (s *MemoryStore) ListMessagesPage(chatID, userID, beforeID string, limit int) ([]model.Message, bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	chat, ok := s.chats[chatID]
	if !ok {
		return nil, false, ErrNotFound
	}
	if !chatHasMember(*chat, userID) {
		return nil, false, ErrForbidden
	}
	if limit <= 0 {
		limit = 50
	}

	all := append([]model.Message(nil), s.messages[chatID]...)
	page := make([]model.Message, 0, limit+1)

	if beforeID == "" {
		for i := len(all) - 1; i >= 0 && len(page) < limit+1; i-- {
			page = append(page, all[i])
		}
	} else {
		cutoff := -1
		for i, message := range all {
			if message.ID == beforeID {
				cutoff = i
				break
			}
		}
		if cutoff <= 0 {
			return []model.Message{}, false, nil
		}
		for i := cutoff - 1; i >= 0 && len(page) < limit+1; i-- {
			page = append(page, all[i])
		}
	}

	hasMore := len(page) > limit
	if hasMore {
		page = page[:limit]
	}
	reverseMessages(page)
	return page, hasMore, nil
}

func (s *MemoryStore) ListChannelPostsPage(chatID, userID, beforeID string, limit int, galleryOnly bool) ([]model.Message, bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	chat, ok := s.chats[chatID]
	if !ok {
		return nil, false, ErrNotFound
	}
	if !chatHasMember(*chat, userID) {
		return nil, false, ErrForbidden
	}
	if limit <= 0 {
		limit = 50
	}

	all := make([]model.Message, 0, len(s.messages[chatID]))
	for _, m := range s.messages[chatID] {
		if strings.TrimSpace(m.ThreadRootID) != "" {
			continue
		}
		if galleryOnly && !messageHasMediaMetadata(m) {
			continue
		}
		all = append(all, m)
	}

	page := make([]model.Message, 0, limit+1)
	if beforeID == "" {
		for i := len(all) - 1; i >= 0 && len(page) < limit+1; i-- {
			page = append(page, all[i])
		}
	} else {
		cutoff := -1
		for i, message := range all {
			if message.ID == beforeID {
				cutoff = i
				break
			}
		}
		if cutoff <= 0 {
			return []model.Message{}, false, nil
		}
		for i := cutoff - 1; i >= 0 && len(page) < limit+1; i-- {
			page = append(page, all[i])
		}
	}
	hasMore := len(page) > limit
	if hasMore {
		page = page[:limit]
	}
	reverseMessages(page)
	return page, hasMore, nil
}

func messageHasMediaMetadata(m model.Message) bool {
	if m.Metadata == nil {
		return false
	}
	raw, ok := m.Metadata["media"]
	if !ok || raw == nil {
		return false
	}
	switch v := raw.(type) {
	case []any:
		return len(v) > 0
	case []map[string]any:
		return len(v) > 0
	default:
		return false
	}
}

func (s *MemoryStore) UpdateChatPreview(chatID, preview string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	chat, ok := s.chats[chatID]
	if !ok {
		return ErrNotFound
	}
	chat.LastMessage = preview
	chat.LastMessageAt = at
	return nil
}

func (s *MemoryStore) PermanentDeleteChat(chatID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.chats[chatID]; !ok {
		return ErrNotFound
	}
	delete(s.chats, chatID)
	delete(s.messages, chatID)
	delete(s.groupMembers, chatID)
	delete(s.groupSettings, chatID)
	delete(s.groupBans, chatID)
	delete(s.groupPinned, chatID)
	delete(s.groupAudit, chatID)
	for token, invite := range s.groupInvitesByToken {
		if invite.GroupID == chatID {
			delete(s.groupInvitesByToken, token)
		}
	}
	for id, req := range s.groupJoinRequests {
		if req.GroupID == chatID {
			delete(s.groupJoinRequests, id)
		}
	}
	return nil
}

func (s *MemoryStore) AddMessage(message model.Message) (model.Message, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	chat, ok := s.chats[message.ChatID]
	if !ok {
		return model.Message{}, ErrNotFound
	}
	if !chatHasMember(*chat, message.SenderID) {
		return model.Message{}, ErrForbidden
	}

	for _, existing := range s.messages[message.ChatID] {
		if message.PendingID != "" && existing.PendingID == message.PendingID {
			return existing, nil
		}
		if existing.ID == message.ID {
			return existing, nil
		}
	}

	s.messages[message.ChatID] = append(s.messages[message.ChatID], message)
	chat.LastMessage = messagePreviewText(message)
	chat.LastMessageAt = message.CreatedAt
	return message, nil
}

func (s *MemoryStore) FindMessageByPendingID(chatID, pendingID string) (model.Message, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, message := range s.messages[chatID] {
		if message.PendingID == pendingID {
			return message, nil
		}
	}
	return model.Message{}, ErrNotFound
}

func (s *MemoryStore) personalizeDMMemory(chat model.Chat, currentUserID string) model.Chat {
	if chat.Type != model.ChatTypeDM {
		return chat
	}
	for _, memberID := range chat.MemberIDs {
		if memberID == currentUserID {
			continue
		}
		if user, err := s.GetUserByID(memberID); err == nil {
			chat.Title = user.Username
		}
		break
	}
	return chat
}

func chatHasMember(chat model.Chat, userID string) bool {
	for _, memberID := range chat.MemberIDs {
		if memberID == userID {
			return true
		}
	}
	return false
}
