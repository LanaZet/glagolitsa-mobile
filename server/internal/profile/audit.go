// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import "glagolitsa/server/internal/model"

// AuditRecorder — опциональный аудит поисковых запросов (identity abuse protection).
type AuditRecorder interface {
	RecordAudit(event model.AuditEventInput) error
}