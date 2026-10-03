// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"glagolitsa/server/internal/model"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

type PostgresStore struct {
	pool       *pgxpool.Pool
	relayQueue RelayQueueDelegate
}

func NewPostgres(ctx context.Context, databaseURL string) (*PostgresStore, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("connect postgres: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping postgres: %w", err)
	}

	store := &PostgresStore{pool: pool}
	if err := store.migrate(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return store, nil
}

func (s *PostgresStore) Close() {
	s.pool.Close()
}

// Ping — diagnostics (Mattermost support packet DB probe).
func (s *PostgresStore) Ping(ctx context.Context) error {
	if s == nil || s.pool == nil {
		return fmt.Errorf("postgres pool is nil")
	}
	return s.pool.Ping(ctx)
}

func (s *PostgresStore) SetRelayQueue(delegate RelayQueueDelegate) {
	s.relayQueue = delegate
}

func (s *PostgresStore) GetServerID() (string, error) {
	if _, err := s.pool.Exec(context.Background(), `
		INSERT INTO instance_identity (id)
		VALUES ('default')
		ON CONFLICT (id) DO NOTHING
	`); err != nil {
		return "", err
	}
	var id string
	err := s.pool.QueryRow(context.Background(), `
		SELECT server_id::text FROM instance_identity WHERE id = 'default'
	`).Scan(&id)
	return id, err
}

func (s *PostgresStore) migrate(ctx context.Context) error {
	if _, err := s.pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			name TEXT PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		)
	`); err != nil {
		return fmt.Errorf("ensure schema_migrations: %w", err)
	}

	entries, err := migrationFiles.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("list migrations: %w", err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".sql") {
			names = append(names, entry.Name())
		}
	}
	slices.Sort(names)
	if err := s.backfillSchemaMigrations(ctx, names); err != nil {
		return err
	}
	for _, name := range names {
		var alreadyApplied bool
		if err := s.pool.QueryRow(ctx, `
			SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name = $1)
		`, name).Scan(&alreadyApplied); err != nil {
			return fmt.Errorf("check migration %s: %w", name, err)
		}
		if alreadyApplied {
			continue
		}

		sqlBytes, err := migrationFiles.ReadFile("migrations/" + name)
		if err != nil {
			return fmt.Errorf("read migration %s: %w", name, err)
		}
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return fmt.Errorf("begin migration %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("apply migration %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations (name) VALUES ($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record migration %s: %w", name, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit migration %s: %w", name, err)
		}
	}
	return nil
}

func (s *PostgresStore) backfillSchemaMigrations(ctx context.Context, names []string) error {
	var count int
	if err := s.pool.QueryRow(ctx, `SELECT COUNT(*) FROM schema_migrations`).Scan(&count); err != nil {
		return fmt.Errorf("count schema migrations: %w", err)
	}
	if count > 0 {
		return nil
	}
	var usersExists bool
	if err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM information_schema.tables
			WHERE table_schema = 'public' AND table_name = 'users'
		)
	`).Scan(&usersExists); err != nil {
		return fmt.Errorf("detect existing schema: %w", err)
	}
	if !usersExists {
		return nil
	}
	var attachmentsExists bool
	if err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM information_schema.tables
			WHERE table_schema = 'public' AND table_name = 'attachments'
		)
	`).Scan(&attachmentsExists); err != nil {
		return fmt.Errorf("detect attachments table: %w", err)
	}
	for _, name := range names {
		if name == "007_attachments.sql" && !attachmentsExists {
			break
		}
		if _, err := s.pool.Exec(ctx, `
			INSERT INTO schema_migrations (name) VALUES ($1) ON CONFLICT DO NOTHING
		`, name); err != nil {
			return fmt.Errorf("backfill migration %s: %w", name, err)
		}
	}
	return nil
}

func (s *PostgresStore) CreateUser(user model.User, passwordHash string) (model.User, error) {
	account, err := s.CreateAccount(user, passwordHash)
	if err != nil {
		return model.User{}, err
	}
	_ = s.ActivateAccount(account.ID)
	return s.GetProfile(account.ID)
}

func (s *PostgresStore) createUserLegacy(user model.User, passwordHash string) (model.User, error) {
	username := strings.ToLower(strings.TrimSpace(user.Username))
	if username == "" {
		return model.User{}, errors.New("username is required")
	}
	user.Username = username

	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO users (id, username, password_hash, display_name, status, bio, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`, user.ID, user.Username, passwordHash, user.DisplayName, user.Status, user.Bio, user.CreatedAt)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			return model.User{}, ErrAlreadyExists
		}
		return model.User{}, err
	}
	return user, nil
}

