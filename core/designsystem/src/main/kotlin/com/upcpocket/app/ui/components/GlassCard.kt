package com.upcpocket.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalHasCustomBackground
import com.upcpocket.app.ui.theme.LocalUiStyle

/** Shared card surface; Material 3 semantics and the upstream nullable-click API are retained. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    optical: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val isGlass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val border = if (isGlass) null else glassBorder()
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = if (!isGlass && LocalHasCustomBackground.current) {
        baseColor.copy(alpha = 0.86f)
    } else {
        baseColor
    }
    val body: @Composable () -> Unit = {
        if (isGlass && !optical) {
            GlassNestedSurface { Box(content = { content() }) }
        } else {
            Box(content = { content() })
        }
    }
    if (onClick != null) {
        GlassSurface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border,
            modifier = modifier,
            optical = optical,
            content = body,
        )
    } else {
        GlassSurface(
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border,
            modifier = modifier,
            optical = optical,
            content = body,
        )
    }
}
