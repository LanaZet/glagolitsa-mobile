// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package ws

import (
	"net/http"
	"net/url"
	"os"
	"strings"
)

// OriginPolicy задаёт, какие Origin допустимы для WebSocket upgrade.
type OriginPolicy struct {
	AllowedOrigins   map[string]struct{}
	AllowEmptyOrigin bool
	AllowAll         bool
}

func (p OriginPolicy) allows(r *http.Request) bool {
	if p.AllowAll {
		return true
	}
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		return p.AllowEmptyOrigin
	}
	if _, ok := p.AllowedOrigins[origin]; ok {
		return true
	}
	if referer := strings.TrimSpace(r.Header.Get("Referer")); referer != "" {
		if refURL, err := url.Parse(referer); err == nil {
			refOrigin := refURL.Scheme + "://" + refURL.Host
			if refOrigin != "://" {
				if _, ok := p.AllowedOrigins[refOrigin]; ok {
					return true
				}
			}
		}
	}
	return false
}

func OriginPolicyFromEnv(frontendOrigin string, env string) OriginPolicy {
	allowed := map[string]struct{}{}
	if frontendOrigin != "" {
		allowed[frontendOrigin] = struct{}{}
	}
	if extra := os.Getenv("WS_ALLOWED_ORIGINS"); extra != "" {
		for _, part := range strings.Split(extra, ",") {
			part = strings.TrimSpace(part)
			if part != "" {
				allowed[part] = struct{}{}
			}
		}
	}
	if env != "production" {
		return OriginPolicy{AllowAll: true}
	}
	return OriginPolicy{
		AllowedOrigins:   allowed,
		AllowEmptyOrigin: true,
	}
}