// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glagolitsa.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.chat.VoiceDictationState
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_mic_button
import glagolitsamobile.shared.generated.resources.chat_send_button
import org.jetbrains.compose.resources.painterResource
import kotlin.math.min

/** Размеры полей ввода Design System 1.0: Small 40 · Default 48 · Large 56. */
enum class GlagolitsaInputSize {
    Small,
    Default,
    Large,
}

/** Специальные виды полей ввода Design System 1.0. */
enum class GlagolitsaInputVariant {
    Default,
    Search,
    Email,
    /** Логин / имя пользователя — иконка пользователя слева. */
    Username,
    Password,
    Phone,
    Date,
    File,
    Multiline,
}

private const val InputAnimationMs = 175

private data class InputMetrics(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val textStyle: TextStyle,
    val cornerRadius: Dp,
    val iconGap: Dp,
)

@Composable
private fun rememberInputMetrics(size: GlagolitsaInputSize): InputMetrics {
    val typography = MaterialTheme.typography
    return remember(size, typography) {
        when (size) {
            GlagolitsaInputSize.Small -> InputMetrics(
                minHeight = 40.dp,
                horizontalPadding = GlagolitsaSpacing.md,
                verticalPadding = GlagolitsaSpacing.sm,
                textStyle = typography.bodyMedium,
                cornerRadius = 10.dp,
                iconGap = GlagolitsaSpacing.sm,
            )

            GlagolitsaInputSize.Default -> InputMetrics(
                minHeight = 48.dp,
                horizontalPadding = GlagolitsaSpacing.lg,
                verticalPadding = GlagolitsaSpacing.md,
                textStyle = typography.bodyLarge,
                cornerRadius = 10.dp,
                iconGap = GlagolitsaSpacing.md,
            )

            GlagolitsaInputSize.Large -> InputMetrics(
                minHeight = 56.dp,
                horizontalPadding = GlagolitsaSpacing.lg,
                verticalPadding = GlagolitsaSpacing.lg,
                textStyle = typography.bodyLarge,
                cornerRadius = 10.dp,
                iconGap = GlagolitsaSpacing.md,
            )
        }
    }
}

/**
 * Поле ввода Design System 1.0 — впадина поверхности, состояния и спецвиды по референсу.
 */
