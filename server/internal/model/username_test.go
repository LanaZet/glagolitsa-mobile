// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "testing"

// Порт Mattermost TestValidUsername (public/model/user_test.go).

type usernameCase struct {
	value    string
	expected bool
}

var usernameCases = []usernameCase{
	{"spin-punch", true},
	{"sp", true},
	{"s", true},
	{"1spin-punch", true},
	{"-spin-punch", true},
	{".spin-punch", true},
	{"Spin-punch", false},
	{"spin punch-", false},
	{"spin_punch", true},
	{"spin", true},
	{"PUNCH", false},
	{"spin.punch", true},
	{"spin'punch", false},
	{"spin*punch", false},
	{"all", false},
	{"system", false},
}

func TestIsValidUsername_mattermostCases(t *testing.T) {
	for _, c := range usernameCases {
		if got := IsValidUsername(c.value); got != c.expected {
			t.Errorf("IsValidUsername(%q) = %v, want %v", c.value, got, c.expected)
		}
	}
}

func TestIsValidRegistrationUsername_acceptsOnlyEnglishLetters(t *testing.T) {
	cases := []usernameCase{
		{"alice", true},
		{"tatyana", true},
		{"alice2", false},
		{"alice-smith", false},
		{"alice_smith", false},
		{"alice.smith", false},
		{"Татьяна", false},
		{"admin", false},
	}
	for _, c := range cases {
		if got := IsValidRegistrationUsername(c.value); got != c.expected {
			t.Errorf("IsValidRegistrationUsername(%q) = %v, want %v", c.value, got, c.expected)
		}
	}
}

func TestNormalizeUsername_mattermostCases(t *testing.T) {
	cases := map[string]string{
		"Spin-punch": "spin-punch",
		"PUNCH":      "punch",
		"spin":       "spin",
	}
	for input, want := range cases {
		if got := NormalizeUsername(input); got != want {
			t.Errorf("NormalizeUsername(%q) = %q, want %q", input, got, want)
		}
	}
}
