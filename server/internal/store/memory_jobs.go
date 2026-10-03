// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/jobs"
)

func (s *MemoryStore) CreateJob(job jobs.Job) (jobs.Job, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
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
	if s.jobs == nil {
		s.jobs = make(map[string]jobs.Job)
	}
	s.jobs[job.ID] = job
	return job, nil
}

func (s *MemoryStore) ClaimNextJob(jobType string, now time.Time) (jobs.Job, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var oldest *jobs.Job
	for id, job := range s.jobs {
		if job.Type != jobType || job.Status != jobs.StatusPending {
			continue
		}
		if oldest == nil || job.CreateAt.Before(oldest.CreateAt) {
			copy := s.jobs[id]
			oldest = &copy
		}
	}
	if oldest == nil {
		return jobs.Job{}, false, nil
	}
	oldest.Status = jobs.StatusInProgress
	oldest.StartAt = &now
	oldest.LastActivityAt = now
	s.jobs[oldest.ID] = *oldest
	return *oldest, true, nil
}

func (s *MemoryStore) SetJobSuccess(jobID string, progress int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job, ok := s.jobs[jobID]
	if !ok {
		return ErrNotFound
	}
	job.Status = jobs.StatusSuccess
	job.Progress = progress
	job.LastActivityAt = NowUTC()
	s.jobs[jobID] = job
	return nil
}

func (s *MemoryStore) SetJobError(jobID string, lastError string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	job, ok := s.jobs[jobID]
	if !ok {
		return ErrNotFound
	}
	job.Status = jobs.StatusError
	job.LastError = lastError
	job.LastActivityAt = NowUTC()
	s.jobs[jobID] = job
	return nil
}

func (s *MemoryStore) EnqueueScheduledJob(jobType string, _ time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, job := range s.jobs {
		if job.Type == jobType && (job.Status == jobs.StatusPending || job.Status == jobs.StatusInProgress) {
			return false, nil
		}
	}
	if s.jobs == nil {
		s.jobs = make(map[string]jobs.Job)
	}
	now := NowUTC()
	job := jobs.Job{
		ID:             uuid.NewString(),
		Type:           jobType,
		Status:         jobs.StatusPending,
		Data:           map[string]any{},
		CreateAt:       now,
		LastActivityAt: now,
	}
	s.jobs[job.ID] = job
	return true, nil
}
