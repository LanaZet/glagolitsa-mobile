// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/base64"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

// callSelectCols is the stable projection for CallSession. Nullable UUIDs are
// coerced to empty strings for JSON compatibility with older clients.
const callSelectCols = `
	id,
	caller_id,
	COALESCE(callee_id::text, ''),
	call_type,
	status,
	livekit_room_id,
	caller_device_id,
	low_bandwidth_mode,
	created_at,
	accepted_at,
	connected_at,
	ended_at,
	duration_sec,
	COALESCE(chat_id::text, ''),
	COALESCE(started_by_user_id::text, ''),
	COALESCE(call_scope, 'dm'),
	COALESCE(selected_region, 'primary'),
	COALESCE(route_class, 'single_region'),
	COALESCE(policy_version, 1)
`

const callInsertSQL = `
	INSERT INTO calls (
		id, caller_id, callee_id, call_type, status, livekit_room_id,
		caller_device_id, low_bandwidth_mode, created_at,
		chat_id, started_by_user_id, call_scope, selected_region, route_class, policy_version
	) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
`

func (s *PostgresStore) CreateCall(call model.CallSession) (model.CallSession, error) {
	call = normalizeCallDefaults(call)

	_, err := s.pool.Exec(context.Background(), callInsertSQL, callInsertArgs(call)...)
	if err != nil {
		return model.CallSession{}, err
	}
	return call, nil
}

