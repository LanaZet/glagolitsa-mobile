// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"testing"

	"glagolitsa/server/internal/model"
)

func TestDecodeEncryptedGroupRequest_validSenderKeyEnvelope(t *testing.T) {
	envelopeType := 4
	got, err := decodeEncryptedGroupRequest(model.SendMessageRequest{
		Ciphertext:     "Zm9v",
		EnvelopeType:   &envelopeType,
		SenderDeviceID: "device-1",
	})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got == nil || got.envelopeType != 4 || got.senderDeviceID != "device-1" {
		t.Fatalf("got = %+v", got)
	}
}

func TestDecodeEncryptedGroupRequest_plaintextReturnsNil(t *testing.T) {
	got, err := decodeEncryptedGroupRequest(model.SendMessageRequest{Body: "hi"})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got != nil {
		t.Fatalf("expected nil for plaintext-only request")
	}
}

func TestDecodeEncryptedGroupRequest_validationErrors(t *testing.T) {
	envelopeType := 4
	tests := []struct {
		name string
		req  model.SendMessageRequest
	}{
		{
			name: "missing envelope type",
			req: model.SendMessageRequest{
				Ciphertext:     "Zm9v",
				SenderDeviceID: "dev",
			},
		},
		{
			name: "wrong envelope type",
			req: func() model.SendMessageRequest {
				t := 3
				return model.SendMessageRequest{
					Ciphertext:     "Zm9v",
					EnvelopeType:   &t,
					SenderDeviceID: "dev",
				}
			}(),
		},
		{
			name: "missing sender device",
			req: model.SendMessageRequest{
				Ciphertext:   "Zm9v",
				EnvelopeType: &envelopeType,
			},
		},
		{
			name: "invalid base64",
			req: model.SendMessageRequest{
				Ciphertext:     "%%%",
				EnvelopeType:   &envelopeType,
				SenderDeviceID: "dev",
			},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := decodeEncryptedGroupRequest(tc.req)
			if err == nil {
				t.Fatal("expected error")
			}
		})
	}
}