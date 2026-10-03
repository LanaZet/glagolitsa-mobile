// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/jobs"
)

func (s *PostgresStore) CreateJob(job jobs.Job) (jobs.Job, error) {
	if job.ID == "" {
		job.ID = uuid.NewString()
	}
	if job.Status == "" {
		job.Status = jobs.StatusPending
	}
	if job.Data == nil {
		job.Data = map[string]any{}
	}
	now := NowUTC()
	if job.CreateAt.IsZero() {
		job.CreateAt = now
	}
	job.LastActivityAt = now

	meta, err := json.Marshal(job.Data)
	if err != nil {
		return jobs.Job{}, err
	}

	_, err = s.pool.Exec(context.Background(), `
		INSERT INTO jobs (id, type, status, progress, data, create_at, last_activity_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`, job.ID, job.Type, job.Status, job.Progress, meta, job.CreateAt, job.LastActivityAt)
	if err != nil {
		return jobs.Job{}, err
	}
	return job, nil
}

func (s *PostgresStore) ClaimNextJob(jobType string, now time.Time) (jobs.Job, bool, error) {
	tx, err := s.pool.Begin(context.Background())
	if err != nil {
		return jobs.Job{}, false, err
	}
	defer tx.Rollback(context.Background())

	var job jobs.Job
	var meta []byte
	var startAt *time.Time
	err = tx.QueryRow(context.Background(), `
		SELECT id, type, status, progress, data, COALESCE(last_error, ''), create_at, start_at, last_activity_at
		FROM jobs
		WHERE type = $1 AND status = $2
		ORDER BY create_at ASC
		LIMIT 1
		FOR UPDATE SKIP LOCKED
	`, jobType, jobs.StatusPending).Scan(
		&job.ID, &job.Type, &job.Status, &job.Progress, &meta, &job.LastError,
		&job.CreateAt, &startAt, &job.LastActivityAt,
	)
	if err == pgx.ErrNoRows {
		return jobs.Job{}, false, nil
	}
	if err != nil {
		return jobs.Job{}, false, err
	}
	if len(meta) > 0 {
		_ = json.Unmarshal(meta, &job.Data)
	}
	job.StartAt = startAt

	_, err = tx.Exec(context.Background(), `
		UPDATE jobs
		SET status = $2, start_at = $3, last_activity_at = $3
		WHERE id = $1
	`, job.ID, jobs.StatusInProgress, now)
	if err != nil {
		return jobs.Job{}, false, err
	}
	if err := tx.Commit(context.Background()); err != nil {
		return jobs.Job{}, false, err
	}
	job.Status = jobs.StatusInProgress
	job.StartAt = &now
	job.LastActivityAt = now
	return job, true, nil
}

func (s *PostgresStore) SetJobSuccess(jobID string, progress int64) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE jobs
		SET status = $2, progress = $3, last_activity_at = $4, last_error = NULL
		WHERE id = $1
	`, jobID, jobs.StatusSuccess, progress, NowUTC())
	return err
}

func (s *PostgresStore) SetJobError(jobID string, lastError string) error {
	_, err := s.pool.Exec(context.Background(), `
		UPDATE jobs
		SET status = $2, last_error = $3, last_activity_at = $4
		WHERE id = $1
	`, jobID, jobs.StatusError, lastError, NowUTC())
	return err
}

func (s *PostgresStore) EnqueueScheduledJob(jobType string, notBefore time.Time) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM jobs
			WHERE type = $1 AND status IN ('pending', 'in_progress')
		)
	`, jobType).Scan(&exists)
	if err != nil {
		return false, err
	}
	if exists {
		return false, nil
	}
	_, err = s.CreateJob(jobs.Job{Type: jobType, CreateAt: notBefore})
	return err == nil, err
}
