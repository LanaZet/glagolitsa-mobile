// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"context"
	"time"

	"glagolitsa/server/internal/calling"
	"glagolitsa/server/internal/jobs"
	"glagolitsa/server/internal/media"
	"glagolitsa/server/internal/metrics"
	"glagolitsa/server/internal/notification"
	"glagolitsa/server/internal/sync"
)

func wireJobServer(
	ctx context.Context,
	dataStore MonolithStore,
	m metrics.Interface,
	notificationSvc *notification.Service,
	syncSvc *sync.Service,
	mediaSvc *media.Service,
	callEvents calling.EventPublisher,
	callPresence calling.PresenceHook,
) *jobs.Server {
	jobServer := jobs.NewServer(dataStore, m)

	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypePurgeRelayQueue,
		Run: func(ctx context.Context) error {
			_, err := dataStore.PurgeExpiredQueue()
			return err
		},
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypePurgeStaleDevices,
		Run: func(ctx context.Context) error {
			_, err := dataStore.PurgeStaleDevices()
			return err
		},
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypeNotificationRetry,
		Run:     notificationSvc.FlushRetries,
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypeNotificationLogRetention,
		Run:     notificationSvc.PurgeDeliveryLogs,
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypeSyncOfflineRetry,
		Run:     syncSvc.FlushOfflineQueue,
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypeMediaRetention,
		Run:     mediaSvc.RunRetentionOnce,
	})
	jobServer.RegisterWorker(jobs.WorkerFunc{
		JobType: jobs.TypeCallTimeoutSweep,
		Run: func(ctx context.Context) error {
			// Background sweep must clear presence + notify clients; otherwise
			// live-guard and in_call presence can lag until the next call API hit.
			// Also fires opaque missed_call push after ring timeout.
			var missed calling.MissedCallNotifier
			if notificationSvc != nil {
				missed = notificationSvc
			}
			_, err := calling.SweepStaleCallsWithNotify(ctx, dataStore, callEvents, callPresence, missed)
			return err
		},
	})

	jobServer.RegisterSchedule(jobs.TypePurgeRelayQueue, 5*time.Minute)
	jobServer.RegisterSchedule(jobs.TypePurgeStaleDevices, 15*time.Minute)
	jobServer.RegisterSchedule(jobs.TypeNotificationRetry, 15*time.Second)
	jobServer.RegisterSchedule(jobs.TypeNotificationLogRetention, 6*time.Hour)
	jobServer.RegisterSchedule(jobs.TypeSyncOfflineRetry, 15*time.Second)
	jobServer.RegisterSchedule(jobs.TypeMediaRetention, 15*time.Minute)
	// Frequent enough to free live-guard slots within ~RingingTimeout + interval.
	jobServer.RegisterSchedule(jobs.TypeCallTimeoutSweep, 15*time.Second)

	jobServer.Start(ctx)
	return jobServer
}
