// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSettingsLogicTest {
    private val base = ChatSettingsUiState(
        isLoading = false,
        currentAppearance = ChatAppearanceSettings(darkness = 0.4f, buttonColor = ChatButtonColor.Red),
        draftAppearance = ChatAppearanceSettings(darkness = 0.4f, buttonColor = ChatButtonColor.Red),
        currentTextScale = 1f,
        draftTextScale = 1f,
    )

    @Test
    fun coerceAndParseTextScale() {
        assertEquals(0.85f, ChatSettingsLogic.coerceTextScale(0.1f))
        assertEquals(1.2f, ChatSettingsLogic.coerceTextScale(2f))
        assertEquals(1.05f, ChatSettingsLogic.parseTextScale("1.05"))
        assertEquals(ChatSettingsLogic.TEXT_SCALE_DEFAULT, ChatSettingsLogic.parseTextScale("nope"))
    }

    @Test
    fun draftEdits_markUnsavedChanges() {
        val darker = ChatSettingsLogic.applyDarkness(base, 0.9f)
        assertTrue(darker.hasUnsavedChanges)
        assertEquals(0.9f, darker.draftAppearance.darkness)
        assertEquals(0.4f, darker.currentAppearance.darkness)

        val scaled = ChatSettingsLogic.applyTextScale(base, 1.15f)
        assertTrue(scaled.hasUnsavedChanges)
        assertEquals(1.15f, scaled.draftTextScale)

        val colored = ChatSettingsLogic.applyButtonColor(base, ChatButtonColor.Gold)
        assertTrue(colored.hasUnsavedChanges)
        assertEquals(ChatButtonColor.Gold, colored.draftAppearance.buttonColor)
    }

    @Test
    fun resetDraft_restoresCurrentValues() {
        val dirty = ChatSettingsLogic.applyTextScale(
            ChatSettingsLogic.applyDarkness(base, 1f),
            1.2f,
        )
        val reset = ChatSettingsLogic.resetDraft(dirty)
        assertFalse(reset.hasUnsavedChanges)
        assertEquals(base.currentAppearance, reset.draftAppearance)
        assertEquals(base.currentTextScale, reset.draftTextScale)
    }

    @Test
    fun decideBack_requiresDiscardWhenDirty() {
        assertEquals(
            ChatSettingsLogic.BackDecision.NavigateBack,
            ChatSettingsLogic.decideBack(base),
        )
        assertEquals(
            ChatSettingsLogic.BackDecision.ShowDiscardDialog,
            ChatSettingsLogic.decideBack(ChatSettingsLogic.applyDarkness(base, 0.1f)),
        )
    }

    @Test
    fun saveLifecycle_startsOnlyWhenDirtyAndNotBusy() {
        assertNull(ChatSettingsLogic.markSaveStarted(base))
        assertNull(ChatSettingsLogic.markSaveStarted(base.copy(isLoading = true)))

        val dirty = ChatSettingsLogic.applyDarkness(base, 0.2f)
        val started = ChatSettingsLogic.markSaveStarted(dirty)
        assertTrue(started!!.isSaving)

        val saved = ChatSettingsLogic.markSaveSucceeded(started.copy(draftAppearance = dirty.draftAppearance))
        assertFalse(saved.isSaving)
        assertFalse(saved.hasUnsavedChanges)
        assertEquals(dirty.draftAppearance, saved.currentAppearance)

        val failed = ChatSettingsLogic.markSaveFailed(started, "boom")
        assertFalse(failed.isSaving)
        assertEquals("boom", failed.errorMessage)
    }

    @Test
    fun markLoaded_setsCurrentAndDraft() {
        val appearance = ChatAppearanceSettings(darkness = 0.55f, buttonColor = ChatButtonColor.Blue)
        val loaded = ChatSettingsLogic.markLoaded(ChatSettingsUiState(), appearance, 1.1f)
        assertFalse(loaded.isLoading)
        assertEquals(appearance, loaded.currentAppearance)
        assertEquals(appearance, loaded.draftAppearance)
        assertEquals(1.1f, loaded.currentTextScale)
        assertEquals(1.1f, loaded.draftTextScale)
        assertFalse(loaded.hasUnsavedChanges)
    }
}
