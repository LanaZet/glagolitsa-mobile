// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"strings"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// PreparedRegister — нормализованный ввод после проверок (Mattermost: PreSave / CreateUser).
type PreparedRegister struct {
	Username       string
	Email          string
	Password       string
	DeviceID       string
	PowChallengeID string
	PowSolution    string
}

func prepareRegister(req model.RegisterRequest, passwordSettings auth.PasswordSettings) (PreparedRegister, string, error) {
	username := model.NormalizeUsername(req.Username)
	password := req.Password
	email := model.NormalizeEmail(req.Email)

	if username == "" || password == "" {
		return PreparedRegister{}, "", errRequiredFields
	}
	if !model.IsValidRegistrationUsername(username) {
		return PreparedRegister{}, "username", errInvalidUsername
	}
	if store.IsDevReservedUsername(username) {
		return PreparedRegister{}, "username", errReservedDevUsername
	}
	if len(password) > auth.PasswordMaximumLength {
		return PreparedRegister{}, "password", errPasswordTooLong
	}
	if err := auth.ValidatePassword(password, passwordSettings); err != nil {
		return PreparedRegister{}, "password", err
	}
	if email != "" && !model.IsValidEmail(email) {
		return PreparedRegister{}, "email", errInvalidEmail
	}

	return PreparedRegister{
		Username:       username,
		Email:          email,
		Password:       password,
		DeviceID:       strings.TrimSpace(req.DeviceID),
		PowChallengeID: strings.TrimSpace(req.PowChallengeID),
		PowSolution:    strings.TrimSpace(req.PowSolution),
	}, "", nil
}