func (s *PostgresStore) CreateCallIfAvailable(call model.CallSession, participantUserIDs []string) (model.CallSession, error) {
	call = normalizeCallDefaults(call)
	userIDs := uniqueNonEmptyStrings(participantUserIDs)
	if len(userIDs) == 0 {
		userIDs = uniqueNonEmptyStrings([]string{call.CallerID, call.CalleeID})
	}
	sort.Strings(userIDs)

	ctx := context.Background()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return model.CallSession{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	// try-lock: never hang create on a stuck peer transaction (maps to 409 busy).
	for _, userID := range userIDs {
		var locked bool
		if err := tx.QueryRow(ctx, `SELECT pg_try_advisory_xact_lock(hashtext($1))`, "calling:user:"+userID).Scan(&locked); err != nil {
			return model.CallSession{}, err
		}
		if !locked {
			return model.CallSession{}, ErrBusy
		}
	}

	var existingID string
	err = tx.QueryRow(ctx, `
		SELECT c.id::text
		FROM calls c
		WHERE c.status IN ($2, $3, $4)
		  AND (
		       c.caller_id::text = ANY($1)
		    OR c.callee_id::text = ANY($1)
		    OR EXISTS (
		        SELECT 1 FROM call_participants p
		        WHERE p.call_id = c.id
		          AND p.user_id::text = ANY($1)
		          AND p.left_at IS NULL
		    )
		  )
		ORDER BY c.created_at DESC
		LIMIT 1
	`, userIDs, model.CallStatusRinging, model.CallStatusConnecting, model.CallStatusActive).Scan(&existingID)
	if err == nil {
		return model.CallSession{}, ErrAlreadyExists
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return model.CallSession{}, err
	}

	if _, err := tx.Exec(ctx, callInsertSQL, callInsertArgs(call)...); err != nil {
		return model.CallSession{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return model.CallSession{}, err
	}
	return call, nil
}

func callInsertArgs(call model.CallSession) []any {
	var callee any
	if call.CalleeID != "" {
		callee = call.CalleeID
	}
	var chatID any
	if call.ChatID != "" {
		chatID = call.ChatID
	}
	var startedBy any
	if call.StartedByUserID != "" {
		startedBy = call.StartedByUserID
	}
	return []any{
		call.ID, call.CallerID, callee, call.CallType, call.Status,
		call.LivekitRoomID, call.CallerDeviceID, call.LowBandwidthMode, call.CreatedAt,
		chatID, startedBy, call.CallScope, call.SelectedRegion, call.RouteClass, call.PolicyVersion,
	}
}

func (s *PostgresStore) GetCall(callID string) (model.CallSession, error) {
	return scanCall(s.pool.QueryRow(context.Background(), `
		SELECT `+callSelectCols+` FROM calls WHERE id = $1
	`, callID))
}

func (s *PostgresStore) FindLiveCallForChat(chatID string) (model.CallSession, error) {
	if chatID == "" {
		return model.CallSession{}, ErrNotFound
	}
	call, err := scanCall(s.pool.QueryRow(context.Background(), `
		SELECT `+callSelectCols+`
		FROM calls
		WHERE chat_id = $1
		  AND status IN ($2, $3, $4)
		ORDER BY created_at DESC
		LIMIT 1
	`, chatID, model.CallStatusRinging, model.CallStatusConnecting, model.CallStatusActive))
	if err != nil {
		return model.CallSession{}, err
	}
	return call, nil
}

func (s *PostgresStore) ListCallsForUser(userID string, limit int) ([]model.CallSession, error) {
	if limit <= 0 {
		limit = 50
	}
	// History is participant-centric; legacy caller/callee still works via
	// backfilled call_participants and OR fallback for pre-migration rows.
	rows, err := s.pool.Query(context.Background(), `
		SELECT `+callSelectCols+`
		FROM calls c
		WHERE c.caller_id = $1
		   OR c.callee_id = $1
		   OR EXISTS (
		        SELECT 1 FROM call_participants p
		        WHERE p.call_id = c.id AND p.user_id = $1
		   )
		ORDER BY c.created_at DESC
		LIMIT $2
	`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	calls := make([]model.CallSession, 0)
	for rows.Next() {
		call, err := scanCallRow(rows)
		if err != nil {
			return nil, err
		}
		calls = append(calls, call)
	}
	return calls, rows.Err()
}

func (s *PostgresStore) UpdateCallStatus(callID, status string, at time.Time) (model.CallSession, error) {
	query := `UPDATE calls SET status = $2`
	args := []any{callID, status}
	switch status {
	case model.CallStatusConnecting:
		query += `, accepted_at = $3`
		args = append(args, at)
	case model.CallStatusRejected, model.CallStatusMissed:
		query += `, ended_at = $3`
		args = append(args, at)
	}
	query += ` WHERE id = $1 RETURNING ` + callSelectCols

	row := s.pool.QueryRow(context.Background(), query, args...)
	return scanCall(row)
}

func (s *PostgresStore) MarkCallConnected(callID string, at time.Time) (model.CallSession, error) {
	call, err := scanCall(s.pool.QueryRow(context.Background(), `
		UPDATE calls
		SET status = $2, connected_at = $3
		WHERE id = $1
		  AND status IN ($4, $5, $6)
		RETURNING `+callSelectCols+`
	`, callID, model.CallStatusActive, at,
		model.CallStatusRinging, model.CallStatusConnecting, model.CallStatusActive))
	if err == nil {
		return call, nil
	}
	return scanCall(s.pool.QueryRow(context.Background(), `
		SELECT `+callSelectCols+` FROM calls WHERE id = $1
	`, callID))
}

func (s *PostgresStore) EndCall(callID string, at time.Time) (model.CallSession, error) {
	return scanCall(s.pool.QueryRow(context.Background(), `
		UPDATE calls
		SET status = $2,
		    ended_at = $3,
		    duration_sec = GREATEST(0, EXTRACT(EPOCH FROM ($3 - COALESCE(connected_at, accepted_at, created_at)))::int)
		WHERE id = $1
		RETURNING `+callSelectCols+`
	`, callID, model.CallStatusEnded, at))
}

func (s *PostgresStore) SetLowBandwidthMode(callID string, enabled bool) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE calls SET low_bandwidth_mode = $2 WHERE id = $1
	`, callID, enabled)
	return err
}

func (s *PostgresStore) UpsertParticipant(callID, userID, deviceID string, joinedAt, leftAt *time.Time) error {
	return s.UpsertCallParticipant(model.CallParticipant{
		CallID:      callID,
		UserID:      userID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleJoined,
		InviteState: model.CallInviteStateJoined,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    joinedAt,
		LeftAt:      leftAt,
	})
}

func (s *PostgresStore) UpsertCallParticipant(p model.CallParticipant) error {
	if p.Role == "" {
		p.Role = model.CallParticipantRoleJoined
	}
	if p.InviteState == "" {
		p.InviteState = model.CallInviteStateJoined
	}
	if p.MediaState == "" {
		p.MediaState = model.CallMediaAudioOnly
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO call_participants (
			call_id, user_id, device_id, role, invite_state, media_state, joined_at, left_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (call_id, user_id, device_id) DO UPDATE SET
			role = COALESCE(NULLIF(EXCLUDED.role, ''), call_participants.role),
			invite_state = COALESCE(NULLIF(EXCLUDED.invite_state, ''), call_participants.invite_state),
			media_state = COALESCE(NULLIF(EXCLUDED.media_state, ''), call_participants.media_state),
			joined_at = COALESCE(EXCLUDED.joined_at, call_participants.joined_at),
			left_at = COALESCE(EXCLUDED.left_at, call_participants.left_at)
	`, p.CallID, p.UserID, p.DeviceID, p.Role, p.InviteState, p.MediaState, p.JoinedAt, p.LeftAt)
	return err
}

func (s *PostgresStore) ListCallParticipants(callID string) ([]model.CallParticipant, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT call_id, user_id, device_id, role, invite_state, media_state, joined_at, left_at
		FROM call_participants
		WHERE call_id = $1
		ORDER BY joined_at NULLS LAST, user_id
	`, callID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]model.CallParticipant, 0)
	for rows.Next() {
		var p model.CallParticipant
		if err := rows.Scan(
			&p.CallID, &p.UserID, &p.DeviceID, &p.Role, &p.InviteState, &p.MediaState,
			&p.JoinedAt, &p.LeftAt,
		); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *PostgresStore) IsCallParticipant(callID, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM call_participants WHERE call_id = $1 AND user_id = $2
		) OR EXISTS(
			SELECT 1 FROM calls WHERE id = $1 AND (caller_id = $2 OR callee_id = $2)
		)
	`, callID, userID).Scan(&ok)
	return ok, err
}

