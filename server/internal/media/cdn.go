// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import "fmt"

// CDNAdapter exposes opaque encrypted blobs via CDN — never decrypts server-side.
type CDNAdapter struct {
	BaseURL string
}

func NewCDNAdapter(cfg Config) CDNAdapter {
	return CDNAdapter{BaseURL: cfg.CDNBaseURL}
}

func (a CDNAdapter) PublicURL(fileID string) string {
	if a.BaseURL == "" || fileID == "" {
		return ""
	}
	return fmt.Sprintf("%s/%s", a.BaseURL, fileID)
}