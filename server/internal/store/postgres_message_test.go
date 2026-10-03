// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"

	"glagolitsa/server/internal/model"
)

func TestMessageBodyForStorage_keepsEncryptedBodyNonNull(t *testing.T) {
	message := model.Message{
		Body:       "",
		Ciphertext: "Y2lwaGVydGV4dA==",
	}

	if got := messageBodyForStorage(message); got != "" {
		t.Fatalf("messageBodyForStorage()=%q want empty string", got)
	}
}

func TestMessageBodyForStorage_preservesPlaintextBody(t *testing.T) {
	message := model.Message{Body: "hello"}

	if got := messageBodyForStorage(message); got != "hello" {
		t.Fatalf("messageBodyForStorage()=%q want hello", got)
	}
}
