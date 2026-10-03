// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import "encoding/base64"

func encodeB64(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(data)
}

func decodeB64(raw string) ([]byte, error) {
	if raw == "" {
		return nil, nil
	}
	return base64.StdEncoding.DecodeString(raw)
}