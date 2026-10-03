// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"sync"
	"time"
)

type actionLimiter struct {
	mu   sync.Mutex
	last map[string]time.Time
}

func newActionLimiter() *actionLimiter {
	return &actionLimiter{last: make(map[string]time.Time)}
}

func (l *actionLimiter) allow(key string, minInterval time.Duration) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	if prev, ok := l.last[key]; ok && now.Sub(prev) < minInterval {
		return false
	}
	l.last[key] = now
	return true
}