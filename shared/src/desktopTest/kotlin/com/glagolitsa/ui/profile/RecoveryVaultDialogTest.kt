// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RecoveryVaultDialogTest {
    @Test
    fun recoveryVaultDialogShowsSplitSecretConcept() = runComposeUiTest {
        setContent {
            RecoveryVaultDialog(
                plan = buildRecoveryVaultPlan(
                    localShardReady = true,
                    cloudShardReady = false,
                    trustedDeviceCount = 1,
                ),
                onDismiss = {},
            )
        }

        onNodeWithText("Восстановление доступа").assertIsDisplayed()
        onNodeWithText("Сохранено на этом устройстве").assertIsDisplayed()
        onNodeWithText("Ожидаем сохранение в облаке").assertIsDisplayed()
        onNodeWithText("1 доверенное устройство").assertIsDisplayed()
    }
}
