// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.glagolitsa.model.Chat
import com.glagolitsa.repository.MessengerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatSettingsSections(
    val showChatInfo: Boolean = true,
    val showSecurity: Boolean = false,
    val showTheme: Boolean = true,
    val showButtons: Boolean = true,
    val showTextScale: Boolean = true,
)

data class ChatSettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val currentAppearance: ChatAppearanceSettings = ChatAppearanceSettings(),
    val draftAppearance: ChatAppearanceSettings = ChatAppearanceSettings(),
    val currentTextScale: Float = ChatSettingsLogic.TEXT_SCALE_DEFAULT,
    val draftTextScale: Float = ChatSettingsLogic.TEXT_SCALE_DEFAULT,
    val showDiscardDialog: Boolean = false,
    val sections: ChatSettingsSections = ChatSettingsSections(),
) {
    val hasUnsavedChanges: Boolean
        get() = draftAppearance != currentAppearance || draftTextScale != currentTextScale
}

sealed interface ChatSettingsEffect {
    data object NavigateBack : ChatSettingsEffect
    data object OpenSafetyNumber : ChatSettingsEffect
}

interface ChatSettingsController {
    val state: StateFlow<ChatSettingsUiState>
    val effects: Flow<ChatSettingsEffect>

    fun load()
    fun onDarknessChanged(value: Float)
    fun onButtonColorSelected(value: ChatButtonColor)
    fun onTextScaleChanged(value: Float)
    fun onSaveRequested()
    fun onResetAppearanceDraftRequested()
    fun onBackRequested()
    fun onDiscardDismissed()
    fun onDiscardConfirmed()
    fun onSafetyNumberRequested()
}

@Composable
fun rememberChatSettingsController(
    chat: Chat,
    repository: MessengerRepository,
    canOpenSafetyNumber: Boolean,
): ChatSettingsController {
    val scope = rememberCoroutineScope()
    return remember(chat.id, canOpenSafetyNumber, repository) {
        ChatSettingsControllerImpl(
            chatId = chat.id,
            scope = scope,
            repository = repository,
            initialSections = ChatSettingsSections(showSecurity = canOpenSafetyNumber),
        )
    }
}

private class ChatSettingsControllerImpl(
    private val chatId: String,
    private val scope: CoroutineScope,
    private val repository: MessengerRepository,
    initialSections: ChatSettingsSections,
) : ChatSettingsController {
    private val _state = MutableStateFlow(
        ChatSettingsUiState(
            isLoading = true,
            sections = initialSections,
        ),
    )
    private val _effects = MutableSharedFlow<ChatSettingsEffect>(extraBufferCapacity = 8)

    override val state: StateFlow<ChatSettingsUiState> = _state.asStateFlow()
    override val effects: Flow<ChatSettingsEffect> = _effects.asSharedFlow()

    override fun load() {
        scope.launch {
            val appearance = repository.loadChatAppearanceSettings(chatId)
            val textScale = repository.loadChatTextScaleSetting(chatId)
            _state.value = ChatSettingsLogic.markLoaded(_state.value, appearance, textScale)
        }
    }

    override fun onDarknessChanged(value: Float) {
        _state.value = ChatSettingsLogic.applyDarkness(_state.value, value)
    }

    override fun onButtonColorSelected(value: ChatButtonColor) {
        _state.value = ChatSettingsLogic.applyButtonColor(_state.value, value)
    }

    override fun onTextScaleChanged(value: Float) {
        _state.value = ChatSettingsLogic.applyTextScale(_state.value, value)
    }

    override fun onSaveRequested() {
        val started = ChatSettingsLogic.markSaveStarted(_state.value) ?: return
        _state.value = started
        scope.launch {
            runCatching {
                repository.saveChatAppearanceSettings(chatId, _state.value.draftAppearance)
                repository.saveChatTextScaleSetting(chatId, _state.value.draftTextScale)
            }.onSuccess {
                _state.value = ChatSettingsLogic.markSaveSucceeded(_state.value)
            }.onFailure { err ->
                _state.value = ChatSettingsLogic.markSaveFailed(
                    _state.value,
                    err.message ?: "Не удалось сохранить настройки чата",
                )
            }
        }
    }

    override fun onResetAppearanceDraftRequested() {
        _state.value = ChatSettingsLogic.resetDraft(_state.value)
    }

    override fun onBackRequested() {
        when (ChatSettingsLogic.decideBack(_state.value)) {
            ChatSettingsLogic.BackDecision.ShowDiscardDialog -> {
                _state.value = _state.value.copy(showDiscardDialog = true)
            }
            ChatSettingsLogic.BackDecision.NavigateBack -> {
                _effects.tryEmit(ChatSettingsEffect.NavigateBack)
            }
        }
    }

    override fun onDiscardDismissed() {
        _state.value = _state.value.copy(showDiscardDialog = false)
    }

    override fun onDiscardConfirmed() {
        _state.value = _state.value.copy(showDiscardDialog = false)
        _effects.tryEmit(ChatSettingsEffect.NavigateBack)
    }

    override fun onSafetyNumberRequested() {
        if (!_state.value.sections.showSecurity) return
        _effects.tryEmit(ChatSettingsEffect.OpenSafetyNumber)
    }
}
