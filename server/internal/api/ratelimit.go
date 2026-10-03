// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"

	"glagolitsa/server/internal/httpx"
)

type RateLimiter struct {
	mu               sync.Mutex
	perIP            map[string]*rate.Limiter
	perUser          map[string]*rate.Limiter
	perUserPoll      map[string]*rate.Limiter
	perUserRefresh   map[string]*rate.Limiter
	perSearchUser    map[string]*rate.Limiter
	perUploadUser    map[string]*rate.Limiter
	perPreviewUser   map[string]*rate.Limiter
	ipLimit          rate.Limit
	ipBurst          int
	userLimit        rate.Limit
	userBurst        int
	pollUserLimit    rate.Limit
	pollUserBurst    int
	refreshLimit     rate.Limit
	refreshBurst     int
	searchUserLimit  rate.Limit
	searchUserBurst  int
	uploadUserLimit  rate.Limit
	uploadUserBurst  int
	previewUserLimit rate.Limit
	previewUserBurst int
}

func NewRateLimiter() *RateLimiter {
	return &RateLimiter{
		perIP:            make(map[string]*rate.Limiter),
		perUser:          make(map[string]*rate.Limiter),
		perUserPoll:      make(map[string]*rate.Limiter),
		perUserRefresh:   make(map[string]*rate.Limiter),
		perSearchUser:    make(map[string]*rate.Limiter),
		perUploadUser:    make(map[string]*rate.Limiter),
		perPreviewUser:   make(map[string]*rate.Limiter),
		ipLimit:          rate.Every(time.Minute / 120),
		ipBurst:          30,
		userLimit:        rate.Every(time.Minute / 60),
		userBurst:        20,
		pollUserLimit:    rate.Every(time.Minute / 180),
		pollUserBurst:    60,
		refreshLimit:     rate.Every(time.Minute / 12),
		refreshBurst:     6,
		searchUserLimit:  rate.Every(time.Minute / 30),
		searchUserBurst:  10,
		uploadUserLimit:  rate.Every(time.Minute / 24),
		uploadUserBurst:  6,
		previewUserLimit: rate.Every(time.Minute / 40),
		previewUserBurst: 12,
	}
}

// NewRateLimiterFromEnv applies RATE_LIMIT_RELAXED / RATE_LIMIT_USER_PER_MIN for local dev.
func NewRateLimiterFromEnv() *RateLimiter {
	l := NewRateLimiter()
	if os.Getenv("RATE_LIMIT_DISABLED") == "true" || os.Getenv("RATE_LIMIT_RELAXED") == "true" {
		l.ipLimit = rate.Every(time.Minute / 600)
		l.ipBurst = 200
		l.userLimit = rate.Every(time.Minute / 600)
		l.userBurst = 200
		l.pollUserLimit = rate.Every(time.Minute / 600)
		l.pollUserBurst = 200
		l.refreshLimit = rate.Every(time.Minute / 60)
		l.refreshBurst = 30
		l.uploadUserLimit = rate.Every(time.Minute / 120)
		l.uploadUserBurst = 30
		l.previewUserLimit = rate.Every(time.Minute / 120)
		l.previewUserBurst = 30
	}
	if value := os.Getenv("RATE_LIMIT_USER_PER_MIN"); value != "" {
		if perMin, err := strconv.Atoi(value); err == nil && perMin > 0 {
			l.userLimit = rate.Every(time.Minute / time.Duration(perMin))
			l.userBurst = maxInt(20, perMin/3)
		}
	}
	if value := os.Getenv("RATE_LIMIT_POLL_PER_MIN"); value != "" {
		if perMin, err := strconv.Atoi(value); err == nil && perMin > 0 {
			l.pollUserLimit = rate.Every(time.Minute / time.Duration(perMin))
			l.pollUserBurst = maxInt(30, perMin/3)
		}
	}
	return l
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func (l *RateLimiter) AllowIP(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perIP[ip]
	if !ok {
		limiter = rate.NewLimiter(l.ipLimit, l.ipBurst)
		l.perIP[ip] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowUser(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perUser[userID]
	if !ok {
		limiter = rate.NewLimiter(l.userLimit, l.userBurst)
		l.perUser[userID] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowUserPoll(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perUserPoll[userID]
	if !ok {
		limiter = rate.NewLimiter(l.pollUserLimit, l.pollUserBurst)
		l.perUserPoll[userID] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowUserRefresh(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perUserRefresh[userID]
	if !ok {
		limiter = rate.NewLimiter(l.refreshLimit, l.refreshBurst)
		l.perUserRefresh[userID] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowSearchUser(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perSearchUser[userID]
	if !ok {
		limiter = rate.NewLimiter(l.searchUserLimit, l.searchUserBurst)
		l.perSearchUser[userID] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowUploadUser(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perUploadUser[userID]
	if !ok {
		limiter = rate.NewLimiter(l.uploadUserLimit, l.uploadUserBurst)
		l.perUploadUser[userID] = limiter
	}
	return limiter.Allow()
}

func (l *RateLimiter) AllowPreviewUser(userID string) bool {
	if userID == "" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.perPreviewUser[userID]
	if !ok {
		limiter = rate.NewLimiter(l.previewUserLimit, l.previewUserBurst)
		l.perPreviewUser[userID] = limiter
	}
	return limiter.Allow()
}

func clientIP(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		parts := strings.Split(forwarded, ",")
		return strings.TrimSpace(parts[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (h *Handler) withRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowUser(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}

func (h *Handler) withPollRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowUserPoll(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}

func (h *Handler) withRefreshRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowUserRefresh(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}

func (h *Handler) withSearchRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowSearchUser(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}

func (h *Handler) withUploadRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowUploadUser(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "upload rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}

func (h *Handler) withPreviewRateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.rateLimiter == nil {
			next(w, r)
			return
		}
		if !h.rateLimiter.AllowIP(clientIP(r)) {
			httpx.WriteError(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		if claims, ok := httpx.ClaimsFromContext(r); ok {
			if !h.rateLimiter.AllowPreviewUser(claims.UserID) {
				httpx.WriteError(w, http.StatusTooManyRequests, "preview rate limit exceeded")
				return
			}
		}
		next(w, r)
	}
}
