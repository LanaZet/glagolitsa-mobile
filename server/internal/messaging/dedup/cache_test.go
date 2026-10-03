// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package dedup

import (
	"testing"
	"time"
)

func TestCache_reserveCommitGet(t *testing.T) {
	c := NewCache(time.Minute, 100)
	if !c.Reserve("pending-1") {
		t.Fatal("first reserve should succeed")
	}
	if !c.Reserve("pending-1") {
		t.Fatal("in-flight reserve should still return true")
	}
	c.Commit("pending-1", "msg-1")
	id, state, ok := c.Get("pending-1")
	if !ok || state != StateCommitted || id != "msg-1" {
		t.Fatalf("get committed = (%q, %v, %v)", id, state, ok)
	}
}

func TestCache_releaseAllowsRereserve(t *testing.T) {
	c := NewCache(time.Minute, 100)
	c.Reserve("pending-2")
	c.Release("pending-2")
	if _, _, ok := c.Get("pending-2"); ok {
		t.Fatal("released pending should be gone")
	}
	if !c.Reserve("pending-2") {
		t.Fatal("reserve after release should succeed")
	}
}

func TestCache_emptyPendingIDIsNoop(t *testing.T) {
	c := NewCache(time.Minute, 100)
	if !c.Reserve("") {
		t.Fatal("empty reserve should no-op true")
	}
	c.Commit("", "x")
	if _, _, ok := c.Get(""); ok {
		t.Fatal("empty pending should never be stored")
	}
}

func TestCache_expiredEntriesEvicted(t *testing.T) {
	c := NewCache(time.Millisecond, 100)
	c.Reserve("pending-ttl")
	time.Sleep(5 * time.Millisecond)
	if _, _, ok := c.Get("pending-ttl"); ok {
		t.Fatal("expired entry should be evicted on get")
	}
}
