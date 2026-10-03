// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package dedup

import (
	"sync"
	"time"
)

// State mirrors Mattermost seenPendingPostIdsCache.
type State int

const (
	StateUnknown State = iota
	StateInFlight
	StateCommitted
)

type entry struct {
	messageID string
	state     State
	expiresAt time.Time
}

// Cache — in-memory pending_id dedup (Mattermost app/post.go deduplicateCreatePost).
type Cache struct {
	mu       sync.Mutex
	entries  map[string]entry
	ttl      time.Duration
	maxItems int
}

func NewCache(ttl time.Duration, maxItems int) *Cache {
	if ttl <= 0 {
		ttl = 30 * time.Second
	}
	if maxItems <= 0 {
		maxItems = 25_000
	}
	return &Cache{entries: make(map[string]entry), ttl: ttl, maxItems: maxItems}
}

func (c *Cache) Get(pendingID string) (messageID string, state State, ok bool) {
	if pendingID == "" {
		return "", StateUnknown, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.evictExpiredLocked(time.Now().UTC())
	item, found := c.entries[pendingID]
	if !found {
		return "", StateUnknown, false
	}
	return item.messageID, item.state, true
}

func (c *Cache) Reserve(pendingID string) bool {
	if pendingID == "" {
		return true
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now().UTC()
	c.evictExpiredLocked(now)
	if item, found := c.entries[pendingID]; found {
		return item.state == StateInFlight
	}
	c.ensureCapacityLocked()
	c.entries[pendingID] = entry{state: StateInFlight, expiresAt: now.Add(c.ttl)}
	return true
}

func (c *Cache) Commit(pendingID, messageID string) {
	if pendingID == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries[pendingID] = entry{
		messageID: messageID,
		state:     StateCommitted,
		expiresAt: time.Now().UTC().Add(c.ttl),
	}
}

func (c *Cache) Release(pendingID string) {
	if pendingID == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, pendingID)
}

func (c *Cache) evictExpiredLocked(now time.Time) {
	for key, item := range c.entries {
		if now.After(item.expiresAt) {
			delete(c.entries, key)
		}
	}
}

func (c *Cache) ensureCapacityLocked() {
	if len(c.entries) < c.maxItems {
		return
	}
	for key := range c.entries {
		delete(c.entries, key)
		if len(c.entries) < c.maxItems {
			return
		}
	}
}