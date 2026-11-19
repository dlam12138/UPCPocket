@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.upcpocket.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerFormatter
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Native Material 3 animates these focus colors, including the label cutout and motion scale. */
@Composable
private fun glassInputColors(colors: TextFieldColors): TextFieldColors {
    return colors.copy(
        focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.035f),
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
    )
}

/** A settled selection produces one brief glint. The initial value never starts an animation. */
@Composable
private fun <T> rememberPickerSelectionGlint(owner: Any, selection: () -> T): State<Float> {
    val glint = remember(owner) { Animatable(0f) }
    val currentSelection by rememberUpdatedState(selection)
    LaunchedEffect(owner) {
        var initial = true
        snapshotFlow { currentSelection() }.collectLatest {
            if (initial) {
                initial = false
                return@collectLatest
            }
            glint.snapTo(0f)
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@collectLatest
            // Time-dial drags can change many minute values. Wait for the selection to settle.
            delay(72)
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@collectLatest
            glint.snapTo(1f)
            glint.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }
    return glint.asState()
}

/** Only the rim is redrawn during the glint; no offscreen layer, scale or text movement. */
@Composable
private fun Modifier.pickerSelectionGlint(shape: Shape, progress: State<Float>): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val rim = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.78f),
            0.42f to accent.copy(alpha = 0.48f),
            1f to Color.Transparent,
            start = Offset(size.width * 0.1f, 0f),
            end = Offset(size.width, size.height),
        )
        val stroke = Stroke(1.5.dp.toPx())
        onDrawWithContent {
            drawContent()
            val alpha = progress.value
            if (alpha > 0f) drawOutline(outline, rim, alpha = alpha, style = stroke)
        }
    }
}

/**
 * 保留当前 String 输入框 API 与原生焦点、错误、禁用、选择和 IME 行为。
 * 常态背景透明，焦点仅增加极轻主题色；原生边框、错误、光标及文字颜色保持不变。
 */
@Composable
fun GlassOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = if (glass) glassInputColors(colors) else colors,
    )
}

/** 当前业务使用的日期选择器参数；未重写 title/headline，沿用原生标题及辅助语义。 */
@Composable
fun GlassDatePicker(
    state: DatePickerState,
    modifier: Modifier = Modifier,
    dateFormatter: DatePickerFormatter = remember { DatePickerDefaults.dateFormatter() },
    colors: DatePickerColors = DatePickerDefaults.colors(),
    showModeToggle: Boolean = true,
) {
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val decoration = if (glass) {
        val glint = rememberPickerSelectionGlint(state) { state.selectedDateMillis }
        modifier.pickerSelectionGlint(MaterialTheme.shapes.large, glint)
    } else modifier
    DatePicker(
        state = state,
        modifier = decoration,
        dateFormatter = dateFormatter,
        colors = if (glass) colors.copy(
            containerColor = Color.Transparent,
            dateTextFieldColors = glassInputColors(colors.dateTextFieldColors),
        ) else colors,
        showModeToggle = showModeToggle,
    )
}

/** 透出宿主弹窗材质，选择中/禁用文本及时间盘交互仍由原生控件维护。 */
@Composable
fun GlassTimePicker(
    state: TimePickerState,
    modifier: Modifier = Modifier,
    colors: TimePickerColors = TimePickerDefaults.colors(),
    layoutType: TimePickerLayoutType = TimePickerDefaults.layoutType(),
) {
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val decoration = if (glass) {
        val glint = rememberPickerSelectionGlint(state) { state.hour * 60 + state.minute }
        modifier.pickerSelectionGlint(MaterialTheme.shapes.large, glint)
    } else modifier
    TimePicker(
        state = state,
        modifier = decoration,
        colors = if (glass) colors.copy(
            containerColor = Color.Transparent,
            clockDialColor = Color.Transparent,
            periodSelectorUnselectedContainerColor = Color.Transparent,
            timeSelectorUnselectedContainerColor = Color.Transparent,
        ) else colors,
        layoutType = layoutType,
    )
}
