// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package jobs

import "context"

// Worker executes a single job type (Mattermost channels/jobs/*_worker.go).
type Worker interface {
	Type() string
	Execute(ctx context.Context, job Job) error
}

// WorkerFunc adapts a function to Worker.
type WorkerFunc struct {
	JobType string
	Run     func(ctx context.Context) error
}

func (w WorkerFunc) Type() string { return w.JobType }

func (w WorkerFunc) Execute(ctx context.Context, _ Job) error {
	return w.Run(ctx)
}