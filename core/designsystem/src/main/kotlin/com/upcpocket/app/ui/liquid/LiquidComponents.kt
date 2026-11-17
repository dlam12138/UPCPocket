package com.upcpocket.app.ui.liquid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.upcpocket.app.designsystem.UiStyle

/**
 * 全局顶栏：
 * - Liquid Glass 模式：通透磨砂玻璃浮层（自适配状态栏 inset 与底部高光线）
 * - Material 3 模式：原汁原味 Material 3 `TopAppBar`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    if (!isLiquidGlassActive()) {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val barTint = if (dark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.58f)
    } else {
        scheme.surfaceContainerLowest.copy(alpha = 0.66f)
    }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 16.dp,
                            refractionHeightDp = 0.dp,
                            refractionAmountDp = 0.dp,
                        )
                    },
                    highlight = { null },
                    shadow = { null },
                    onDrawSurface = { drawRect(barTint) },
                ),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                    navigationIcon()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                            title()
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                        actions()
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = if (dark) Color.White.copy(alpha = 0.14f) else scheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

/**
 * 通用主按钮：Liquid Glass 模式下使用折射高光胶囊玻璃按钮，Material 3 模式使用标准 `Button`。
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassCapsule,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isLiquidGlassActive()) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = spring(0.6f, 600f),
        label = "liquidButtonScale",
    )
    val surfaceTint = if (enabled) {
        scheme.primary.copy(alpha = 0.82f)
    } else {
        scheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)

    val clipShape = when (shape) {
        GlassCapsule -> GlassCapsuleClipShape
        GlassCardShape -> GlassCardClipShape
        GlassDialogShape -> GlassDialogClipShape
        else -> androidx.compose.foundation.shape.CircleShape
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(clipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 8.dp,
                            refractionHeightDp = 8.dp,
                            refractionAmountDp = 16.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = if (enabled) 0.85f else 0.25f) },
                    shadow = {
                        if (enabled) Shadow(radius = 8.dp, color = scheme.primary.copy(alpha = 0.22f)) else null
                    },
                    onDrawSurface = { drawRect(surfaceTint) },
                ),
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    content()
                }
            }
        }
    }
}

/**
 * 液态次级按钮：Liquid Glass 下呈现色调毛玻璃胶囊，Material 3 下对应 `FilledTonalButton`。
 */
@Composable
fun LiquidTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassCapsule,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isLiquidGlassActive()) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = spring(0.6f, 600f),
        label = "liquidTonalButtonScale",
    )
    val surfaceTint = if (enabled) {
        scheme.secondaryContainer.copy(alpha = if (dark) 0.58f else 0.70f)
    } else {
        scheme.onSurface.copy(alpha = 0.10f)
    }
    val contentColor = if (enabled) scheme.onSecondaryContainer else scheme.onSurface.copy(alpha = 0.38f)

    val clipShape = when (shape) {
        GlassCapsule -> GlassCapsuleClipShape
        GlassCardShape -> GlassCardClipShape
        GlassDialogShape -> GlassDialogClipShape
        else -> androidx.compose.foundation.shape.CircleShape
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(clipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 8.dp,
                            refractionHeightDp = 6.dp,
                            refractionAmountDp = 12.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.65f) },
                    onDrawSurface = { drawRect(surfaceTint) },
                ),
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    content()
                }
            }
        }
    }
}

/**
 * 液态玻璃描边按钮：Liquid Glass 下透明清晰折射，Material 3 下为 `OutlinedButton`。
 */
@Composable
fun LiquidOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassCapsule,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isLiquidGlassActive()) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = spring(0.6f, 600f),
        label = "liquidOutlinedScale",
    )
    val surfaceTint = if (dark) Color.White.copy(alpha = 0.08f) else scheme.surface.copy(alpha = 0.42f)
    val contentColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f)

    val clipShape = resolveClipShape(shape)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                BorderStroke(1.dp, scheme.outline.copy(alpha = if (enabled) 0.45f else 0.20f)),
                clipShape,
            )
            .clip(clipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 6.dp,
                            refractionHeightDp = 6.dp,
                            refractionAmountDp = 12.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.6f) },
                    onDrawSurface = { drawRect(surfaceTint) },
                ),
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    content()
                }
            }
        }
    }
}

