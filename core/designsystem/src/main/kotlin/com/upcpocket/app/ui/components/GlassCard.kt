package com.upcpocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle

/** Shared card surface; Material 3 semantics and the upstream nullable-click API are retained. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = if (LocalUiStyle.current == UiStyle.LIQUID_GLASS) null else
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    if (onClick != null) {
        GlassSurface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border,
            modifier = modifier,
            optical = true,
        ) {
            Box(content = { content() })
        }
    } else {
        GlassSurface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border,
            modifier = modifier,
            optical = true,
        ) {
            Box(content = { content() })
        }
    }
}
