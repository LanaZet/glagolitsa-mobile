// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) CreateProfile(userID, username string, user model.User) (model.User, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO profiles (
			user_id, username, email, display_name, status, bio,
			avatar_url, presence, nickname, position
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
	`, userID, username, user.Email, user.DisplayName, user.Status, user.Bio,
		user.AvatarURL, user.Presence, user.Nickname, user.Position)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			return model.User{}, ErrAlreadyExists
		}
		return model.User{}, err
	}
	user.ID = userID
	user.Username = username
	return user, nil
}

func (s *PostgresStore) GetProfile(userID string) (model.User, error) {
	var user model.User
	err := s.pool.QueryRow(context.Background(), `
		SELECT p.user_id, p.username, p.email, p.display_name, p.status, p.bio,
		       p.avatar_url, p.presence, p.nickname, p.position, u.created_at
		FROM profiles p
		JOIN users u ON u.id = p.user_id
		WHERE p.user_id = $1
	`, userID).Scan(
		&user.ID, &user.Username, &user.Email, &user.DisplayName, &user.Status, &user.Bio,
		&user.AvatarURL, &user.Presence, &user.Nickname, &user.Position, &user.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.User{}, ErrNotFound
	}
	return user, err
}

func (s *PostgresStore) UpdateProfile(userID string, update model.UpdateProfileRequest) (model.User, error) {
	user, err := s.GetProfile(userID)
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
		UPDATE profiles
		SET display_name = $2, status = $3, bio = $4,
		    avatar_url = $5, presence = $6, nickname = $7, position = $8,
		    updated_at = NOW()
		WHERE user_id = $1
	`, user.ID, user.DisplayName, user.Status, user.Bio,
		user.AvatarURL, user.Presence, user.Nickname, user.Position)
	if err != nil {
		return model.User{}, err
	}
	return user, nil
}

const activeAccountFilter = `u.account_status = '` + model.AccountStatusActive + `'`

func (s *PostgresStore) SearchProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error) {
	if limit <= 0 {
		limit = 20
	}
	query = strings.ToLower(strings.TrimSpace(query))
	if query == "" {
		return []model.UserSearchHit{}, nil
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT p.user_id, p.username, p.display_name, p.status, p.bio, p.avatar_url
		FROM profiles p
		JOIN users u ON u.id = p.user_id
		WHERE p.user_id <> $2
		  AND `+activeAccountFilter+`
		  AND (
		    lower(p.username) LIKE $1 || '%'
		    OR lower(coalesce(p.display_name, '')) LIKE $1 || '%'
		  )
		ORDER BY
		  CASE WHEN lower(p.username) = $1 THEN 0 ELSE 1 END,
		  p.username ASC
		LIMIT $3
	`, query, excludeUserID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanProfileSearchHits(rows)
}

func (s *PostgresStore) AutocompleteProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error) {
	if limit <= 0 {
		limit = 8
	}
	query = strings.ToLower(strings.TrimSpace(query))
	if len(query) < 2 {
		return []model.UserSearchHit{}, nil
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT p.user_id, p.username, p.display_name, p.status, p.bio, p.avatar_url
		FROM profiles p
		JOIN users u ON u.id = p.user_id
		WHERE p.user_id <> $2
		  AND `+activeAccountFilter+`
		  AND lower(p.username) LIKE $1 || '%'
		ORDER BY
		  CASE WHEN lower(p.username) = $1 THEN 0 ELSE 1 END,
		  char_length(p.username),
		  p.username ASC
		LIMIT $3
	`, query, excludeUserID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanProfileSearchHits(rows)
}

func (s *PostgresStore) LookupProfileByUsername(username, excludeUserID string) (model.UserSearchHit, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	if username == "" {
		return model.UserSearchHit{}, ErrNotFound
	}
	var hit model.UserSearchHit
	err := s.pool.QueryRow(context.Background(), `
		SELECT p.user_id, p.username, p.display_name, p.status, p.bio, p.avatar_url
		FROM profiles p
		JOIN users u ON u.id = p.user_id
		WHERE lower(p.username) = $1
		  AND p.user_id <> $2
		  AND `+activeAccountFilter+`
	`, username, excludeUserID).Scan(
		&hit.ID, &hit.Username, &hit.DisplayName, &hit.Status, &hit.Bio, &hit.AvatarURL,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.UserSearchHit{}, ErrNotFound
	}
	if err != nil {
		return model.UserSearchHit{}, err
	}
	return hit, nil
}

func scanProfileSearchHits(rows pgx.Rows) ([]model.UserSearchHit, error) {
	hits := make([]model.UserSearchHit, 0)
	for rows.Next() {
		var hit model.UserSearchHit
		if err := rows.Scan(&hit.ID, &hit.Username, &hit.DisplayName, &hit.Status, &hit.Bio, &hit.AvatarURL); err != nil {
			return nil, err
		}
		hits = append(hits, hit)
	}
	return hits, rows.Err()
}

func (s *PostgresStore) GetUsername(userID string) (string, error) {
	var username string
	err := s.pool.QueryRow(context.Background(), `
		SELECT username FROM profiles WHERE user_id = $1
	`, userID).Scan(&username)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return username, err
}

// GetProfilesByIDs returns public profile cards for chat-list and chat-info hydration.
func (s *PostgresStore) GetProfilesByIDs(userIDs []string, excludeUserID string) ([]model.UserSearchHit, error) {
	ids := uniqueNonEmptyIDs(userIDs, excludeUserID)
	if len(ids) == 0 {
		return []model.UserSearchHit{}, nil
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT p.user_id, p.username, p.display_name, p.status, p.bio, p.avatar_url
		FROM profiles p
		JOIN users u ON u.id = p.user_id
		WHERE p.user_id = ANY($1)
		  AND `+activeAccountFilter+`
	`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanProfileSearchHits(rows)
}

func uniqueNonEmptyIDs(userIDs []string, excludeUserID string) []string {
	seen := make(map[string]struct{}, len(userIDs))
	out := make([]string, 0, len(userIDs))
	for _, id := range userIDs {
		id = strings.TrimSpace(id)
		if id == "" || id == excludeUserID {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
		if len(out) >= 50 {
			break
		}
	}
	return out
}
