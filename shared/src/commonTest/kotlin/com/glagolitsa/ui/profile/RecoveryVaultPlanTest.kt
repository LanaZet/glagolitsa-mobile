// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryVaultPlanTest {
    @Test
    fun buildRecoveryVaultPlan_marksReadyWhenBothShardsExist() {
        val plan = buildRecoveryVaultPlan(
            localShardReady = true,
            cloudShardReady = true,
            trustedDeviceCount = 2,
            passkeyFallbackReady = true,
        )

        assertEquals("Защита настроена", plan.statusLabel)
        assertTrue(plan.headline.contains("Обе половины"))
        assertTrue(plan.nextStep.contains("обе"))
        assertEquals("Сохранено на этом устройстве", plan.factors[0].value)
        assertEquals("Зашифрованная копия готова", plan.factors[1].value)
        assertEquals("2 доверенных устройства", plan.factors[2].value)
        assertEquals("Подключено", plan.factors[3].value)
        assertEquals("1 сутки", plan.factors[4].value)
    }

    @Test
    fun buildRecoveryVaultPlan_flagsMissingCloudShard() {
        val plan = buildRecoveryVaultPlan(
            localShardReady = true,
            cloudShardReady = false,
            trustedDeviceCount = 0,
        )

        assertEquals("Нужна вторая половина ключа", plan.statusLabel)
        assertTrue(plan.nextStep.contains("облаке") || plan.nextStep.contains("вторую"))
        assertEquals("Ожидаем сохранение в облаке", plan.factors[1].value)
        assertEquals(RecoveryVaultFactorState.Missing, plan.factors[1].state)
        assertEquals(RecoveryVaultFactorState.Planned, plan.factors[2].state)
    }

    @Test
    fun buildRecoveryVaultPlan_flagsMissingLocalShard() {
        val plan = buildRecoveryVaultPlan(
            localShardReady = false,
            cloudShardReady = true,
            trustedDeviceCount = 0,
        )

        assertEquals("Нужна вторая половина ключа", plan.statusLabel)
        assertTrue(plan.nextStep.contains("телефоне") || plan.nextStep.contains("половин"))
        assertEquals("Ещё не создано", plan.factors[0].value)
        assertEquals(RecoveryVaultFactorState.Missing, plan.factors[0].state)
        assertEquals("3 суток", plan.factors[4].value)
    }
}