func (s *PostgresStore) GetUserByUsername(username string) (model.User, string, error) {
	var user model.User
	var passwordHash string
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, username, display_name, status, bio, avatar_url, presence, nickname, position, created_at, password_hash
		FROM users WHERE username = $1
	`, strings.ToLower(strings.TrimSpace(username))).Scan(
		&user.ID, &user.Username, &user.DisplayName, &user.Status, &user.Bio,
		&user.AvatarURL, &user.Presence, &user.Nickname, &user.Position, &user.CreatedAt, &passwordHash,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.User{}, "", ErrNotFound
	}
	return user, passwordHash, err
}

func (s *PostgresStore) GetUserByID(id string) (model.User, error) {
	var user model.User
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, username, display_name, status, bio, avatar_url, presence, nickname, position, created_at
		FROM users WHERE id = $1
	`, id).Scan(
		&user.ID, &user.Username, &user.DisplayName, &user.Status, &user.Bio,
		&user.AvatarURL, &user.Presence, &user.Nickname, &user.Position, &user.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.User{}, ErrNotFound
	}
	return user, err
}

func (s *PostgresStore) UpdateUserProfile(userID string, update model.UpdateProfileRequest) (model.User, error) {
	user, err := s.GetUserByID(userID)
	if err != nil {
		return model.User{}, err
	}
	if update.DisplayName != nil {
		user.DisplayName = *update.DisplayName
	}
	if update.Status != nil {
		user.Status = *update.Status
	}
	if update.Bio != nil {
		user.Bio = *update.Bio
	}
	if update.AvatarURL != nil {
		user.AvatarURL = *update.AvatarURL
	}
	if update.Presence != nil {
		user.Presence = *update.Presence
	}
	if update.Nickname != nil {
		user.Nickname = *update.Nickname
	}
	if update.Position != nil {
		user.Position = *update.Position
	}

	_, err = s.pool.Exec(context.Background(), `
		UPDATE users
		SET display_name = $2, status = $3, bio = $4,
		    avatar_url = $5, presence = $6, nickname = $7, position = $8
		WHERE id = $1
	`, user.ID, user.DisplayName, user.Status, user.Bio,
		user.AvatarURL, user.Presence, user.Nickname, user.Position)
	if err != nil {
		return model.User{}, err
	}
	return user, nil
}

func (s *PostgresStore) SearchUsers(query, excludeUserID string, limit int) ([]model.User, error) {
	hits, err := s.SearchProfiles(query, excludeUserID, limit)
	if err != nil {
		return nil, err
	}
	return searchHitsToUsers(hits), nil
}