@Composable
fun GlagolitsaInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    size: GlagolitsaInputSize = GlagolitsaInputSize.Default,
    variant: GlagolitsaInputVariant = GlagolitsaInputVariant.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    errorMessage: String? = null,
    successMessage: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    trailingAction: (@Composable (Color) -> Unit)? = null,
) {
    val metrics = rememberInputMetrics(size)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    val isMultiline = variant == GlagolitsaInputVariant.Multiline || !singleLine
    val shape: Shape = GlagolitsaShapes.input
    val cornerRadius = metrics.cornerRadius

    val hasError = !errorMessage.isNullOrBlank()
    val hasSuccess = !successMessage.isNullOrBlank() && !hasError

    val showFocusGlow = focused && enabled && !readOnly && !hasError && !hasSuccess
    val showHover = hovered && enabled && !readOnly && !showFocusGlow && !hasError && !hasSuccess

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> GlagolitsaColors.InputBorderDefault.copy(alpha = 0.35f)
            readOnly -> GlagolitsaColors.InputBorderDefault.copy(alpha = 0.55f)
            hasError -> GlagolitsaColors.StatusError
            hasSuccess -> GlagolitsaColors.InputSuccessBright
            showFocusGlow -> GlagolitsaColors.InputFocusBorder.copy(alpha = 0.82f)
            showHover -> GlagolitsaColors.InputBorderHover
            else -> GlagolitsaColors.InputBorderDefault
        },
        animationSpec = tween(InputAnimationMs),
        label = "inputBorder",
    )

    val borderWidth = when {
        hasError || hasSuccess -> 1.5.dp
        showFocusGlow -> 0.7.dp
        else -> 1.dp
    }

    val iconTint = when {
        !enabled -> GlagolitsaColors.TextDisabled
        hasError -> GlagolitsaColors.StatusError
        hasSuccess -> GlagolitsaColors.InputSuccessBright
        showFocusGlow -> GlagolitsaColors.InputFocusBorder
        else -> GlagolitsaColors.TextTertiary
    }

    val textColor = when {
        !enabled -> GlagolitsaColors.TextDisabled
        readOnly -> GlagolitsaColors.TextSecondary
        else -> GlagolitsaColors.TextPrimary
    }

    val placeholderColor = if (enabled) GlagolitsaColors.InputPlaceholder else GlagolitsaColors.TextDisabled
    val textStyle = metrics.textStyle.copy(color = textColor)
    val placeholderStyle = metrics.textStyle.copy(color = placeholderColor)

    val keyboardOptions = when (variant) {
        GlagolitsaInputVariant.Email -> KeyboardOptions(keyboardType = KeyboardType.Email)
        GlagolitsaInputVariant.Phone -> KeyboardOptions(keyboardType = KeyboardType.Phone)
        else -> KeyboardOptions.Default
    }

    val visualTransformation = if (variant == GlagolitsaInputVariant.Password && !passwordVisible) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }

    val resolvedLeading: (@Composable (Color) -> Unit)? = leadingIcon ?: when (variant) {
        GlagolitsaInputVariant.Search -> ({ tint -> GlagolitsaInputSearchIcon(tint = tint) })
        GlagolitsaInputVariant.Email -> ({ tint -> GlagolitsaInputEmailIcon(tint = tint) })
        GlagolitsaInputVariant.Username -> ({ tint -> GlagolitsaInputUsernameIcon(tint = tint) })
        GlagolitsaInputVariant.Password -> ({ tint -> GlagolitsaInputLockIcon(tint = tint) })
        GlagolitsaInputVariant.Phone -> ({ tint -> GlagolitsaInputPhoneIcon(tint = tint) })
        GlagolitsaInputVariant.Date -> ({ tint -> GlagolitsaInputCalendarIcon(tint = tint) })
        GlagolitsaInputVariant.File -> ({ tint -> GlagolitsaInputAttachIcon(tint = tint) })
        else -> null
    }

    val showClear = value.isNotEmpty() && enabled && !readOnly && trailingAction == null &&
        variant !in setOf(GlagolitsaInputVariant.Password, GlagolitsaInputVariant.Date, GlagolitsaInputVariant.File)

    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.xs),
    ) {
        InsetInputSurface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isMultiline) {
                        Modifier.defaultMinSize(minHeight = 96.dp)
                    } else {
                        Modifier.defaultMinSize(minHeight = metrics.minHeight)
                    },
                )
                .inputFocusGlow(
                    active = showFocusGlow,
                    shape = shape,
                    cornerRadius = cornerRadius,
                )
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = shape,
                ),
            shape = shape,
            cornerRadius = cornerRadius,
            focused = showFocusGlow,
            hovered = showHover,
            enabled = enabled,
            readOnly = readOnly,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = metrics.horizontalPadding,
                        vertical = metrics.verticalPadding,
                    ),
                verticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (resolvedLeading != null) {
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        resolvedLeading(iconTint)
                    }
                    Spacer(modifier = Modifier.width(metrics.iconGap))
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !readOnly,
                    readOnly = readOnly,
                    textStyle = textStyle,
                    singleLine = !isMultiline,
                    minLines = if (isMultiline) maxOf(minLines, 3) else minLines,
                    maxLines = maxLines,
                    interactionSource = interactionSource,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    cursorBrush = SolidColor(
                        if (hasError) GlagolitsaColors.StatusError else GlagolitsaColors.InputCursor,
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Text(text = placeholder, style = placeholderStyle)
                            }
                            innerTextField()
                        }
                    },
                )

                when {
                    trailingAction != null -> {
                        Spacer(modifier = Modifier.width(metrics.iconGap))
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            trailingAction(iconTint)
                        }
                    }

                    variant == GlagolitsaInputVariant.Password && enabled && !readOnly -> {
                        Spacer(modifier = Modifier.width(metrics.iconGap))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { passwordVisible = !passwordVisible },
                            contentAlignment = Alignment.Center,
                        ) {
                            GlagolitsaInputEyeIcon(tint = iconTint, visible = passwordVisible)
                        }
                    }

                    hasError -> {
                        Spacer(modifier = Modifier.width(metrics.iconGap))
                        GlagolitsaInputErrorIcon(tint = GlagolitsaColors.StatusError)
                    }

                    hasSuccess -> {
                        Spacer(modifier = Modifier.width(metrics.iconGap))
                        GlagolitsaInputSuccessIcon(tint = GlagolitsaColors.InputSuccessBright)
                    }

                    showClear -> {
                        Spacer(modifier = Modifier.width(metrics.iconGap))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onValueChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            GlagolitsaInputClearIcon(tint = iconTint)
                        }
                    }
                }
            }
        }

        if (hasError) {
            Text(
                text = errorMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.InputErrorText,
            )
        } else if (hasSuccess) {
            Text(
                text = successMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.InputSuccessBright,
            )
        }
    }
}

