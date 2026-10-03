// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package jobs

import "time"

// Store — persistence for Mattermost-style job queue.
type Store interface {
	CreateJob(job Job) (Job, error)
	ClaimNextJob(jobType string, now time.Time) (Job, bool, error)
	SetJobSuccess(jobID string, progress int64) error
	SetJobError(jobID string, lastError string) error
	EnqueueScheduledJob(jobType string, notBefore time.Time) (bool, error)
}