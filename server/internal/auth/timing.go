// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

// Защита от user-enumeration по времени ответа login (Mattermost: всегда bcrypt).
const dummyPasswordHash = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi8e8u4H5Y1xZstte.r9IwQ5w4gYlNm"

// CheckPasswordOrDummy выполняет bcrypt даже при отсутствии пользователя.
func CheckPasswordOrDummy(storedHash, password string) bool {
	hash := storedHash
	if hash == "" {
		hash = dummyPasswordHash
	}
	return CheckPassword(hash, password)
}
