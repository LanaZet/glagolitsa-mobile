// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

enum class RecoveryVaultFactorState {
    Ready,
    Missing,
    Planned,
}

data class RecoveryVaultFactor(
    val label: String,
    val value: String,
    val state: RecoveryVaultFactorState,
    val hint: String,
)

data class RecoveryVaultPlan(
    val statusLabel: String,
    val headline: String,
    val summary: String,
    val factors: List<RecoveryVaultFactor>,
    val nextStep: String,
)

/**
 * User-facing recovery status. Internal model still uses "shard" concepts;
 * all strings are plain Russian for ordinary users.
 */
fun buildRecoveryVaultPlan(
    localShardReady: Boolean,
    cloudShardReady: Boolean,
    trustedDeviceCount: Int,
    passkeyFallbackReady: Boolean = false,
): RecoveryVaultPlan {
    val bothPartsReady = localShardReady && cloudShardReady
    val statusLabel = when {
        bothPartsReady -> "Защита настроена"
        localShardReady || cloudShardReady -> "Нужна вторая половина ключа"
        else -> "Ещё не настроено"
    }
    val headline = when {
        bothPartsReady -> "Обе половины ключа на месте"
        localShardReady -> "Часть ключа на этом телефоне уже есть"
        cloudShardReady -> "Облачная половина ключа уже сохранена"
        else -> "Восстановление аккаунта пока не настроено"
    }
    val summary = "Чтобы вернуть доступ к аккаунту, нужны две половины секретного ключа: " +
        "одна остаётся на вашем телефоне, вторая — в облаке в зашифрованном виде. " +
        "Доверенные устройства, ключ телефона (passkey) и пауза перед восстановлением " +
        "добавляют защиту, но не заменяют эти две половины."

    val trustedDevices = if (trustedDeviceCount > 0) {
        RecoveryVaultFactorState.Ready
    } else {
        RecoveryVaultFactorState.Planned
    }
    val timeLockHours = if (bothPartsReady) 24 else 72

    return RecoveryVaultPlan(
        statusLabel = statusLabel,
        headline = headline,
        summary = summary,
        factors = listOf(
            RecoveryVaultFactor(
                label = "На телефоне",
                value = if (localShardReady) "Сохранено на этом устройстве" else "Ещё не создано",
                state = if (localShardReady) RecoveryVaultFactorState.Ready else RecoveryVaultFactorState.Missing,
                hint = "Эта половина ключа не уходит с телефона, пока вы сами её не перенесёте.",
            ),
            RecoveryVaultFactor(
                label = "В облаке",
                value = if (cloudShardReady) "Зашифрованная копия готова" else "Ожидаем сохранение в облаке",
                state = if (cloudShardReady) RecoveryVaultFactorState.Ready else RecoveryVaultFactorState.Missing,
                hint = "На сервере лежит только шифр: без половины с телефона её нельзя прочитать.",
            ),
            RecoveryVaultFactor(
                label = "Доверенные устройства",
                value = if (trustedDeviceCount > 0) {
                    when (trustedDeviceCount) {
                        1 -> "1 доверенное устройство"
                        in 2..4 -> "$trustedDeviceCount доверенных устройства"
                        else -> "$trustedDeviceCount доверенных устройств"
                    }
                } else {
                    "Пока нет"
                },
                state = trustedDevices,
                hint = "Позже другое ваше устройство сможет дополнительно подтвердить, что это вы.",
            ),
            RecoveryVaultFactor(
                label = "Ключ телефона",
                value = if (passkeyFallbackReady) "Подключено" else "Скоро",
                state = if (passkeyFallbackReady) RecoveryVaultFactorState.Ready else RecoveryVaultFactorState.Planned,
                hint = "Вход по отпечатку или Face ID без ослабления защиты ключа.",
            ),
            RecoveryVaultFactor(
                label = "Пауза перед восстановлением",
                value = if (timeLockHours >= 24 && timeLockHours % 24 == 0) {
                    val days = timeLockHours / 24
                    when (days) {
                        1 -> "1 сутки"
                        in 2..4 -> "$days суток"
                        else -> "$days суток"
                    }
                } else {
                    "$timeLockHours ч"
                },
                state = RecoveryVaultFactorState.Planned,
                hint = "Подозрительные попытки восстановления ждут, пока не соберутся обе половины ключа.",
            ),
        ),
        nextStep = when {
            bothPartsReady ->
                "Храните обе половины ключа порознь. Для восстановления нужны обе."
            localShardReady ->
                "Сохраните вторую половину ключа в облаке, чтобы защита заработала полностью."
            cloudShardReady ->
                "Сначала создайте половину ключа на этом телефоне, затем можно восстанавливать доступ."
            else ->
                "Сначала настройте обе половины ключа — без них восстановление недоступно."
        },
    )
}
