// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import "errors"

var (
	errRequiredFields           = errors.New("username and password are required")
	errInvalidUsername          = errors.New("invalid username")
	errReservedDevUsername      = errors.New("username reserved for development")
	errInvalidEmail             = errors.New("invalid email")
	errPasswordTooLong          = errors.New("password is too long")
	errInvalidPasskeySession    = errors.New("invalid passkey session")
	errInvalidPasskeyCredential = errors.New("invalid passkey credential")
)
