// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package cluster

import "glagolitsa/server/internal/model"

// Interface — Mattermost einterfaces/cluster.go (WS fan-out across nodes).
type Interface interface {
	Publish(memberIDs []string, event model.WSEvent)
	Close() error
}