// IsGroupMember reports whether userID is a member of chatID (group/channel).
// Used by calling invites so non-members cannot join group calls.
func (s *PostgresStore) IsGroupMember(chatID, userID string) (bool, error) {
	if chatID == "" || userID == "" {
		return false, nil
	}
	return s.isChatMember(chatID, userID), nil
}

func (s *PostgresStore) ListStaleCalls(ringingBefore, connectingBefore, activeBefore time.Time, limit int) ([]model.CallSession, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT `+callSelectCols+`
		FROM calls
		WHERE
			(status = $1 AND created_at < $2)
			OR (status = $3 AND COALESCE(accepted_at, created_at) < $4)
			OR (status = $5 AND COALESCE(connected_at, accepted_at, created_at) < $6)
		ORDER BY created_at ASC
		LIMIT $7
	`, model.CallStatusRinging, ringingBefore,
		model.CallStatusConnecting, connectingBefore,
		model.CallStatusActive, activeBefore,
		limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]model.CallSession, 0)
	for rows.Next() {
		call, err := scanCallRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, call)
	}
	return out, rows.Err()
}

func (s *PostgresStore) CreateCallInvite(inv model.CallInvite) (model.CallInvite, error) {
	if inv.ID == "" {
		inv.ID = uuid.NewString()
	}
	if inv.State == "" {
		inv.State = model.CallInvitePending
	}
	if inv.CreatedAt.IsZero() {
		inv.CreatedAt = NowUTC()
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO call_invites (
			id, call_id, invited_user_id, invited_by_user_id, state, created_at, expires_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (call_id, invited_user_id) DO UPDATE SET
			state = EXCLUDED.state,
			invited_by_user_id = EXCLUDED.invited_by_user_id,
			expires_at = EXCLUDED.expires_at
	`, inv.ID, inv.CallID, inv.InvitedUserID, inv.InvitedByUserID, inv.State, inv.CreatedAt, inv.ExpiresAt)
	if err != nil {
		return model.CallInvite{}, err
	}
	return inv, nil
}

func (s *PostgresStore) StoreCallKeyOffers(callID string, offers []CallKeyOfferRecord) error {
	for _, offer := range offers {
		_, err := s.pool.Exec(context.Background(), `
			INSERT INTO call_key_offers (
				id, call_id, source_user_id, source_device_id,
				target_user_id, target_device_id, envelope_type, encrypted_key
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
			ON CONFLICT (call_id, source_device_id, target_device_id) DO UPDATE
			SET envelope_type = EXCLUDED.envelope_type,
			    encrypted_key = EXCLUDED.encrypted_key,
			    created_at = NOW()
		`, uuid.NewString(), callID, offer.SourceUserID, offer.SourceDeviceID,
			offer.TargetUserID, offer.TargetDeviceID, offer.EnvelopeType, offer.EncryptedKey)
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *PostgresStore) ListCallKeyOffersForDevice(callID, userID, deviceID string) ([]model.CallKeyOffer, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT source_user_id, source_device_id, target_user_id, target_device_id,
		       envelope_type, encrypted_key, created_at
		FROM call_key_offers
		WHERE call_id = $1 AND target_user_id = $2 AND target_device_id = $3
		ORDER BY created_at ASC
	`, callID, userID, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	offers := make([]model.CallKeyOffer, 0)
	for rows.Next() {
		var offer model.CallKeyOffer
		var key []byte
		if err := rows.Scan(
			&offer.SourceUserID, &offer.SourceDeviceID,
			&offer.TargetUserID, &offer.TargetDeviceID,
			&offer.EnvelopeType, &key, &offer.CreatedAt,
		); err != nil {
			return nil, err
		}
		offer.EncryptedKey = base64.StdEncoding.EncodeToString(key)
		offers = append(offers, offer)
	}
	return offers, rows.Err()
}

func scanCall(row pgx.Row) (model.CallSession, error) {
	var call model.CallSession
	err := row.Scan(
		&call.ID, &call.CallerID, &call.CalleeID, &call.CallType, &call.Status,
		&call.LivekitRoomID, &call.CallerDeviceID, &call.LowBandwidthMode, &call.CreatedAt,
		&call.AcceptedAt, &call.ConnectedAt, &call.EndedAt, &call.DurationSec,
		&call.ChatID, &call.StartedByUserID, &call.CallScope, &call.SelectedRegion,
		&call.RouteClass, &call.PolicyVersion,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.CallSession{}, ErrNotFound
	}
	return call, err
}

func scanCallRow(rows pgx.Rows) (model.CallSession, error) {
	var call model.CallSession
	err := rows.Scan(
		&call.ID, &call.CallerID, &call.CalleeID, &call.CallType, &call.Status,
		&call.LivekitRoomID, &call.CallerDeviceID, &call.LowBandwidthMode, &call.CreatedAt,
		&call.AcceptedAt, &call.ConnectedAt, &call.EndedAt, &call.DurationSec,
		&call.ChatID, &call.StartedByUserID, &call.CallScope, &call.SelectedRegion,
		&call.RouteClass, &call.PolicyVersion,
	)
	return call, err
}