private val ChatInputShellShape = RoundedCornerShape(24.dp)
private val ChatInputFieldCompactShape = RoundedCornerShape(percent = 50)
private val ChatInputFieldExpandedShape = RoundedCornerShape(24.dp)
private val ChatInputFieldExpandedCornerRadius = 24.dp
private val ChatInputMaxTextHeight = 112.dp

/**
 * Поле ввода сообщений в чат — стеклянная капсула:
 * [скрепка · «Сообщение…» · эмодзи] · кнопка микрофона (или отправки).
 * Скрепка внутри поля, чтобы капсула занимала максимум ширины.
 *
 * Optional [replyHeader] is drawn **inside** the text field (above the caret),
 * so reply context feels part of the composer rather than a separate plate above it.
 *
 * @param canSend When true, forces the send button even without text (e.g. channel post with photos only).
 *   Defaults to text-not-blank when null.
 */
@Composable
fun GlagolitsaChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Сообщение...",
    enabled: Boolean = true,
    onAttach: () -> Unit = {},
    onAttachPhotoVideo: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onAttachCamera: (() -> Unit)? = null,
    onEmoji: () -> Unit = {},
    onVoice: () -> Unit = {},
    onVoiceLongPress: (() -> Unit)? = null,
    voiceDictationState: VoiceDictationState = VoiceDictationState(),
    voiceIdleContentDescription: String = "Диктовка",
    voiceActiveContentDescription: String = "Остановить диктовку",
    onSend: (() -> Unit)? = null,
    canSend: Boolean? = null,
    replyHeader: (@Composable () -> Unit)? = null,
) {
    val hasText = value.isNotBlank()
    val showSend = canSend ?: hasText
    val voiceActive = voiceDictationState.isActive
    // Send and dictation must never share a fall-through path: showSend → send only,
    // empty → mic only. Previously `onSend ?: onVoice` could start recording mid-send.
    val primaryAction: (() -> Unit)? = when {
        !enabled -> null
        voiceActive -> onVoice
        showSend -> onSend
        else -> onVoice
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val attachInteractionSource = remember { MutableInteractionSource() }
    val attachPressed by attachInteractionSource.collectIsPressedAsState()
    var attachMenuExpanded by remember { mutableStateOf(false) }
    val hasAttachmentMenu = onAttachPhotoVideo != null || onAttachFile != null || onAttachCamera != null
    val emojiInteractionSource = remember { MutableInteractionSource() }
    val emojiPressed by emojiInteractionSource.collectIsPressedAsState()
    val primaryInteractionSource = remember { MutableInteractionSource() }
    val primaryPressed by primaryInteractionSource.collectIsPressedAsState()
    val typography = MaterialTheme.typography
    var renderedTextLineCount by remember { mutableStateOf(1) }
    val hasReplyHeader = replyHeader != null
    val inputExpanded = hasReplyHeader || renderedTextLineCount > 1 || value.contains('\n')
    val fieldShape = if (inputExpanded) ChatInputFieldExpandedShape else ChatInputFieldCompactShape
    val fieldMaxHeight = if (hasReplyHeader) 188.dp else 136.dp
    val light = !GlagolitsaColors.IsDark
    val lightInk = Color(0xFF6F604B)
    val fieldBorderColor = when {
        !enabled -> GlagolitsaColors.InputBorderDefault.copy(alpha = if (light) 0.16f else 0.22f)
        voiceActive -> GlagolitsaColors.OrnamentGold.copy(alpha = if (light) 0.38f else 0.31f)
        isFocused -> GlagolitsaColors.InputBorderHover.copy(alpha = if (light) 0.44f else 0.52f)
        else -> GlagolitsaColors.InputBorderDefault.copy(alpha = if (light) 0.36f else 0.47f)
    }
    val shellFill = if (light) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color(0xFFFBF6ED),
                0.54f to Color(0xFFF1E7D6),
                1f to Color(0xFFE1D3BA),
            ),
        )
    } else {
        GlagolitsaColors.ChatChromeFill
    }
    val shellBorderColor = if (light) {
        Color(0xFFE4D7BF).copy(alpha = 0.78f)
    } else {
        GlagolitsaColors.OverlayHairlineSoft
    }
    val fieldFill = if (light) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color(0xFFE7DECF),
                0.20f to Color(0xFFF0E8DA),
                0.72f to Color(0xFFFBF7EF),
                1f to Color(0xFFF7F0E6),
            ),
        )
    } else {
        GlagolitsaColors.ChatFieldFill
    }
    val cursorColor = when {
        voiceActive -> GlagolitsaColors.OrnamentGold.copy(alpha = 0.78f)
        hasText -> GlagolitsaColors.TextSecondary.copy(alpha = 0.82f)
        else -> GlagolitsaColors.TextMuted.copy(alpha = 0.46f)
    }
    val textRowAlignment = if (inputExpanded && !hasReplyHeader) {
        Alignment.Bottom
    } else {
        Alignment.CenterVertically
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .animateContentSize(animationSpec = tween(InputAnimationMs))
            .clip(ChatInputShellShape)
            .background(shellFill)
            .border(1.dp, shellBorderColor, ChatInputShellShape)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (light) 0.30f else 0.045f),
                            Color.Transparent,
                            if (light) {
                                lightInk.copy(alpha = 0.075f)
                            } else {
                                Color.Black.copy(alpha = 0.22f)
                            },
                        ),
                    ),
                    cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .heightIn(max = fieldMaxHeight)
                    .animateContentSize(animationSpec = tween(InputAnimationMs))
                    .clip(fieldShape)
                    .background(fieldFill)
                    .drawBehind {
                        val fieldCornerRadius = if (inputExpanded) {
                            ChatInputFieldExpandedCornerRadius.toPx()
                        } else {
                            size.height / 2f
                        }
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to if (light) {
                                        lightInk.copy(alpha = 0.12f)
                                    } else {
                                        Color.Black.copy(alpha = 0.38f)
                                    },
                                    0.52f to if (light) {
                                        lightInk.copy(alpha = 0.032f)
                                    } else {
                                        Color.Black.copy(alpha = 0.1f)
                                    },
                                    1f to Color.White.copy(alpha = if (light) 0.34f else 0.03f),
                                ),
                                endY = size.height * 0.68f,
                            ),
                            cornerRadius = CornerRadius(fieldCornerRadius, fieldCornerRadius),
                        )
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    if (light) {
                                        lightInk.copy(alpha = 0.07f)
                                    } else {
                                        Color.Black.copy(alpha = 0.22f)
                                    },
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (light) 0.28f else 0.025f),
                                ),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                            cornerRadius = CornerRadius(fieldCornerRadius, fieldCornerRadius),
                        )
                    }
                    .border(
                        width = 1.dp,
                        color = fieldBorderColor,
                        shape = fieldShape,
                    )
                    .padding(
                        start = 6.dp,
                        end = 6.dp,
                        top = if (hasReplyHeader) 8.dp else if (inputExpanded) 10.dp else 6.dp,
                        bottom = if (inputExpanded) 8.dp else 6.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (hasReplyHeader) 6.dp else 0.dp),
            ) {
                if (replyHeader != null) {
                    replyHeader()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = textRowAlignment,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Attach lives inside the field capsule so the text area can use full width.
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (attachPressed) Color.White.copy(alpha = 0.075f) else Color.Transparent,
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = attachInteractionSource,
                                indication = null,
                                onClick = {
                                    if (hasAttachmentMenu) {
                                        attachMenuExpanded = true
                                    } else {
                                        onAttach()
                                    }
                                },
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = "Прикрепить вложение"
                            }
                            .testTag("chat-attach"),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlagolitsaInputAttachIcon(
                            tint = GlagolitsaColors.TextPrimary.copy(
                                alpha = if (enabled) 0.88f else 0.42f,
                            ),
                            modifier = Modifier.size(22.dp),
                        )
                        if (hasAttachmentMenu) {
                            ChatAttachmentMenu(
                                expanded = attachMenuExpanded,
                                onDismiss = { attachMenuExpanded = false },
                                onPhotoVideo = onAttachPhotoVideo?.let { action ->
                                    {
                                        attachMenuExpanded = false
                                        action()
                                    }
                                },
                                onFile = onAttachFile?.let { action ->
                                    {
                                        attachMenuExpanded = false
                                        action()
                                    }
                                },
                                onCamera = onAttachCamera?.let { action ->
                                    {
                                        attachMenuExpanded = false
                                        action()
                                    }
                                },
                            )
                        }
                    }

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 32.dp, max = ChatInputMaxTextHeight)
                            .testTag("chat-message-input"),
                        textStyle = typography.bodyLarge.copy(
                            color = GlagolitsaColors.TextPrimary,
                        ),
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5,
                        onTextLayout = { textLayoutResult ->
                            val lineCount = textLayoutResult.lineCount.coerceAtLeast(1)
                            if (renderedTextLineCount != lineCount) {
                                renderedTextLineCount = lineCount
                            }
                        },
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(cursorColor),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (inputExpanded && !hasReplyHeader) {
                                    Alignment.TopStart
                                } else {
                                    Alignment.CenterStart
                                },
                            ) {
                                if (value.isEmpty()) {
                                    val voicePlaceholder = when {
                                        voiceDictationState.partialText.isNotBlank() -> voiceDictationState.partialText
                                        voiceDictationState.isListening -> "Говорите..."
                                        voiceDictationState.isProcessing -> "Распознаю..."
                                        else -> placeholder
                                    }
                                    val placeholderTint = if (voiceActive || voiceDictationState.partialText.isNotBlank()) {
                                        GlagolitsaColors.OrnamentGold.copy(alpha = 0.82f)
                                    } else {
                                        GlagolitsaColors.TextMuted.copy(alpha = 0.72f)
                                    }
                                    Text(
                                        text = voicePlaceholder,
                                        style = typography.bodyLarge,
                                        color = placeholderTint,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (emojiPressed) GlagolitsaColors.SurfacePressed else Color.Transparent)
                            .clickable(
                                enabled = enabled,
                                interactionSource = emojiInteractionSource,
                                indication = null,
                                onClick = onEmoji,
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = "Эмодзи"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        GlagolitsaInputEmojiIcon(
                            tint = GlagolitsaColors.TextPrimary.copy(alpha = if (enabled) 0.82f else 0.42f),
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    if (showSend) {
                                        GlagolitsaColors.AccentRedBright.copy(alpha = if (enabled) 0.34f else 0.12f)
                                    } else if (voiceActive) {
                                        GlagolitsaColors.OrnamentGold.copy(alpha = if (enabled) 0.34f else 0.12f)
                                    } else {
                                        GlagolitsaColors.OrnamentGold.copy(alpha = if (enabled) 0.2f else 0.08f)
                                    },
                                    if (showSend) {
                                        GlagolitsaColors.AccentRed.copy(alpha = if (enabled) 0.12f else 0.04f)
                                    } else if (voiceActive) {
                                        GlagolitsaColors.OrnamentGold.copy(alpha = if (enabled) 0.16f else 0.04f)
                                    } else {
                                        GlagolitsaColors.ChatChromeTop.copy(alpha = if (enabled) 0.13f else 0.05f)
                                    },
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = size.minDimension * 0.72f,
                            ),
                        )
                    }
                    .padding(2.dp)
                    .clip(CircleShape)
                    .then(
                        if (showSend) {
                            Modifier.drawBehind {
                                drawCircle(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            GlagolitsaColors.AccentRedLight.copy(alpha = if (enabled) 0.96f else 0.42f),
                                            GlagolitsaColors.AccentRed.copy(alpha = if (enabled) 0.96f else 0.38f),
                                            GlagolitsaColors.AccentRedContainerPressed.copy(alpha = if (enabled) 0.98f else 0.36f),
                                        ),
                                    ),
                                )
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = if (primaryPressed) 0.08f else 0.2f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.38f, size.height * 0.22f),
                                        radius = size.minDimension * 0.58f,
                                    ),
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = if (primaryPressed) 0.28f else 0.18f),
                                    radius = size.minDimension * 0.5f,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                        } else if (voiceActive) {
                            Modifier
                                .border(
                                    width = 1.dp,
                                    color = GlagolitsaColors.OrnamentGold.copy(alpha = 0.92f),
                                    shape = CircleShape,
                                )
                                .drawBehind {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                GlagolitsaColors.OrnamentGold.copy(alpha = 0.22f),
                                                Color.Transparent,
                                            ),
                                            center = Offset(size.width * 0.38f, size.height * 0.24f),
                                            radius = size.minDimension * 0.62f,
                                        ),
                                    )
                                }
                        } else {
                            Modifier
                                .border(
                                    width = 1.dp,
                                    color = GlagolitsaColors.OrnamentGold.copy(
                                        alpha = when {
                                            !enabled -> 0.24f
                                            primaryPressed -> 0.9f
                                            else -> 0.66f
                                        },
                                    ),
                                    shape = CircleShape,
                                )
                                .drawBehind {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                GlagolitsaColors.OrnamentGold.copy(
                                                    alpha = if (primaryPressed) 0.14f else 0.08f,
                                                ),
                                                Color.Transparent,
                                            ),
                                            center = Offset(size.width * 0.38f, size.height * 0.24f),
                                            radius = size.minDimension * 0.58f,
                                        ),
                                    )
                                }
                        },
                    )
                    .combinedClickable(
                        enabled = enabled && primaryAction != null,
                        interactionSource = primaryInteractionSource,
                        indication = null,
                        onClick = { primaryAction?.invoke() },
                        onLongClick = {
                            if (!showSend && onVoiceLongPress != null) {
                                onVoiceLongPress.invoke()
                            }
                        },
                    )
                    .then(
                        if (showSend) Modifier.testTag("chat-send") else Modifier,
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = when {
                            voiceActive -> voiceActiveContentDescription
                            showSend -> "Отправить сообщение"
                            else -> voiceIdleContentDescription
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (showSend) {
                    Image(
                        painter = painterResource(Res.drawable.chat_send_button),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(42.dp)
                            .alpha(
                                when {
                                    !enabled -> 0.52f
                                    primaryPressed -> 0.9f
                                    else -> 1f
                                },
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = if (primaryPressed) 0.04f else 0.12f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.34f, size.height * 0.2f),
                                        radius = size.minDimension * 0.52f,
                                    ),
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.18f),
                                    radius = size.minDimension * 0.49f,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            },
                    )
                } else if (voiceActive) {
                    Canvas(modifier = Modifier.size(22.dp)) {
                        drawRoundRect(
                            color = GlagolitsaColors.OrnamentGold.copy(alpha = if (enabled) 0.92f else 0.5f),
                            topLeft = Offset(size.width * 0.24f, size.height * 0.24f),
                            size = Size(size.width * 0.52f, size.height * 0.52f),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                    }
                } else {
                    Image(
                        painter = painterResource(Res.drawable.chat_mic_button),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(32.dp)
                            .alpha(
                                when {
                                    !enabled -> 0.5f
                                    primaryPressed -> 0.9f
                                    else -> 1f
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPhotoVideo: (() -> Unit)?,
    onFile: (() -> Unit)?,
    onCamera: (() -> Unit)?,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .width(224.dp)
            .background(GlagolitsaColors.Surface800),
    ) {
        AttachmentMenuItem(
            title = "Фото или видео",
            enabled = onPhotoVideo != null,
            onClick = onPhotoVideo ?: {},
            icon = { tint -> GlagolitsaInputMediaIcon(tint = tint, modifier = Modifier.size(22.dp)) },
        )
        AttachmentMenuItem(
            title = "Файл",
            enabled = onFile != null,
            onClick = onFile ?: {},
            icon = { tint -> GlagolitsaInputFileIcon(tint = tint, modifier = Modifier.size(22.dp)) },
        )
        AttachmentMenuItem(
            title = "Камера",
            enabled = onCamera != null,
            onClick = onCamera ?: {},
            icon = { tint -> GlagolitsaInputCameraIcon(tint = tint, modifier = Modifier.size(22.dp)) },
        )
    }
}

@Composable
private fun AttachmentMenuItem(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (enabled) {
        GlagolitsaColors.TextPrimary.copy(alpha = 0.9f)
    } else {
        GlagolitsaColors.TextDisabled
    }
    DropdownMenuItem(
        text = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
        },
        leadingIcon = { icon(tint) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = 54.dp),
    )
}

/** Впадина поверхности с внутренней тенью — реф INPUTS Design System 1.0. */
@Composable
private fun InsetInputSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    cornerRadius: Dp,
    focused: Boolean,
    hovered: Boolean,
    enabled: Boolean,
    readOnly: Boolean,
    content: @Composable () -> Unit,
) {
    val shellColor = GlagolitsaColors.InputShell
    val topColor = when {
        !enabled -> GlagolitsaColors.InputCavityMid.copy(alpha = 0.7f)
        readOnly -> GlagolitsaColors.InputCavityMid
        focused -> GlagolitsaColors.InputCavityTop
        hovered -> GlagolitsaColors.Surface600.copy(alpha = 0.55f)
        else -> GlagolitsaColors.InputCavityTop
    }
    val midColor = when {
        !enabled -> GlagolitsaColors.InputCavityBottom.copy(alpha = 0.8f)
        readOnly -> GlagolitsaColors.InputCavityMid
        hovered -> GlagolitsaColors.InputCavityMid
        else -> GlagolitsaColors.InputCavityMid
    }
    val bottomColor = GlagolitsaColors.InputCavityBottom

    val cavityFill = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to topColor,
            0.42f to midColor,
            1f to bottomColor,
        ),
    )

    val shadowBoost = when {
        focused -> 1.18f
        hovered -> 1.1f
        readOnly -> 0.72f
        else -> 1f
    }
    val light = !GlagolitsaColors.IsDark
    val ink = if (light) Color(0xFF3A2E22) else Color.Black
    val lift = Color.White

    Box(
        modifier = modifier
            .clip(shape)
            .background(shellColor)
            .padding(1.dp)
            .clip(shape)
            .background(cavityFill)
            .drawBehind {
                val corner = cornerRadius.toPx().coerceAtMost(min(size.width, size.height) / 2f)
                val topA = if (light) 0.09f else 0.34f
                val topMidA = if (light) 0.035f else 0.14f
                val midA = if (light) 0.06f else 0.22f
                val midSoftA = if (light) 0.025f else 0.08f
                val diagA = if (light) 0.07f else 0.26f
                val diagSoftA = if (light) 0.025f else 0.09f
                val bottomLiftA = if (light) 0.26f else 0.025f
                val bottomLiftEdgeA = if (light) 0.44f else 0.04f
                val rimLiftA = if (light) 0.32f else 0.025f
                val rimLiftMidA = if (light) 0.10f else 0.006f
                val rimInkA = if (light) 0.045f else 0.2f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to ink.copy(alpha = topA * shadowBoost),
                            0.38f to ink.copy(alpha = topMidA * shadowBoost),
                            1f to Color.Transparent,
                        ),
                        endY = size.height * 0.5f,
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to ink.copy(alpha = midA * shadowBoost),
                            0.22f to ink.copy(alpha = midSoftA * shadowBoost),
                            0.5f to Color.Transparent,
                        ),
                        endY = size.height * 0.56f,
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to ink.copy(alpha = diagA * shadowBoost),
                            0.55f to ink.copy(alpha = diagSoftA * shadowBoost),
                            1f to Color.Transparent,
                        ),
                        start = Offset(2.dp.toPx(), 2.dp.toPx()),
                        end = Offset(size.width * 0.62f, size.height * 0.66f),
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.8f to lift.copy(alpha = bottomLiftA),
                            1f to lift.copy(alpha = bottomLiftEdgeA),
                        ),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to lift.copy(alpha = rimLiftA),
                            0.48f to lift.copy(alpha = rimLiftMidA),
                            1f to ink.copy(alpha = rimInkA),
                        ),
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 0.5.dp.toPx()),
                )
                if (focused) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to GlagolitsaColors.InputFocusBorder.copy(alpha = 0.08f),
                                0.38f to GlagolitsaColors.InputFocusBorder.copy(alpha = 0.03f),
                                1f to Color.Transparent,
                            ),
                            endY = size.height * 0.55f,
                        ),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
            },
    ) {
        content()
    }
}

/** Мягкое ледяное свечение при фокусе — отделено от красного состояния ошибки. */
private fun Modifier.inputFocusGlow(
    active: Boolean,
    shape: Shape,
    cornerRadius: Dp,
): Modifier {
    if (!active) return this
    return drawBehind {
        val expansion = 2.dp.toPx()
        val corner = cornerRadius.toPx().coerceAtMost(min(size.width, size.height) / 2f) + expansion
        val center = Offset(size.width / 2f, size.height * 0.5f)
        val glowSize = Size(size.width + expansion * 2, size.height + expansion * 2)
        val glowOrigin = Offset(-expansion, -expansion)

        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    GlagolitsaColors.InputFocusBorder.copy(alpha = 0.14f),
                    GlagolitsaColors.InputFocusBorder.copy(alpha = 0.06f),
                    GlagolitsaColors.InputFocusBorder.copy(alpha = 0.02f),
                    Color.Transparent,
                ),
                center = center,
                radius = size.maxDimension * 0.72f,
            ),
            topLeft = glowOrigin,
            size = glowSize,
            cornerRadius = CornerRadius(corner, corner),
        )
    }
}
