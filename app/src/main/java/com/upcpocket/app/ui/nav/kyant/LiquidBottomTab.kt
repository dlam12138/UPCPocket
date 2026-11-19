/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Source: Kyant0/AndroidLiquidGlass, tag 1.0.6 (896a94a3ade1cc1a940b92365f942a34971fecda).
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.6/catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt
 * Modified for UPC Pocket: relocated package; integration changes are recorded in NOTICE.md.
 */
package com.upcpocket.app.ui.nav.kyant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

internal val LocalLiquidBottomTabInteractive =
    staticCompositionLocalOf { true }

internal val LocalLiquidBottomTabSelectedColorFilter =
    staticCompositionLocalOf<() -> ColorFilter?> { { null } }

/**
 * A single equal-width tab. The host supplies its accessible label through text or [modifier].
 * The hidden refraction copy is visual-only and never adds a second click/focus target.
 */
@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val selectedColorFilter = LocalLiquidBottomTabSelectedColorFilter.current
    val interactionModifier = if (LocalLiquidBottomTabInteractive.current) {
        Modifier.selectable(
            selected = selected,
            interactionSource = null,
            indication = null,
            role = Role.Tab,
            onClick = onClick
        )
    } else {
        Modifier
    }
    Column(
        modifier
            .clip(Capsule())
            .then(interactionModifier)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
                colorFilter = if (selected) selectedColorFilter() else null
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