/**
 * 液态玻璃开关：Liquid Glass 下使用胶囊滑块 + 折射高光，Material 3 下使用原生 `Switch`。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!isLiquidGlassActive()) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> scheme.primary.copy(alpha = 0.32f)
            !enabled -> scheme.onSurface.copy(alpha = 0.14f)
            checked -> scheme.primary.copy(alpha = 0.86f)
            dark -> Color.White.copy(alpha = 0.22f)
            else -> scheme.surfaceVariant.copy(alpha = 0.72f)
        },
        label = "liquidToggleTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = spring(0.55f, 520f),
        label = "liquidToggleOffset",
    )

    Box(
        modifier = modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(GlassCapsuleClipShape)
            .let { base ->
                if (onCheckedChange != null) {
                    base.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    base
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 6.dp,
                            refractionHeightDp = 4.dp,
                            refractionAmountDp = 8.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.6f) },
                    onDrawSurface = { drawRect(trackColor) },
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(width = 28.dp, height = 24.dp)
                .clip(GlassCapsuleClipShape)
                .background(Color.White.copy(alpha = if (enabled) 0.94f else 0.6f), GlassCapsuleClipShape)
                .border(0.75.dp, Color.White.copy(alpha = 0.8f), GlassCapsuleClipShape),
        )
    }
}

/**
 * 液态胶囊筛选标签：Liquid Glass 下使用带高光的透明玻璃胶囊，Material 3 下回退 `FilterChip`。
 */
@Composable
fun LiquidFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    if (!isLiquidGlassActive()) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val chipTint = when {
        selected -> scheme.primaryContainer.copy(alpha = if (dark) 0.76f else 0.82f)
        dark -> scheme.surfaceContainerHigh.copy(alpha = 0.48f)
        else -> scheme.surfaceContainerLowest.copy(alpha = 0.58f)
    }
    val contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant

    Box(
        modifier = modifier
            .height(34.dp)
            .clip(GlassCapsuleClipShape)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { GlassCapsule },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 8.dp,
                            refractionHeightDp = 6.dp,
                            refractionAmountDp = 12.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = if (selected) 0.9f else 0.45f) },
                    onDrawSurface = { drawRect(chipTint) },
                ),
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                if (leadingIcon != null) {
                    leadingIcon()
                }
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    label()
                }
            }
        }
    }
}

/**
 * 通用液态玻璃弹窗容器：在 Liquid Glass 模式下包裹半透明高模糊玻璃圆角面板；
 * 在 Material 3 模式下使用标准 `AlertDialog`。
 */
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    if (!isLiquidGlassActive()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
        return
    }

    val backdrop = LocalGlassBackdrop.current!!
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    val dialogTint = if (dark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.82f)
    } else {
        scheme.surfaceContainerLowest.copy(alpha = 0.86f)
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Box(modifier = modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { GlassDialogShape },
                        effects = {
                            applyGlassEffects(
                                blurRadiusDp = 22.dp,
                                refractionHeightDp = 14.dp,
                                refractionAmountDp = 28.dp,
                                depthEffect = true,
                            )
                        },
                        highlight = { Highlight.Default },
                        shadow = {
                            Shadow(
                                radius = 24.dp,
                                color = Color.Black.copy(alpha = if (dark) 0.42f else 0.18f),
                            )
                        },
                        onDrawSurface = { drawRect(dialogTint) },
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GlassDialogClipShape)
                    .padding(24.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides scheme.secondary) {
                            icon()
                        }
                    }
                }
                if (title != null) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                            title()
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (text != null) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                            text()
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun liquidScaffoldColor(): Color =
    if (isLiquidGlassActive()) Color.Transparent else MaterialTheme.colorScheme.surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun liquidSheetContainerColor(): Color =
    if (isLiquidGlassActive()) {
        val scheme = MaterialTheme.colorScheme
        val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
        if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f) else scheme.surfaceContainerLowest.copy(alpha = 0.90f)
    } else {
        androidx.compose.material3.BottomSheetDefaults.ContainerColor
    }

/**
 * 通用液态玻璃面板：供 Banner、图表容器、底部操作栏使用。
 */
@Composable
fun LiquidPanel(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape,
    tintColor: Color? = null,
    blurRadius: Dp = 12.dp,
    shadowElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .liquidGlassSurface(
                    shape = shape,
                    tintColor = tintColor,
                    blurRadius = blurRadius,
                    shadowElevation = shadowElevation,
                ),
        )
        Box(modifier = Modifier.clip(resolveClipShape(shape)), content = content)
    }
}

private fun resolveClipShape(shape: Shape): Shape = when (shape) {
    GlassCapsule -> GlassCapsuleClipShape
    GlassCardShape -> GlassCardClipShape
    GlassDialogShape -> GlassDialogClipShape
    else -> GlassCapsuleClipShape
}
