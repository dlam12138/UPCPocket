@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.upcpocket.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle

/**
 * 小控件复用 Material 3 的交互、状态与无障碍语义。
 * 光泽只随按压或选中状态变化，不单独采集或模糊背景。
 */
@Composable
private fun isGlassControl() = LocalUiStyle.current == UiStyle.LIQUID_GLASS

@Composable
private fun glassInteractions(source: MutableInteractionSource?): MutableInteractionSource? =
    if (source != null || !isGlassControl()) source else remember { MutableInteractionSource() }

@Composable
private fun glassControlContent(tint: Color, content: Color): Color {
    if (!content.isSpecified || !tint.isSpecified || tint.alpha == 0f) return content
    val scheme = MaterialTheme.colorScheme
    val backdrop = tint.compositeOver(scheme.surface)
    val backgroundLight = backdrop.luminance()
    val foregroundLight = content.compositeOver(backdrop).luminance()
    val contrast = (maxOf(backgroundLight, foregroundLight) + 0.05f) /
        (minOf(backgroundLight, foregroundLight) + 0.05f)
    return if (contrast >= 4.5f) content else scheme.onSurface
}

@Composable
private fun Modifier.glassControlDecoration(
    shape: Shape,
    interactions: MutableInteractionSource?,
    enabled: Boolean = true,
    outlined: Boolean = false,
): Modifier {
    if (!isGlassControl() || !enabled) return this
    val decorated = glassPressHighlight(shape, interactions, compress = true).glassHighlight(shape)
    return if (outlined) decorated.border(glassBorder(), shape) else decorated
}

/** 按压反馈仅围绕原生 22dp 指示器绘制。 */
@Composable
private fun Modifier.glassToggleDecoration(
    shape: Shape,
    interactions: MutableInteractionSource?,
    enabled: Boolean,
): Modifier {
    if (!isGlassControl() || !enabled) return this
    return glassPressHighlight(shape, interactions, indicatorSize = 22.dp)
}

@Composable
private fun Modifier.glassSwitchGlint(checked: Boolean, enabled: Boolean): Modifier {
    if (!isGlassControl() || !enabled) return this

    val position = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.69f, stiffness = 420f),
        label = "Glass switch reflection",
    )
    return drawWithCache {
        val trackWidth = 52.dp.toPx().coerceAtMost(size.width)
        val trackHeight = 32.dp.toPx().coerceAtMost(size.height)
        val glintWidth = 14.dp.toPx().coerceAtMost(trackWidth / 3f)
        val glintHeight = 3.dp.toPx()
        val inset = 8.dp.toPx().coerceAtMost((trackWidth - glintWidth) / 2f)
        val glintSize = Size(glintWidth, glintHeight)
        val glintTop = (size.height - trackHeight) / 2f + 2.dp.toPx()
        val mirrored = layoutDirection == LayoutDirection.Rtl
        val brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.48f to Color.White,
            1f to Color.Transparent,
            endX = glintWidth.coerceAtLeast(1f),
        )
        onDrawWithContent {
            drawContent()
            val amount = position.value.coerceIn(0f, 1f)
            val horizontal = if (mirrored) 1f - amount else amount
            val x = (size.width - trackWidth) / 2f +
                lerp(inset, trackWidth - inset - glintWidth, horizontal)
            translate(x, glintTop) {
                drawRoundRect(
                    brush,
                    size = glintSize,
                    cornerRadius = CornerRadius(glintHeight / 2f),
                    alpha = 0.25f + 1.9f * amount * (1f - amount),
                )
            }
        }
    }
}

@Composable
private fun Modifier.glassTabSelection(selected: Boolean): Modifier {
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val opacity = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "Glass tab selection",
    )
    return drawWithCache {
        val outline = CircleShape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val alpha = opacity.value
            if (alpha > 0f) drawOutline(outline, color = fill, alpha = alpha)
        }
    }
}

@Composable
private fun glassButtonColors(colors: ButtonColors): ButtonColors {
    if (!isGlassControl()) return colors
    val tint = glassSurfaceColor(colors.containerColor)
    return colors.copy(
        containerColor = tint,
        contentColor = glassControlContent(tint, colors.contentColor),
        disabledContainerColor = glassSurfaceColor(colors.disabledContainerColor),
    )
}

@Composable
private fun glassIconColors(colors: IconButtonColors): IconButtonColors {
    if (!isGlassControl()) return colors
    val tint = glassSurfaceColor(colors.containerColor)
    return colors.copy(
        containerColor = tint,
        contentColor = glassControlContent(tint, colors.contentColor),
        disabledContainerColor = glassSurfaceColor(colors.disabledContainerColor),
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    Button(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        shape = shape,
        colors = glassButtonColors(colors),
        elevation = if (isGlassControl()) null else elevation,
        border = if (isGlassControl() && enabled) glassBorder() else border,
        contentPadding = contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        shape = shape,
        colors = glassButtonColors(colors),
        elevation = if (isGlassControl()) null else elevation,
        border = if (isGlassControl() && enabled) glassBorder() else border,
        contentPadding = contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    TextButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        shape = shape,
        colors = glassButtonColors(colors),
        elevation = if (isGlassControl()) null else elevation,
        border = if (isGlassControl() && enabled) glassBorder() else border,
        contentPadding = contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        shape = shape,
        colors = glassButtonColors(colors),
        elevation = if (isGlassControl()) null else elevation,
        border = if (isGlassControl() && enabled) glassBorder() else border,
        contentPadding = contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = IconButtonDefaults.standardShape,
    content: @Composable () -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    IconButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled, outlined = true),
        enabled = enabled,
        shape = shape,
        colors = glassIconColors(colors),
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled, outlined = true),
        enabled = enabled,
        shape = shape,
        colors = glassIconColors(colors),
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    val glass = isGlassControl()
    val tint = glassSurfaceColor(containerColor)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.glassControlDecoration(shape, interactions, outlined = true),
        shape = shape,
        containerColor = tint,
        contentColor = if (glass) glassControlContent(tint, contentColor) else contentColor,
        elevation = if (glass) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else elevation,
        interactionSource = interactions,
        content = content,
    )
}

