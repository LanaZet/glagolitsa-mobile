// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

enum class GlassTextFieldStyle {
    Outlined,
    Filled,
    /** @deprecated Используйте [GlagolitsaInput] — поля DS 1.0 с впадиной поверхности. */
    Pill,
}

enum class GlassTextFieldSize {
    Default,
    Compact,
}

/** Обёртка над [GlagolitsaInput] для обратной совместимости. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    style: GlassTextFieldStyle = GlassTextFieldStyle.Outlined,
    size: GlassTextFieldSize = GlassTextFieldSize.Default,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    errorMessage: String? = null,
    successMessage: String? = null,
) {
    val inputSize = when (size) {
        GlassTextFieldSize.Compact -> GlagolitsaInputSize.Small
        GlassTextFieldSize.Default -> GlagolitsaInputSize.Default
    }

    val variant = when {
        !singleLine || minLines > 1 || maxLines > 1 -> GlagolitsaInputVariant.Multiline
        leadingIcon != null -> GlagolitsaInputVariant.Search
        else -> GlagolitsaInputVariant.Default
    }

    GlagolitsaInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        modifier = modifier,
        size = inputSize,
        variant = variant,
        enabled = enabled,
        readOnly = readOnly,
        errorMessage = errorMessage,
        successMessage = successMessage,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        leadingIcon = leadingIcon,
    )
}