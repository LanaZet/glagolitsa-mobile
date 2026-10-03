// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import "glagolitsa/server/internal/model"

// FanoutPublisher — WebSocket fanout (только видимым получателям).
type FanoutPublisher interface {
	BroadcastToUsers(memberIDs []string, event model.WSEvent)
}