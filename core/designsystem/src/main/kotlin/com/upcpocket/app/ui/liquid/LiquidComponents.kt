package com.upcpocket.app.ui.liquid

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.flow.collectLatest

/**
 * 全局顶栏：
 * - Liquid Glass 模式：清透无灰底玻璃顶栏（低模糊 + 轻透明纯光 + 边缘高光线）
 * - Material 3 模式：原生 Material 3 `TopAppBar`
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
        Color(0xFF10141D).copy(alpha = 0.28f)
    } else {
        Color.White.copy(alpha = 0.22f)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 6.dp,
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
                color = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 通用主按钮：Kyant0 流体高光与指向性弹性形变 + 高通透主题色调玻璃。
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
    val scheme = MaterialTheme.colorScheme
    LiquidInteractiveCapsuleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        hueTint = if (enabled) scheme.primary else Color.Unspecified,
        surfaceColor = if (enabled) scheme.primary.copy(alpha = 0.52f) else scheme.onSurface.copy(alpha = 0.10f),
        contentColor = if (enabled) Color.White else scheme.onSurface.copy(alpha = 0.38f),
        shadowColor = if (enabled) scheme.primary.copy(alpha = 0.24f) else null,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 液态次级按钮：超清透浅染色玻璃胶囊。
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
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    LiquidInteractiveCapsuleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        hueTint = if (enabled && !dark) scheme.primary.copy(alpha = 0.4f) else Color.Unspecified,
        surfaceColor = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.08f)
            dark -> Color.White.copy(alpha = 0.12f)
            else -> scheme.primary.copy(alpha = 0.14f)
        },
        contentColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 液态玻璃描边按钮：水晶清透透明玻璃 + 镜面高光边界。
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
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f
    LiquidInteractiveCapsuleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        surfaceColor = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.14f),
        contentColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f),
        borderStroke = BorderStroke(
            0.8.dp,
            if (dark) Color.White.copy(alpha = 0.22f) else scheme.outline.copy(alpha = 0.30f),
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun LiquidInteractiveCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    hueTint: Color = Color.Unspecified,
    surfaceColor: Color,
    contentColor: Color,
    shadowColor: Color? = null,
    borderStroke: BorderStroke? = null,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current!!
    val clipShape = resolveClipShape(shape)
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        LiquidInteractiveHighlight(animationScope = animationScope)
    }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 42.dp)
            .graphicsLayer {
                if (!enabled) return@graphicsLayer
                val progress = interactiveHighlight.pressProgress
                val scale = lerp(1f, 1f + 4.dp.toPx() / size.height.coerceAtLeast(1f), progress)
                val maxOffset = size.minDimension
                val offset = interactiveHighlight.offset
                if (maxOffset > 0f) {
                    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                }
                val maxDragScale = 4.dp.toPx() / size.height.coerceAtLeast(1f)
                val angle = atan2(offset.y, offset.x)
                val maxDim = size.maxDimension.coerceAtLeast(1f)
                val ratioW = (size.width / size.height.coerceAtLeast(1f)).fastCoerceAtMost(1f)
                val ratioH = (size.height / size.width.coerceAtLeast(1f)).fastCoerceAtMost(1f)
                scaleX = scale + maxDragScale * abs(cos(angle) * offset.x / maxDim) * ratioW
                scaleY = scale + maxDragScale * abs(sin(angle) * offset.y / maxDim) * ratioH
            }
            .let { base -> if (borderStroke != null) base.border(borderStroke, clipShape) else base }
            .clip(clipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (enabled) interactiveHighlight.gestureModifier else Modifier),
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
                            blurRadiusDp = 2.dp,
                            refractionHeightDp = 12.dp,
                            refractionAmountDp = 24.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = if (enabled) 0.90f else 0.30f) },
                    shadow = {
                        shadowColor?.let { Shadow(radius = 10.dp, color = it) }
                    },
                    onDrawSurface = {
                        if (hueTint.isSpecified) {
                            drawRect(hueTint, blendMode = BlendMode.Hue)
                        }
                        drawRect(surfaceColor)
                    },
                )
                .then(if (enabled) interactiveHighlight.modifier else Modifier),
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
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
 * Kyant0 风格流体玻璃开关：支持真实水平拖动手势（跟手果冻拉伸、滑块按压/拖拽转为色散放大镜），
 * 同时支持点击切换与无障碍 `Role.Switch` 语义。
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
    val accentColor = scheme.primary
    val trackColor = if (dark) Color(0xFF787880).copy(alpha = 0.34f) else Color(0xFF787880).copy(alpha = 0.22f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()

    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope, dragWidth, isLtr, enabled, onCheckedChange) {
        LiquidDampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.45f,
            onDragStarted = { didDrag = false },
            onDragStopped = {
                if (!enabled || onCheckedChange == null) {
                    didDrag = false
                } else if (didDrag) {
                    val target = if (targetValue >= 0.5f) 1f else 0f
                    fraction = target
                    onCheckedChange(target == 1f)
                    didDrag = false
                }
            },
            onDrag = { _, dragAmount ->
                if (enabled && onCheckedChange != null) {
                    if (!didDrag) didDrag = dragAmount.x != 0f
                    val delta = dragAmount.x / dragWidth
                    fraction = if (isLtr) {
                        (fraction + delta).fastCoerceIn(0f, 1f)
                    } else {
                        (fraction - delta).fastCoerceIn(0f, 1f)
                    }
                }
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { f ->
            dampedDragAnimation.updateValue(f)
        }
    }

    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(
        backdrop,
        rememberBackdrop(trackBackdrop) { drawTrack ->
            val p = dampedDragAnimation.pressProgress
            val sx = lerp(0.72f, 0.80f, p)
            val sy = lerp(0.10f, 0.78f, p)
            scale(sx, sy) { drawTrack() }
        },
    )

    val semanticsAndInteraction = if (onCheckedChange != null) {
        Modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    fraction = if (next) 1f else 0f
                    dampedDragAnimation.animateToValue(fraction)
                    onCheckedChange(next)
                },
            )
            .then(if (enabled) dampedDragAnimation.modifier else Modifier)
    } else {
        Modifier.semantics { role = Role.Switch }
    }

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .clip(GlassCapsuleClipShape)
            .then(semanticsAndInteraction),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道层（采集进 trackBackdrop 以供滑块按压拖拽时产生折射放大效果）
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .clip(GlassCapsuleClipShape)
                .drawBehind {
                    val v = dampedDragAnimation.value.fastCoerceIn(0f, 1f)
                    val baseTrack = lerp(trackColor, accentColor, v)
                    drawRect(if (enabled) baseTrack else baseTrack.copy(alpha = baseTrack.alpha * 0.45f))
                }
                .size(64.dp, 28.dp),
        )

        // 浮动的液态玻璃滑块（静态白润，按压/拖动转为色散透明透镜）
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val v = dampedDragAnimation.value.fastCoerceIn(0f, 1f)
                    val paddingPx = 2.dp.toPx()
                    translationX = if (isLtr) {
                        lerp(paddingPx, paddingPx + dragWidth, v)
                    } else {
                        lerp(-paddingPx, -(paddingPx + dragWidth), v)
                    }
                    val vel = (dampedDragAnimation.velocity / 50f).fastCoerceIn(-0.22f, 0.22f)
                    scaleX = dampedDragAnimation.scaleX / (1f - vel * 0.75f).fastCoerceIn(0.8f, 1.25f)
                    scaleY = dampedDragAnimation.scaleY * (1f - vel * 0.25f).fastCoerceIn(0.8f, 1.2f)
                }
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val p = dampedDragAnimation.pressProgress
                        val blurPx = (6.dp * (1f - p)).toPx()
                        if (blurPx > 0.5f) {
                            blur(blurPx)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && p > 0.01f) {
                            lens(
                                refractionHeight = (5.dp * p).toPx(),
                                refractionAmount = (10.dp * p).toPx(),
                                chromaticAberration = true,
                            )
                        }
                    },
                    highlight = {
                        val p = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = p,
                        )
                    },
                    shadow = {
                        Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.08f))
                    },
                    innerShadow = {
                        val p = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4.dp * p, alpha = p * 0.7f)
                    },
                    onDrawSurface = {
                        val p = dampedDragAnimation.pressProgress
                        val alpha = ((1f - p) * if (enabled) 0.96f else 0.55f).fastCoerceIn(0f, 1f)
                        drawRect(Color.White.copy(alpha = alpha))
                    },
                )
                .size(40.dp, 24.dp),
        )
    }
}

/**
 * 液态胶囊选项按钮：超清透玻璃底 + 指向性流体高亮与果冻推压手势，选中使用高透主题色。
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
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        LiquidInteractiveHighlight(animationScope = animationScope)
    }
    val interactionSource = remember { MutableInteractionSource() }

    val chipTint = when {
        selected -> scheme.primary.copy(alpha = if (dark) 0.34f else 0.22f)
        dark -> Color.White.copy(alpha = 0.07f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.primary
        else -> scheme.onSurface
    }

    Box(
        modifier = modifier
            .height(34.dp)
            .graphicsLayer {
                if (!enabled) return@graphicsLayer
                val p = interactiveHighlight.pressProgress
                val scale = lerp(1f, 1.08f, p)
                val maxOffset = size.minDimension
                val offset = interactiveHighlight.offset
                if (maxOffset > 0f) {
                    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                }
                scaleX = scale
                scaleY = scale
            }
            .clip(GlassCapsuleClipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .then(if (enabled) interactiveHighlight.gestureModifier else Modifier),
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
                            blurRadiusDp = 2.dp,
                            refractionHeightDp = 8.dp,
                            refractionAmountDp = 18.dp,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = if (selected) 0.96f else 0.65f) },
                    onDrawSurface = {
                        if (selected) {
                            drawRect(scheme.primary, blendMode = BlendMode.Hue)
                        }
                        drawRect(chipTint)
                    },
                )
                .then(if (enabled) interactiveHighlight.modifier else Modifier),
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
 * 通用液态玻璃弹窗容器。
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
        Color(0xFF141822).copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.68f)
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
                                blurRadiusDp = 12.dp,
                                refractionHeightDp = 14.dp,
                                refractionAmountDp = 28.dp,
                                depthEffect = true,
                            )
                        },
                        highlight = { Highlight.Default },
                        shadow = {
                            Shadow(
                                radius = 24.dp,
                                color = Color.Black.copy(alpha = if (dark) 0.40f else 0.14f),
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
        if (dark) Color(0xFF141822).copy(alpha = 0.82f) else Color.White.copy(alpha = 0.84f)
    } else {
        BottomSheetDefaults.ContainerColor
    }

/**
 * 通用液态玻璃面板。
 */
@Composable
fun LiquidPanel(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape,
    tintColor: Color? = null,
    fallbackColor: Color? = null,
    blurRadius: Dp = 4.dp,
    shadowElevation: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .liquidGlassSurface(
                    shape = shape,
                    tintColor = tintColor,
                    fallbackColor = fallbackColor,
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