func (s *PostgresStore) CreateChat(chat model.Chat) (model.Chat, error) {
	if chat.Title == "" {
		return model.Chat{}, errors.New("title is required")
	}
	if chat.Type == "" {
		chat.Type = model.ChatTypeGroup
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return model.Chat{}, err
	}
	defer tx.Rollback(context.Background())

	_, err = tx.Exec(context.Background(), `
		INSERT INTO chats (id, title, chat_type, dm_key, last_message, last_message_at, created_at, avatar_url)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`, chat.ID, chat.Title, chat.Type, nullableDMKey(chat), nullableString(chat.LastMessage), nullableTime(chat.LastMessageAt), chat.CreatedAt, chat.AvatarURL)
	if err != nil {
		return model.Chat{}, err
	}

	for _, memberID := range chat.MemberIDs {
		_, err = tx.Exec(context.Background(), `
			INSERT INTO chat_members (chat_id, user_id, joined_at) VALUES ($1, $2, $3)
		`, chat.ID, memberID, chat.CreatedAt)
		if err != nil {
			return model.Chat{}, err
		}
	}

	if err := tx.Commit(context.Background()); err != nil {
		return model.Chat{}, err
	}
	return chat, nil
}

func (s *PostgresStore) FindOrCreateDM(userID, otherUserID string) (model.Chat, error) {
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
	if _, err := s.GetUserByID(otherUserID); err != nil {
		return model.Chat{}, err
	}

	dmKey := makeDMKey(userID, otherUserID)
	if chat, err := s.getChatByDMKey(dmKey, userID); err == nil {
		return chat, nil
	} else if !errors.Is(err, ErrNotFound) {
		return model.Chat{}, err
	}

	otherUser, _ := s.GetUserByID(otherUserID)
	newChat := model.Chat{
		ID:        uuid.NewString(),
		Title:     otherUser.Username,
		Type:      model.ChatTypeDM,
		DMKey:     dmKey,
		MemberIDs: []string{userID, otherUserID},
		CreatedAt: NowUTC(),
	}

	created, err := s.CreateChat(newChat)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			return s.getChatByDMKey(dmKey, userID)
		}
		return model.Chat{}, err
	}
	return s.personalizeDM(created, userID), nil
}

func (s *PostgresStore) ListChatsForUser(userID string) ([]model.Chat, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT c.id, c.title, c.chat_type, c.dm_key, c.last_message, c.last_message_at, c.created_at, c.avatar_url
		FROM chats c
		INNER JOIN chat_members cm ON cm.chat_id = c.id
		WHERE cm.user_id = $1
		ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	chats := make([]model.Chat, 0)
	for rows.Next() {
		chat, err := scanChatFromRows(rows)
		if err != nil {
			return nil, err
		}
		chat.MemberIDs, err = s.listChatMemberIDs(chat.ID)
		if err != nil {
			return nil, err
		}
		// Channel/group policy projection (slug, visibility, description) for client lists.
		if chat.Type == model.ChatTypeChannel || chat.Type == model.ChatTypeGroup {
			if settings, sErr := s.GetGroupSettings(chat.ID); sErr == nil {
				chat.Description = settings.Description
				chat.Visibility = settings.Visibility
				chat.Slug = settings.Slug
				chat.Encryption = settings.EncryptionMode
			}
			s.attachOwnerID(&chat)
		}
		chats = append(chats, s.personalizeDM(chat, userID))
	}
	return chats, rows.Err()
}

func (s *PostgresStore) GetChat(chatID, userID string) (model.Chat, error) {
	if !s.isChatMember(chatID, userID) {
		return model.Chat{}, ErrForbidden
	}

	chat, err := s.getChatByID(chatID)
	if err != nil {
		return model.Chat{}, err
	}
	chat.MemberIDs, err = s.listChatMemberIDs(chat.ID)
	if err != nil {
		return model.Chat{}, err
	}
	if chat.Type == model.ChatTypeChannel || chat.Type == model.ChatTypeGroup {
		s.attachOwnerID(&chat)
	}
	return s.personalizeDM(chat, userID), nil
}

func (s *PostgresStore) attachOwnerID(chat *model.Chat) {
	var owner string
	err := s.pool.QueryRow(context.Background(), `
		SELECT user_id FROM chat_members
		WHERE chat_id = $1 AND role = $2
		LIMIT 1
	`, chat.ID, GroupRoleOwner).Scan(&owner)
	if err == nil && owner != "" {
		chat.CreatorID = owner
	}
}

