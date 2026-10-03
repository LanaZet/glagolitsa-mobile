// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

/**
 * Сервер принял запрос (202), но сессию не выдал — типично при занятом username/email.
 * Anti-enumeration: нельзя показывать «имя занято».
 */
class RegistrationDeferredException(
    val userMessage: String,
) : Exception(userMessage)