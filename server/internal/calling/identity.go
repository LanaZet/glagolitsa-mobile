// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
)

// OpaqueParticipantID derives a LiveKit participant identity that does not
// embed username/phone/email. Mapping back to account stays in Go API only.
// Secret material must never be logged.
func OpaqueParticipantID(secret, callID, userID, deviceID string) string {
	key := []byte(secret)
	if len(key) == 0 {
		// Fail closed shape: still opaque-ish, but production must set secret.
		key = []byte("glagolitsa-unconfigured-livekit")
	}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(callID))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(userID))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(deviceID))
	sum := mac.Sum(nil)
	return "cp_" + hex.EncodeToString(sum[:16])
}
