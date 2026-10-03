// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"os"
	"testing"
	"time"
)

func TestNewRateLimiterFromEnv_relaxed(t *testing.T) {
	t.Setenv("RATE_LIMIT_RELAXED", "true")
	l := NewRateLimiterFromEnv()
	if !l.AllowUser("user-1") {
		t.Fatal("relaxed limiter should allow burst")
	}
	for i := 0; i < 200; i++ {
		if !l.AllowUserPoll("user-1") {
			t.Fatalf("relaxed poll limit exceeded at %d", i)
		}
	}
}

func TestRateLimiter_pollBucketSeparateFromUser(t *testing.T) {
	l := NewRateLimiter()
	userID := "poll-user"
	for i := 0; i < l.userBurst; i++ {
		if !l.AllowUser(userID) {
			t.Fatalf("user bucket exhausted early at %d", i)
		}
	}
	if l.AllowUser(userID) {
		t.Fatal("user bucket should be exhausted")
	}
	if !l.AllowUserPoll(userID) {
		t.Fatal("poll bucket should remain available when user bucket is full")
	}
}

func TestNewRateLimiterFromEnv_customPerMin(t *testing.T) {
	t.Setenv("RATE_LIMIT_USER_PER_MIN", "120")
	l := NewRateLimiterFromEnv()
	allowed := 0
	for i := 0; i < 50; i++ {
		if l.AllowUser("custom-user") {
			allowed++
		}
		time.Sleep(time.Millisecond)
	}
	if allowed < 40 {
		t.Fatalf("expected at least 40 allowed in burst, got %d", allowed)
	}
	_ = os.Getenv("RATE_LIMIT_USER_PER_MIN")
}