// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package transport

import (
	"testing"
)

func TestBuildRelayInputs_validation(t *testing.T) {
	ciphertext := []byte("fake-ciphertext")

	tests := []struct {
		name    string
		inputs  []EnvelopeInput
		wantErr string
	}{
		{
			name: "valid sealed sender envelope",
			inputs: []EnvelopeInput{{
				MailboxToken: "mailbox-1",
				EnvelopeType: 3,
				Ciphertext:   ciphertext,
			}},
		},
		{
			name: "valid standard envelope",
			inputs: []EnvelopeInput{{
				MailboxToken: "mailbox-1",
				EnvelopeType: 1,
				Ciphertext:   ciphertext,
			}},
		},
		{
			name: "missing mailbox token",
			inputs: []EnvelopeInput{{
				EnvelopeType: 3,
				Ciphertext:   ciphertext,
			}},
			wantErr: "envelopes.mailbox_token is required",
		},
		{
			name: "missing ciphertext",
			inputs: []EnvelopeInput{{
				MailboxToken: "mailbox-1",
				EnvelopeType: 3,
			}},
			wantErr: "envelopes.ciphertext is required",
		},
		{
			name: "invalid envelope type",
			inputs: []EnvelopeInput{{
				MailboxToken: "mailbox-1",
				EnvelopeType: 2,
				Ciphertext:   ciphertext,
			}},
			wantErr: "envelopes.envelope_type must be 1 or 3",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := BuildRelayInputs(tc.inputs, nil)
			if tc.wantErr != "" {
				if err == nil || err.Error() != tc.wantErr {
					t.Fatalf("err = %v, want %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected err: %v", err)
			}
			if len(got) != len(tc.inputs) {
				t.Fatalf("len(inputs) = %d, want %d", len(got), len(tc.inputs))
			}
		})
	}
}