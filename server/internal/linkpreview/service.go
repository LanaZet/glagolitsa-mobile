// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package linkpreview

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const maxHTMLBytes = 256 * 1024

type Preview struct {
	URL         string `json:"url"`
	Title       string `json:"title,omitempty"`
	Description string `json:"description,omitempty"`
	ImageURL    string `json:"image_url,omitempty"`
	SiteName    string `json:"site_name,omitempty"`
}

type Service struct {
	Client *http.Client
}

func NewService() *Service {
	transport := &http.Transport{
		Proxy:               nil,
		MaxIdleConns:        32,
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     30 * time.Second,
	}
	client := &http.Client{
		Timeout:   6 * time.Second,
		Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return ErrRedirectBlocked
		},
	}
	return &Service{Client: client}
}

func (s *Service) Fetch(ctx context.Context, rawURL string) (Preview, error) {
	normalized, err := validatePublicURL(rawURL)
	if err != nil {
		return Preview{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, normalized, nil)
	if err != nil {
		return Preview{}, err
	}
	req.Header.Set("User-Agent", "GlagolitsaPreviewBot/1.0")
	req.Header.Set("Accept", "text/html,application/xhtml+xml")

	resp, err := s.Client.Do(req)
	if err != nil {
		if errors.Is(err, ErrRedirectBlocked) {
			return Preview{}, ErrRedirectBlocked
		}
		return Preview{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return Preview{}, fmt.Errorf("preview fetch failed with status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxHTMLBytes))
	if err != nil {
		return Preview{}, err
	}
	meta := parseOpenGraph(string(body))
	if meta.URL == "" {
		meta.URL = normalized
	}
	if meta.ImageURL != "" {
		if _, err := validatePublicURL(meta.ImageURL); err != nil {
			meta.ImageURL = ""
		}
	}
	return meta, nil
}

var (
	ErrInvalidURL      = errors.New("invalid preview url")
	ErrPrivateAddress  = errors.New("private or local addresses are not allowed")
	ErrRedirectBlocked = errors.New("redirects are not allowed for previews")
)

func validatePublicURL(rawURL string) (string, error) {
	rawURL = strings.TrimSpace(rawURL)
	u, err := url.Parse(rawURL)
	if err != nil || u == nil {
		return "", ErrInvalidURL
	}
	if u.Scheme != "https" && u.Scheme != "http" {
		return "", ErrInvalidURL
	}
	if u.User != nil {
		return "", ErrInvalidURL
	}
	host := u.Hostname()
	if host == "" {
		return "", ErrInvalidURL
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if !isPublicAddr(ip) {
			return "", ErrPrivateAddress
		}
		return u.String(), nil
	}
	ips, err := net.LookupIP(host)
	if err != nil || len(ips) == 0 {
		return "", ErrInvalidURL
	}
	for _, ip := range ips {
		addr, ok := netip.AddrFromSlice(ip)
		if !ok || !isPublicAddr(addr) {
			return "", ErrPrivateAddress
		}
	}
	return u.String(), nil
}

func isPublicAddr(addr netip.Addr) bool {
	return addr.IsGlobalUnicast() &&
		!addr.IsPrivate() &&
		!addr.IsLoopback() &&
		!addr.IsLinkLocalUnicast() &&
		!addr.IsLinkLocalMulticast() &&
		!addr.IsMulticast() &&
		!addr.IsUnspecified()
}

var (
	metaTagRe     = regexp.MustCompile(`(?is)<meta[^>]+(?:property|name)\s*=\s*["'](?:og:|twitter:)?([^"']+)["'][^>]*>`)
	contentAttrRe = regexp.MustCompile(`(?is)content\s*=\s*["']([^"']*)["']`)
	titleRe       = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
)

func parseOpenGraph(html string) Preview {
	values := map[string]string{}
	for _, match := range metaTagRe.FindAllStringSubmatch(html, -1) {
		tag := match[0]
		key := strings.ToLower(strings.TrimSpace(match[1]))
		content := contentAttrRe.FindStringSubmatch(tag)
		if len(content) < 2 {
			continue
		}
		values[key] = strings.TrimSpace(htmlUnescape(content[1]))
	}
	title := strings.TrimSpace(values["title"])
	if title == "" {
		if t := titleRe.FindStringSubmatch(html); len(t) >= 2 {
			title = strings.TrimSpace(htmlUnescape(t[1]))
		}
	}
	return Preview{
		URL:         strings.TrimSpace(values["url"]),
		Title:       title,
		Description: strings.TrimSpace(values["description"]),
		ImageURL:    strings.TrimSpace(values["image"]),
		SiteName:    strings.TrimSpace(values["site_name"]),
	}
}

var replacer = strings.NewReplacer(
	"&amp;", "&",
	"&quot;", "\"",
	"&#39;", "'",
	"&lt;", "<",
	"&gt;", ">",
)

func htmlUnescape(value string) string {
	return replacer.Replace(value)
}
