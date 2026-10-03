// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

type preparedLogin struct {
	Username string
	Password string
	DeviceID string
}

func prepareLogin(req model.LoginRequest) (preparedLogin, error) {
	username := model.NormalizeUsername(req.Username)
	password := req.Password

	if username == "" || password == "" {
		return preparedLogin{}, errRequiredFields
	}
	if !model.IsValidUsername(username) {
		return preparedLogin{}, errInvalidUsername
	}
	if len(password) > auth.PasswordMaximumLength {
		return preparedLogin{}, errPasswordTooLong
	}
	return preparedLogin{
		Username: username,
		Password: password,
		DeviceID: req.DeviceID,
	}, nil
}
