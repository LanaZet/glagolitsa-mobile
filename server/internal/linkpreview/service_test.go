// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package linkpreview

import "testing"

func TestValidatePublicURLRejectsPrivate(t *testing.T) {
	_, err := validatePublicURL("http://127.0.0.1/test")
	if err == nil {
		t.Fatal("expected private address rejection")
	}
}

func TestValidatePublicURLRejectsFileScheme(t *testing.T) {
	_, err := validatePublicURL("file:///etc/passwd")
	if err == nil {
		t.Fatal("expected invalid scheme rejection")
	}
}

func TestParseOpenGraph(t *testing.T) {
	html := `
<html><head>
<meta property="og:title" content="Example title">
<meta property="og:description" content="Desc">
<meta property="og:image" content="https://cdn.example.com/image.png">
<meta property="og:site_name" content="Example Site">
</head><body></body></html>`
	preview := parseOpenGraph(html)
	if preview.Title != "Example title" {
		t.Fatalf("title=%q", preview.Title)
	}
	if preview.Description != "Desc" {
		t.Fatalf("description=%q", preview.Description)
	}
	if preview.ImageURL != "https://cdn.example.com/image.png" {
		t.Fatalf("image=%q", preview.ImageURL)
	}
	if preview.SiteName != "Example Site" {
		t.Fatalf("site=%q", preview.SiteName)
	}
}
