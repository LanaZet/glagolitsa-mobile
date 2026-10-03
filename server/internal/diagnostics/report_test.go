// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package diagnostics

import (
	"context"
	"errors"
	"testing"
)

type okPinger struct{}

func (okPinger) Ping(context.Context) error { return nil }

type errPinger struct{}

func (errPinger) Ping(context.Context) error { return errors.New("down") }

type fakePush struct {
	info PushInfo
}

func (f fakePush) PushDiagnostics() PushInfo { return f.info }

func TestBuild_NoSecretsAndPrivacyFlags(t *testing.T) {
	r := Build(context.Background(), BuildOptions{
		Store:       okPinger{},
		Push:        fakePush{info: PushInfo{Android: "fcm", IOS: "log", UnifiedPush: "simple_push", Privacy: "opaque_wake_only"}},
		RedisURLSet: true,
		ClusterOn:   true,
		MetricsOn:   true,
		NodeID:      "node-a",
	})
	if r.Status != "ok" {
		t.Fatalf("status=%q", r.Status)
	}
	if r.Security.PushPreviewInOSPNS {
		t.Fatal("must never claim OSPNS previews (security policy)")
	}
	if r.Security.PushPayloadPolicy != "opaque_wake_only" {
		t.Fatal(r.Security.PushPayloadPolicy)
	}
	if r.Subsystems["push"].Extra["android"] != "fcm" {
		t.Fatal(r.Subsystems["push"].Extra)
	}
	// Sanity: report JSON surface must not invent secret-looking fields in Extra.
	for k := range r.Subsystems["push"].Extra {
		if k == "credentials" || k == "token" || k == "private_key" {
			t.Fatalf("forbidden key %q", k)
		}
	}
}

func TestBuild_DegradedOnDBError(t *testing.T) {
	r := Build(context.Background(), BuildOptions{Store: errPinger{}})
	if r.Status != "degraded" {
		t.Fatalf("status=%q", r.Status)
	}
	if r.Subsystems["database"].Status != "error" {
		t.Fatal(r.Subsystems["database"])
	}
}