@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = glassInteractions(interactionSource)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = if (isGlassControl()) colors.copy(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            disabledContainerColor = Color.Transparent,
            disabledSelectedContainerColor = Color.Transparent,
        ) else colors,
        elevation = if (isGlassControl()) null else elevation,
        border = if (isGlassControl() && enabled) glassBorder() else border,
        interactionSource = interactions,
    )
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = glassInteractions(interactionSource)
    val checkedTint = glassSurfaceColor(colors.checkedTrackColor)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.glassControlDecoration(CircleShape, interactions, enabled)
            .glassSwitchGlint(checked, enabled),
        thumbContent = thumbContent,
        enabled = enabled,
        colors = if (isGlassControl()) colors.copy(
            checkedTrackColor = checkedTint,
            checkedThumbColor = glassControlContent(checkedTint, colors.checkedThumbColor),
            uncheckedTrackColor = Color.Transparent,
            disabledCheckedTrackColor = glassSurfaceColor(colors.disabledCheckedTrackColor),
            disabledUncheckedTrackColor = Color.Transparent,
        ) else colors,
        interactionSource = interactions,
    )
}

@Composable
fun GlassCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = glassInteractions(interactionSource)
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.glassToggleDecoration(RoundedCornerShape(4.dp), interactions, enabled),
        enabled = enabled,
        colors = colors,
        interactionSource = interactions,
    )
}

@Composable
fun GlassRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = glassInteractions(interactionSource)
    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier.glassToggleDecoration(CircleShape, interactions, enabled),
        enabled = enabled,
        colors = colors,
        interactionSource = interactions,
    )
}

@Composable
fun GlassTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    selectedContentColor: Color = LocalContentColor.current,
    unselectedContentColor: Color = selectedContentColor,
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = glassInteractions(interactionSource)
    val glass = isGlassControl() && enabled
    val tabModifier = if (glass) {
        modifier.glassControlDecoration(CircleShape, interactions).glassTabSelection(selected)
    } else modifier
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = tabModifier,
        enabled = enabled,
        text = text,
        icon = icon,
        selectedContentColor = if (glass && selectedContentColor == LocalContentColor.current) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else selectedContentColor,
        unselectedContentColor = unselectedContentColor,
        interactionSource = interactions,
    )
}

@Suppress("DEPRECATION")
@Composable
fun GlassTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = TabRowDefaults.primaryContainerColor,
    contentColor: Color = TabRowDefaults.primaryContentColor,
    indicator: @Composable (tabPositions: List<TabPosition>) -> Unit =
        @Composable { tabPositions ->
            if (selectedTabIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex])
                )
            }
        },
    divider: @Composable () -> Unit = @Composable { HorizontalDivider() },
    tabs: @Composable () -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = if (isGlassControl()) Color.Transparent else containerColor,
        contentColor = contentColor,
        indicator = indicator,
        divider = divider,
        tabs = tabs,
    )
}

@Composable
fun SingleChoiceSegmentedButtonRowScope.GlassSegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SegmentedButtonColors = SegmentedButtonDefaults.colors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SegmentedButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit = { SegmentedButtonDefaults.Icon(selected) },
    label: @Composable () -> Unit,
) {
    val interactions = glassInteractions(interactionSource)
    val resolvedBorder = border ?: if (isGlassControl() && enabled) glassBorder() else
        SegmentedButtonDefaults.borderStroke(
            when {
                enabled && selected -> colors.activeBorderColor
                enabled -> colors.inactiveBorderColor
                selected -> colors.disabledActiveBorderColor
                else -> colors.disabledInactiveBorderColor
            },
        )
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = shape,
        modifier = modifier.glassControlDecoration(shape, interactions, enabled),
        enabled = enabled,
        colors = if (isGlassControl()) colors.copy(
            activeContainerColor = glassSurfaceColor(colors.activeContainerColor),
            inactiveContainerColor = glassSurfaceColor(colors.inactiveContainerColor),
            disabledActiveContainerColor = glassSurfaceColor(colors.disabledActiveContainerColor),
            disabledInactiveContainerColor = glassSurfaceColor(colors.disabledInactiveContainerColor),
        ) else colors,
        border = resolvedBorder,
        contentPadding = contentPadding,
        interactionSource = interactions,
        icon = icon,
        label = label,
    )
}

@Composable
fun GlassSingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    space: Dp = SegmentedButtonDefaults.BorderWidth,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier,
        space = space,
        content = content,
    )
}