func (s *PostgresStore) ListMessages(chatID, userID string) ([]model.Message, error) {
	messages, _, err := s.ListMessagesPage(chatID, userID, "", 10_000)
	return messages, err
}

func (s *PostgresStore) ListMessagesPage(chatID, userID, beforeID string, limit int) ([]model.Message, bool, error) {
	if !s.isChatMember(chatID, userID) {
		return nil, false, ErrForbidden
	}
	if limit <= 0 {
		limit = 50
	}

	var rows pgx.Rows
	var err error
	if beforeID == "" {
		rows, err = s.pool.Query(context.Background(), `
			SELECT id, chat_id, sender_id, body, pending_id, created_at,
			       envelope_type, ciphertext, sender_device_id
			FROM messages
			WHERE chat_id = $1 AND deleted_at IS NULL
			ORDER BY created_at DESC
			LIMIT $2
		`, chatID, limit+1)
	} else {
		rows, err = s.pool.Query(context.Background(), `
			SELECT id, chat_id, sender_id, body, pending_id, created_at,
			       envelope_type, ciphertext, sender_device_id
			FROM messages
			WHERE chat_id = $1 AND deleted_at IS NULL
			  AND created_at < (
			    SELECT created_at FROM messages WHERE id = $2 AND chat_id = $1
			  )
			ORDER BY created_at DESC
			LIMIT $3
		`, chatID, beforeID, limit+1)
	}
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()

	messages, err := scanMessages(rows)
	if err != nil {
		return nil, false, err
	}

	hasMore := len(messages) > limit
	if hasMore {
		messages = messages[:limit]
	}
	reverseMessages(messages)
	return messages, hasMore, nil
}

func (s *PostgresStore) ListChannelPostsPage(chatID, userID, beforeID string, limit int, galleryOnly bool) ([]model.Message, bool, error) {
	if !s.isChatMember(chatID, userID) {
		return nil, false, ErrForbidden
	}
	if limit <= 0 {
		limit = 50
	}

	// Prefer migration 029 columns; fall back to full page + filter.
	galleryClause := ""
	if galleryOnly {
		galleryClause = ` AND metadata_json ? 'media'
		  AND jsonb_typeof(metadata_json->'media') = 'array'
		  AND jsonb_array_length(metadata_json->'media') > 0`
	}

	var rows pgx.Rows
	var err error
	if beforeID == "" {
		rows, err = s.pool.Query(context.Background(), `
			SELECT id, chat_id, sender_id, body, pending_id, created_at,
			       envelope_type, ciphertext, sender_device_id,
			       COALESCE(reply_to_message_id::text, ''),
			       COALESCE(thread_root_id::text, ''),
			       COALESCE(thread_parent_id::text, ''),
			       COALESCE(visibility, ''),
			       COALESCE(thread_reply_count, 0),
			       COALESCE(metadata_json, '{}'::jsonb)
			FROM messages
			WHERE chat_id = $1 AND deleted_at IS NULL
			  AND (thread_root_id IS NULL)
			`+galleryClause+`
			ORDER BY created_at DESC
			LIMIT $2
		`, chatID, limit+1)
	} else {
		rows, err = s.pool.Query(context.Background(), `
			SELECT id, chat_id, sender_id, body, pending_id, created_at,
			       envelope_type, ciphertext, sender_device_id,
			       COALESCE(reply_to_message_id::text, ''),
			       COALESCE(thread_root_id::text, ''),
			       COALESCE(thread_parent_id::text, ''),
			       COALESCE(visibility, ''),
			       COALESCE(thread_reply_count, 0),
			       COALESCE(metadata_json, '{}'::jsonb)
			FROM messages
			WHERE chat_id = $1 AND deleted_at IS NULL
			  AND (thread_root_id IS NULL)
			  AND created_at < (
			    SELECT created_at FROM messages WHERE id = $2 AND chat_id = $1
			  )
			`+galleryClause+`
			ORDER BY created_at DESC
			LIMIT $3
		`, chatID, beforeID, limit+1)
	}
	if err != nil {
		// Pre-029 fallback.
		all, hasMore, lerr := s.ListMessagesPage(chatID, userID, beforeID, limit)
		if lerr != nil {
			return nil, false, lerr
		}
		filtered := make([]model.Message, 0, len(all))
		for _, m := range all {
			if strings.TrimSpace(m.ThreadRootID) != "" {
				continue
			}
			if galleryOnly && !messageHasMediaMetadata(m) {
				continue
			}
			filtered = append(filtered, m)
		}
		return filtered, hasMore, nil
	}
	defer rows.Close()

	messages, err := scanMessagesExtended(rows)
	if err != nil {
		return nil, false, err
	}
	hasMore := len(messages) > limit
	if hasMore {
		messages = messages[:limit]
	}
	reverseMessages(messages)
	return messages, hasMore, nil
}

