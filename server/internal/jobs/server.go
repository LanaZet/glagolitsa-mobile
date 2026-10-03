// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package jobs

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/metrics"
)

// Server — Mattermost channels/jobs/server.go (workers + schedulers + watcher).
type Server struct {
	store       Store
	metrics     metrics.Interface
	workers     map[string]Worker
	schedules   []schedule
	watcherTick time.Duration
	mu          sync.Mutex
}

type schedule struct {
	jobType  string
	interval time.Duration
}

func NewServer(store Store, m metrics.Interface) *Server {
	if m == nil {
		m = metrics.Noop{}
	}
	return &Server{
		store:       store,
		metrics:     m,
		workers:     make(map[string]Worker),
		watcherTick: time.Second,
	}
}

func (s *Server) RegisterWorker(worker Worker) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.workers[worker.Type()] = worker
}

func (s *Server) RegisterSchedule(jobType string, interval time.Duration) {
	s.schedules = append(s.schedules, schedule{jobType: jobType, interval: interval})
}

func (s *Server) Start(ctx context.Context) {
	go s.runSchedulers(ctx)
	go s.runWatcher(ctx)
}

func (s *Server) runSchedulers(ctx context.Context) {
	for _, sched := range s.schedules {
		go s.runScheduler(ctx, sched)
	}
}

func (s *Server) runScheduler(ctx context.Context, sched schedule) {
	ticker := time.NewTicker(sched.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			created, err := s.store.EnqueueScheduledJob(sched.jobType, time.Now().UTC())
			if err != nil {
				slog.Warn("job_schedule_enqueue_failed", "type", sched.jobType, "error", err)
				continue
			}
			if created {
				slog.Debug("job_scheduled", "type", sched.jobType)
			}
		}
	}
}

func (s *Server) runWatcher(ctx context.Context) {
	ticker := time.NewTicker(s.watcherTick)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.claimAndRun(ctx)
		}
	}
}

func (s *Server) claimAndRun(ctx context.Context) {
	s.mu.Lock()
	types := make([]string, 0, len(s.workers))
	for jobType := range s.workers {
		types = append(types, jobType)
	}
	s.mu.Unlock()

	for _, jobType := range types {
		s.mu.Lock()
		worker := s.workers[jobType]
		s.mu.Unlock()
		if worker == nil {
			continue
		}

		job, ok, err := s.store.ClaimNextJob(jobType, time.Now().UTC())
		if err != nil {
			slog.Warn("job_claim_failed", "type", jobType, "error", err)
			continue
		}
		if !ok {
			continue
		}

		s.metrics.IncrementJobActive(jobType)
		runErr := worker.Execute(ctx, job)
		s.metrics.DecrementJobActive(jobType)
		if runErr != nil {
			_ = s.store.SetJobError(job.ID, runErr.Error())
			slog.Warn("job_failed", "id", job.ID, "type", job.Type, "error", runErr)
			continue
		}
		_ = s.store.SetJobSuccess(job.ID, 100)
	}
}

// EnqueueNow creates an immediate pending job (for manual/admin triggers).
func (s *Server) EnqueueNow(jobType string) error {
	_, err := s.store.CreateJob(Job{
		ID:       uuid.NewString(),
		Type:     jobType,
		Status:   StatusPending,
		CreateAt: time.Now().UTC(),
	})
	return err
}