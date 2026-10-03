// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

/**
 * Pure helpers for chat settings draft/current state transitions.
 * Keeps Compose controller thin and unit-testable.
 */
object ChatSettingsLogic {
    const val TEXT_SCALE_MIN = 0.85f
    const val TEXT_SCALE_MAX = 1.2f
    const val TEXT_SCALE_DEFAULT = 1.0f

    fun coerceDarkness(value: Float): Float = value.coerceIn(0f, 1f)

    fun coerceTextScale(value: Float): Float =
        value.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)

    fun parseTextScale(raw: String?, default: Float = TEXT_SCALE_DEFAULT): Float =
        raw?.toFloatOrNull()?.let(::coerceTextScale) ?: default

    fun applyDarkness(state: ChatSettingsUiState, value: Float): ChatSettingsUiState =
        state.copy(
            draftAppearance = state.draftAppearance.copy(darkness = coerceDarkness(value)),
        )

    fun applyButtonColor(state: ChatSettingsUiState, value: ChatButtonColor): ChatSettingsUiState =
        state.copy(
            draftAppearance = state.draftAppearance.copy(buttonColor = value),
        )

    fun applyTextScale(state: ChatSettingsUiState, value: Float): ChatSettingsUiState =
        state.copy(draftTextScale = coerceTextScale(value))

    fun resetDraft(state: ChatSettingsUiState): ChatSettingsUiState =
        state.copy(
            draftAppearance = state.currentAppearance,
            draftTextScale = state.currentTextScale,
            errorMessage = null,
        )

    fun markLoaded(
        state: ChatSettingsUiState,
        appearance: ChatAppearanceSettings,
        textScale: Float,
    ): ChatSettingsUiState =
        state.copy(
            isLoading = false,
            currentAppearance = appearance,
            draftAppearance = appearance,
            currentTextScale = coerceTextScale(textScale),
            draftTextScale = coerceTextScale(textScale),
            errorMessage = null,
        )

    fun markSaveStarted(state: ChatSettingsUiState): ChatSettingsUiState? {
        if (state.isSaving || state.isLoading || !state.hasUnsavedChanges) return null
        return state.copy(isSaving = true, errorMessage = null)
    }

    fun markSaveSucceeded(state: ChatSettingsUiState): ChatSettingsUiState =
        state.copy(
            isSaving = false,
            currentAppearance = state.draftAppearance,
            currentTextScale = state.draftTextScale,
            showDiscardDialog = false,
            errorMessage = null,
        )

    fun markSaveFailed(state: ChatSettingsUiState, message: String): ChatSettingsUiState =
        state.copy(isSaving = false, errorMessage = message)

    sealed interface BackDecision {
        data object NavigateBack : BackDecision
        data object ShowDiscardDialog : BackDecision
    }

    fun decideBack(state: ChatSettingsUiState): BackDecision =
        if (state.hasUnsavedChanges) BackDecision.ShowDiscardDialog else BackDecision.NavigateBack
}
