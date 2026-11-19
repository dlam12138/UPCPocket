package com.upcpocket.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle
import kotlin.math.PI
import kotlin.math.sin

/**
 * Window chrome uses a cached glass fill and a finite entrance glint. Native Material 3 owns focus,
 * popup positioning, dismissal, scrolling, insets and accessibility. Nested controls never
 * sample the activity's live backdrop from a separate dialog/popup window.
 */
@Composable
private fun Modifier.glassWindowMaterial(
    shape: Shape,
    baseColor: Color,
    reveal: () -> Float = { 1f },
    drawBase: Boolean = true,
): Modifier {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS || baseColor.alpha == 0f) return this
    val dark = baseColor.luminance() < 0.5f
    // A continuous neutral veil lets the underlying window remain visible. No dark plate or
    // transparent middle stripe is inserted between two bright bands.
    val top = Color.White.copy(alpha = if (dark) 0.075f else 0.11f)
    val bottom = Color.White.copy(alpha = if (dark) 0.025f else 0.045f)
    val edgeTop = Color.White.copy(alpha = if (dark) 0.24f else 0.40f)
    val edgeBottom = Color.White.copy(alpha = if (dark) 0.08f else 0.14f)
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill = Brush.linearGradient(
            0f to top, 1f to bottom,
            start = Offset(size.width * 0.3f, 0f),
            end = Offset(size.width * 0.65f, size.height),
        )
        val rim = Brush.verticalGradient(listOf(edgeTop, edgeBottom))
        val stroke = Stroke(1.dp.toPx())
        val clip = when (outline) {
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
        }
        val glintWidth = size.width * 0.42f
        val glint = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White, Color.Transparent),
            startX = 0f,
            endX = glintWidth.coerceAtLeast(1f),
        )
        val glintSize = Size(glintWidth, size.height)
        val glintOpacity = if (dark) 0.12f else 0.20f
        onDrawBehind {
            if (drawBase) {
                drawOutline(outline, fill)
                drawOutline(outline, rim, style = stroke)
            }
            // Only this draw phase observes the short-lived animation. Brushes/outline stay cached.
            val progress = reveal().coerceIn(0f, 1f)
            if (progress > 0f && progress < 1f) {
                val opacity = sin(progress * PI).toFloat() * glintOpacity
                clipPath(clip) {
                    translate(left = -glintWidth + progress * (size.width + glintWidth)) {
                        drawRect(glint, size = glintSize, alpha = opacity)
                    }
                }
            }
        }
    }
}

/** Animatable honors Compose's duration scale; there is no idle ticker or delayed dismissal. */
@Composable
internal fun rememberWindowReveal(
    key: Any = Unit,
    active: Boolean = true,
    durationMillis: Int = 360,
): Animatable<Float, AnimationVector1D> {
    val scope = rememberCoroutineScope()
    val motionEnabled = scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f
    val reveal = remember(key) { Animatable(if (active && motionEnabled) 0f else 1f) }
    LaunchedEffect(reveal, active, durationMillis) {
        if (!active || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
            reveal.snapTo(1f)
        } else {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
        }
    }
    return reveal
}

private fun Modifier.glassWindowEntrance(reveal: () -> Float): Modifier = graphicsLayer {
    val progress = reveal().coerceIn(0f, 1f)
    val scale = 0.965f + 0.035f * progress
    scaleX = scale
    scaleY = scale
    alpha = 0.90f + 0.10f * progress
}

@Composable
private fun glassWindowActive(): Boolean = LocalUiStyle.current == UiStyle.LIQUID_GLASS

internal class GlassSheetVisibility {
    var count by mutableIntStateOf(0)
}

internal val LocalGlassSheetVisibility = staticCompositionLocalOf<GlassSheetVisibility?> { null }

/** Keeps the floating controls behind a translucent sheet from reading as part of the sheet. */
@Composable
fun isGlassSheetVisible(): Boolean = (LocalGlassSheetVisibility.current?.count ?: 0) > 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val glass = glassWindowActive()
    GlassNestedSurface {
        TopAppBar(
            title = title,
            // A full-width painted pane reads as a band, so glass top bars stay transparent.
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = expandedHeight,
            windowInsets = windowInsets,
            colors = if (glass) colors.copy(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ) else colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = glassWindowActive()
    val reveal = if (glass) rememberWindowReveal(durationMillis = 280) else null
    val decoration = if (reveal != null) {
        modifier.glassWindowEntrance { reveal.value }
            .glassWindowMaterial(shape, containerColor, reveal = { reveal.value })
    } else modifier
    GlassNestedSurface {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = decoration,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = if (glass) Color.Transparent else containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = if (glass) 0.dp else tonalElevation,
            properties = properties,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = glassWindowActive()
    val visibility = LocalGlassSheetVisibility.current
    if (glass && visibility != null) {
        DisposableEffect(visibility) {
            visibility.count++
            onDispose { visibility.count-- }
        }
    }
    GlassNestedSurface {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            sheetGesturesEnabled = sheetGesturesEnabled,
            shape = shape,
            containerColor = if (glass && containerColor.alpha > 0f) {
                Color.White.copy(alpha = if (containerColor.luminance() < 0.5f) 0.055f else 0.09f)
            } else containerColor,
            contentColor = contentColor,
            tonalElevation = if (glass) 0.dp else tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
        ) {
            // The native sheet positions its surface after applying the caller's modifier.
            // Draw the sheen inside the translated surface, beneath the sheet's own controls.
            if (glass && containerColor.alpha > 0f) {
                val reveal = rememberWindowReveal(
                    key = sheetState,
                    active = sheetState.targetValue != SheetValue.Hidden || sheetState.isVisible,
                )
                Column(
                    Modifier.fillMaxWidth()
                        .glassWindowMaterial(shape, containerColor, reveal = { reveal.value }, drawBase = false),
                    content = content,
                )
            } else {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape = DatePickerDefaults.shape,
    tonalElevation: Dp = DatePickerDefaults.TonalElevation,
    colors: DatePickerColors = DatePickerDefaults.colors(),
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = glassWindowActive()
    val reveal = if (glass) rememberWindowReveal(durationMillis = 280) else null
    val decoration = if (reveal != null) {
        modifier.glassWindowEntrance { reveal.value }
            .glassWindowMaterial(shape, colors.containerColor, reveal = { reveal.value })
    } else modifier
    GlassNestedSurface {
        DatePickerDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = decoration,
            dismissButton = dismissButton,
            shape = shape,
            tonalElevation = if (glass) 0.dp else tonalElevation,
            colors = if (glass) colors.copy(containerColor = Color.Transparent) else colors,
            properties = properties,
            content = content,
        )
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = glassWindowActive()
    val reveal = if (glass) rememberWindowReveal(active = expanded) else null
    val decoration = if (reveal != null) {
        modifier.glassWindowMaterial(shape, containerColor, reveal = { reveal.value })
    } else modifier
    GlassNestedSurface {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = decoration,
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = shape,
            containerColor = if (glass) Color.Transparent else containerColor,
            tonalElevation = if (glass) 0.dp else tonalElevation,
            shadowElevation = if (glass) 0.dp else shadowElevation,
            border = border,
            content = content,
        )
    }
}

@Composable
fun GlassDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    val glass = glassWindowActive()
    val interactions = if (glass) interactionSource ?: remember { MutableInteractionSource() } else interactionSource
    val decoration = if (glass && enabled) {
        modifier.glassPressHighlight(RectangleShape, interactions)
    } else modifier
    GlassNestedSurface {
        DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = decoration,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = interactions,
        )
    }
}
