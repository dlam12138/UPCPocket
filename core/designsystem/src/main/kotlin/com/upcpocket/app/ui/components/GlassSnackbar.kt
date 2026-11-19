package com.upcpocket.app.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle

/**
 * A snackbar samples a sibling content layer. Keep the host, navigation and floating buttons
 * outside the recorded layer so the snackbar cannot sample itself.
 */
class GlassSnackbarBackdrop internal constructor(internal val layer: LayerBackdrop?, internal val owner: View) {
    internal var visibleSurfaces by mutableIntStateOf(0)

    fun recordContent(background: Color): Modifier {
        val source = layer ?: return Modifier
        if (visibleSurfaces == 0) return Modifier
        return Modifier.layerBackdrop(source).background(background)
    }
}

@Composable
fun rememberGlassSnackbarBackdrop(): GlassSnackbarBackdrop? {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) return null
    val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberLayerBackdrop() else null
    val owner = LocalView.current
    return remember(layer, owner) { GlassSnackbarBackdrop(layer, owner) }
}

/** The native host retains queuing, timeout, assistive announcements and transition ownership. */
@Composable
fun GlassSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    backdrop: GlassSnackbarBackdrop? = null,
    snackbar: @Composable (SnackbarData) -> Unit = { DefaultGlassSnackbar(it, backdrop) },
) {
    val visibility = if (isGlassSheetVisible()) modifier.alpha(0f) else modifier
    SnackbarHost(hostState = hostState, modifier = visibility, snackbar = snackbar)
}

@Composable
private fun DefaultGlassSnackbar(data: SnackbarData, backdrop: GlassSnackbarBackdrop?) {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) {
        Snackbar(snackbarData = data)
        return
    }

    val scheme = MaterialTheme.colorScheme
    val owner = LocalView.current
    val capture = backdrop?.takeIf { it.owner === owner && it.layer != null }
    if (capture != null) {
        DisposableEffect(capture) {
            capture.visibleSurfaces++
            onDispose { capture.visibleSurfaces-- }
        }
    }

    val source = capture?.layer
    val shape = RoundedCornerShape(20.dp)
    val dark = scheme.surface.luminance() < 0.5f
    val backing = scheme.surfaceContainerHigh.copy(alpha = 0.92f)
    val reveal = rememberWindowReveal(key = data, durationMillis = 320)
    val inset = Modifier.padding(12.dp)
    val material = if (source != null) {
        inset.drawBackdrop(
            backdrop = source,
            shape = { shape },
            effects = {
                blur(3.dp.toPx())
                lens(9.dp.toPx(), 15.dp.toPx(), depthEffect = false)
            },
            highlight = null,
            shadow = null,
            innerShadow = null,
        )
    } else {
        inset
    }

    val visuals = data.visuals
    Row(
        modifier = material.glassSnackbarReflections(shape, scheme.primary, dark, backing) { reveal.value }
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = visuals.message,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        visuals.actionLabel?.let { label ->
            val interactions = remember { MutableInteractionSource() }
            TextButton(
                onClick = data::performAction,
                modifier = Modifier.glassPressHighlight(ButtonDefaults.textShape, interactions, compress = true),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.primary),
                interactionSource = interactions,
            ) { Text(label) }
        }
        if (visuals.withDismissAction) {
            IconButton(onClick = data::dismiss) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(androidx.compose.material3.R.string.m3c_snackbar_dismiss),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A thin directional rim and a single glint sit behind the message and its action. */
private fun Modifier.glassSnackbarReflections(
    shape: Shape,
    accent: Color,
    dark: Boolean,
    backing: Color,
    reveal: () -> Float,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val materialPath = when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
    val face = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.13f else 0.30f).compositeOver(backing),
        0.35f to Color.White.copy(alpha = if (dark) 0.035f else 0.07f).compositeOver(backing),
        1f to backing,
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.72f else 0.90f),
        0.36f to Color.White.copy(alpha = 0.36f),
        0.65f to Color.White.copy(alpha = 0.17f),
        1f to accent.copy(alpha = if (dark) 0.43f else 0.31f),
        end = Offset(size.width, size.height * 1.8f),
    )
    val rimStroke = Stroke(width = 2.dp.toPx())
    val glintWidth = size.width * 0.38f
    val glintSize = Size(glintWidth, size.height * 0.56f)
    val glint = Brush.linearGradient(
        0f to Color.Transparent,
        0.44f to Color.White,
        1f to Color.Transparent,
        end = Offset(glintWidth.coerceAtLeast(1f), glintSize.height * 0.45f),
    )
    onDrawBehind {
        drawOutline(outline, face)
        clipPath(materialPath) {
            drawOutline(outline, rim, style = rimStroke)
            val progress = reveal()
            if (progress > 0f && progress < 1f) {
                translate(left = progress * (size.width + glintWidth) - glintWidth) {
                    drawRect(glint, size = glintSize, alpha = 0.60f * progress * (1f - progress))
                }
            }
        }
    }
}
