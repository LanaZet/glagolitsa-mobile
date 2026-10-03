// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"crypto/sha256"
	"encoding/hex"
)

func IdentityKeyHash(publicKey []byte) []byte {
	sum := sha256.Sum256(publicKey)
	return sum[:]
}

func KeyChangeEventHash(prevHash, accountID, deviceID, eventType string, identityKeyHash []byte, signedPrekeyID int) []byte {
	h := sha256.New()
	if prevHash != "" {
		h.Write([]byte(prevHash))
	}
	h.Write([]byte(accountID))
	h.Write([]byte(deviceID))
	h.Write([]byte(eventType))
	h.Write(identityKeyHash)
	if signedPrekeyID > 0 {
		h.Write([]byte{byte(signedPrekeyID >> 24), byte(signedPrekeyID >> 16), byte(signedPrekeyID >> 8), byte(signedPrekeyID)})
	}
	sum := h.Sum(nil)
	return sum[:]
}

func HashHex(data []byte) string {
	return hex.EncodeToString(data)
}