func (s *PostgresStore) UpdateChatPreview(chatID, preview string, at time.Time) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE chats SET last_message = $2, last_message_at = $3 WHERE id = $1
	`, chatID, preview, at)
	return err
}

func (s *PostgresStore) AddMessage(message model.Message) (model.Message, error) {
	if !s.isChatMember(message.ChatID, message.SenderID) {
		return model.Message{}, ErrForbidden
	}

	if message.PendingID != "" {
		if existing, err := s.findMessageByPendingID(message.ChatID, message.PendingID); err == nil {
			return existing, nil
		} else if !errors.Is(err, ErrNotFound) {
			return model.Message{}, err
		}
	}

	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return model.Message{}, err
	}
	defer tx.Rollback(context.Background())

	preview := messagePreviewText(message)
	ciphertext, err := decodeMessageCiphertext(message.Ciphertext)
	if err != nil {
		return model.Message{}, err
	}

	_, err = tx.Exec(context.Background(), `
		INSERT INTO messages (
			id, chat_id, sender_id, body, pending_id, created_at,
			envelope_type, ciphertext, sender_device_id
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`, message.ID, message.ChatID, message.SenderID, messageBodyForStorage(message),
		nullableString(message.PendingID), message.CreatedAt,
		nullableEnvelopeType(message.EnvelopeType), nullableBytes(ciphertext), nullableString(message.SenderDeviceID))
	if err != nil {
		return model.Message{}, err
	}

	_, err = tx.Exec(context.Background(), `
		UPDATE chats SET last_message = $2, last_message_at = $3 WHERE id = $1
	`, message.ChatID, preview, message.CreatedAt)
	if err != nil {
		return model.Message{}, err
	}

	if err := tx.Commit(context.Background()); err != nil {
		return model.Message{}, err
	}
	return message, nil
}

func (s *PostgresStore) getChatByDMKey(dmKey, userID string) (model.Chat, error) {
	row := s.pool.QueryRow(context.Background(), `
		SELECT id, title, chat_type, dm_key, last_message, last_message_at, created_at, avatar_url
		FROM chats WHERE dm_key = $1
	`, dmKey)

	chat, err := scanChatFromRow(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Chat{}, ErrNotFound
	}
	if err != nil {
		return model.Chat{}, err
	}
	if !s.isChatMember(chat.ID, userID) {
		return model.Chat{}, ErrForbidden
	}

	chat.MemberIDs, err = s.listChatMemberIDs(chat.ID)
	return chat, err
}

func (s *PostgresStore) getChatByID(chatID string) (model.Chat, error) {
	row := s.pool.QueryRow(context.Background(), `
		SELECT id, title, chat_type, dm_key, last_message, last_message_at, created_at, avatar_url
		FROM chats WHERE id = $1
	`, chatID)

	chat, err := scanChatFromRow(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Chat{}, ErrNotFound
	}
	return chat, err
}

func (s *PostgresStore) listChatMemberIDs(chatID string) ([]string, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT user_id FROM chat_members WHERE chat_id = $1 ORDER BY joined_at ASC
	`, chatID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	ids := make([]string, 0)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (s *PostgresStore) isChatMember(chatID, userID string) bool {
	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(SELECT 1 FROM chat_members WHERE chat_id = $1 AND user_id = $2)
	`, chatID, userID).Scan(&exists)
	return err == nil && exists
}

func (s *PostgresStore) FindMessageByPendingID(chatID, pendingID string) (model.Message, error) {
	return s.findMessageByPendingID(chatID, pendingID)
}

func (s *PostgresStore) findMessageByPendingID(chatID, pendingID string) (model.Message, error) {
	var message model.Message
	var pending *string
	var body *string
	var envelopeType *int
	var ciphertext []byte
	var senderDeviceID *string
	err := s.pool.QueryRow(context.Background(), `
		SELECT id, chat_id, sender_id, body, pending_id, created_at,
		       envelope_type, ciphertext, sender_device_id
		FROM messages WHERE chat_id = $1 AND pending_id = $2
	`, chatID, pendingID).Scan(
		&message.ID, &message.ChatID, &message.SenderID, &body, &pending, &message.CreatedAt,
		&envelopeType, &ciphertext, &senderDeviceID,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Message{}, ErrNotFound
	}
	if body != nil {
		message.Body = *body
	}
	if pending != nil {
		message.PendingID = *pending
	}
	message.EnvelopeType = envelopeType
	if len(ciphertext) > 0 {
		message.Ciphertext = encodeMessageCiphertext(ciphertext)
	}
	if senderDeviceID != nil {
		message.SenderDeviceID = *senderDeviceID
	}
	return message, err
}

func (s *PostgresStore) personalizeDM(chat model.Chat, currentUserID string) model.Chat {
	if chat.Type != model.ChatTypeDM {
		return chat
	}
	for _, memberID := range chat.MemberIDs {
		if memberID == currentUserID {
			continue
		}
		user, err := s.GetUserByID(memberID)
		if err == nil {
			chat.Title = user.Username
		}
		break
	}
	return chat
}

func scanChatFromRows(rows pgx.Rows) (model.Chat, error) {
	return scanChat(rows.Scan)
}

func scanChatFromRow(row pgx.Row) (model.Chat, error) {
	return scanChat(row.Scan)
}

func scanChat(scan func(dest ...any) error) (model.Chat, error) {
	var chat model.Chat
	var dmKey, lastMessage, avatarURL *string
	var lastMessageAt *time.Time
	err := scan(&chat.ID, &chat.Title, &chat.Type, &dmKey, &lastMessage, &lastMessageAt, &chat.CreatedAt, &avatarURL)
	if err != nil {
		return model.Chat{}, err
	}
	if dmKey != nil {
		chat.DMKey = *dmKey
	}
	if lastMessage != nil {
		chat.LastMessage = *lastMessage
	}
	if lastMessageAt != nil {
		chat.LastMessageAt = *lastMessageAt
	}
	if avatarURL != nil {
		chat.AvatarURL = *avatarURL
	}
	return chat, nil
}

func (s *PostgresStore) PermanentDeleteChat(chatID string) error {
	ctx := context.Background()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Wipe messages and members first; remaining group_* rows CASCADE from chats(id).
	if _, err := tx.Exec(ctx, `DELETE FROM messages WHERE chat_id = $1`, chatID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM chat_members WHERE chat_id = $1`, chatID); err != nil {
		return err
	}
	tag, err := tx.Exec(ctx, `DELETE FROM chats WHERE id = $1`, chatID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(ctx)
}

func (s *PostgresStore) UpdateChatAvatar(chatID, avatarURL string) error {
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE chats SET avatar_url = $2, updated_at = NOW() WHERE id = $1
	`, chatID, avatarURL)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func reverseMessages(messages []model.Message) {
	for i, j := 0, len(messages)-1; i < j; i, j = i+1, j-1 {
		messages[i], messages[j] = messages[j], messages[i]
	}
}

func scanMessages(rows pgx.Rows) ([]model.Message, error) {
	messages := make([]model.Message, 0)
	for rows.Next() {
		var message model.Message
		var pendingID *string
		var body *string
		var envelopeType *int
		var ciphertext []byte
		var senderDeviceID *string
		if err := rows.Scan(
			&message.ID, &message.ChatID, &message.SenderID, &body, &pendingID, &message.CreatedAt,
			&envelopeType, &ciphertext, &senderDeviceID,
		); err != nil {
			return nil, err
		}
		if body != nil {
			message.Body = *body
		}
		if pendingID != nil {
			message.PendingID = *pendingID
		}
		message.EnvelopeType = envelopeType
		if len(ciphertext) > 0 {
			message.Ciphertext = encodeMessageCiphertext(ciphertext)
		}
		if senderDeviceID != nil {
			message.SenderDeviceID = *senderDeviceID
		}
		messages = append(messages, message)
	}
	return messages, rows.Err()
}

func scanMessagesExtended(rows pgx.Rows) ([]model.Message, error) {
	messages := make([]model.Message, 0)
	for rows.Next() {
		var message model.Message
		var pendingID *string
		var body *string
		var envelopeType *int
		var ciphertext []byte
		var senderDeviceID *string
		var replyTo, threadRoot, threadParent, visibility string
		var replyCount int
		var metadata []byte
		if err := rows.Scan(
			&message.ID, &message.ChatID, &message.SenderID, &body, &pendingID, &message.CreatedAt,
			&envelopeType, &ciphertext, &senderDeviceID,
			&replyTo, &threadRoot, &threadParent, &visibility, &replyCount, &metadata,
		); err != nil {
			return nil, err
		}
		if body != nil {
			message.Body = *body
		}
		if pendingID != nil {
			message.PendingID = *pendingID
		}
		message.EnvelopeType = envelopeType
		if len(ciphertext) > 0 {
			message.Ciphertext = encodeMessageCiphertext(ciphertext)
		}
		if senderDeviceID != nil {
			message.SenderDeviceID = *senderDeviceID
		}
		message.ReplyToMessageID = replyTo
		message.ThreadRootID = threadRoot
		message.ThreadParentID = threadParent
		message.Visibility = visibility
		message.ThreadReplyCount = replyCount
		if len(metadata) > 0 && string(metadata) != "{}" && string(metadata) != "null" {
			var meta map[string]any
			if err := json.Unmarshal(metadata, &meta); err == nil {
				message.Metadata = meta
			}
		}
		messages = append(messages, message)
	}
	return messages, rows.Err()
}

func makeDMKey(userID, otherUserID string) string {
	ids := []string{userID, otherUserID}
	sort.Strings(ids)
	return ids[0] + ":" + ids[1]
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func messageBodyForStorage(message model.Message) string {
	if strings.TrimSpace(message.Ciphertext) != "" {
		return ""
	}
	return message.Body
}

func nullableEnvelopeType(value *int) any {
	if value == nil {
		return nil
	}
	return *value
}

func messagePreviewText(message model.Message) string {
	if strings.TrimSpace(message.Ciphertext) != "" {
		return "🔒 Сообщение"
	}
	return message.Body
}

func decodeMessageCiphertext(value string) ([]byte, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil, nil
	}
	raw, err := base64.StdEncoding.DecodeString(trimmed)
	if err != nil {
		return nil, fmt.Errorf("invalid ciphertext encoding")
	}
	return raw, nil
}

func encodeMessageCiphertext(value []byte) string {
	return base64.StdEncoding.EncodeToString(value)
}

func nullableDMKey(chat model.Chat) any {
	if chat.DMKey == "" {
		return nil
	}
	return chat.DMKey
}

func nullableTime(value time.Time) any {
	if value.IsZero() {
		return nil
	}
	return value
}
