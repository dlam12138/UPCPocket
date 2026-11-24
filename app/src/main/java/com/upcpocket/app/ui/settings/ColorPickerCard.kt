package com.upcpocket.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.normalizeOpaqueSeedArgb
import com.upcpocket.app.ui.components.CampusTonalButton
import com.upcpocket.app.ui.components.GlassOutlinedTextField
import com.upcpocket.app.ui.theme.MaterialYouScheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 取色卡内部使用的 HSV 分量：H ∈ [0, 360)，S/V ∈ [0, 1]。 */
internal data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/** 快捷色卡预设（12 色，覆盖冷暖与低饱和色系）。 */
internal val CURATED_SWATCH_COLORS: List<Int> = listOf(
    0xFF1A73E8.toInt(),
    0xFF3949AB.toInt(),
    0xFF00838F.toInt(),
    0xFF2E7D32.toInt(),
    0xFF558B2F.toInt(),
    0xFFE8710A.toInt(),
    0xFFD84315.toInt(),
    0xFFC62828.toInt(),
    0xFFD81B60.toInt(),
    0xFF8E24AA.toInt(),
    0xFF6750A4.toInt(),
    0xFF546E7A.toInt(),
)

internal fun argbToHsv(argb: Int): HsvColor {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    val hue = when {
        delta <= 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val saturation = if (maxC <= 0f) 0f else delta / maxC
    return HsvColor(
        hue = hue.coerceIn(0f, 359.99f),
        saturation = saturation.coerceIn(0f, 1f),
        value = maxC.coerceIn(0f, 1f),
    )
}

internal fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun formatHexColor(argb: Int): String {
    val rgb = argb and 0xFFFFFF
    return "#%06X".format(rgb)
}

internal fun parseHexColor(input: String): Int? {
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toIntOrNull(16) ?: return null
    return normalizeOpaqueSeedArgb(rgb)
}

/**
 * 取色卡交互面板：2D 饱和度/明度色板 + 色相条 + 十六进制输入 + 快捷色卡 + 背景图取色。
 *
 * 拖动色板或色相条时仅更新本地预览状态，松手或点选色块后落盘并切换整套 Material You 配色，
 * 避免拖动期间频繁写 DataStore。
 */
@Composable
internal fun ColorPickerPanel(
    selectedArgb: Int,
    dark: Boolean,
    extractedBackgroundSeeds: List<Int>,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedSelected = normalizeOpaqueSeedArgb(selectedArgb)
    var hsv by remember { mutableStateOf(argbToHsv(normalizedSelected)) }
    var hexInput by remember { mutableStateOf(formatHexColor(normalizedSelected)) }
    var hexError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(normalizedSelected) {
        val currentPreview = hsvToArgb(hsv.hue, hsv.saturation, hsv.value)
        if (currentPreview != normalizedSelected) {
            hsv = argbToHsv(normalizedSelected)
        }
        hexInput = formatHexColor(normalizedSelected)
        hexError = null
    }

    val previewArgb = hsvToArgb(hsv.hue, hsv.saturation, hsv.value)
    val previewSwatches = remember(previewArgb, dark) {
        val scheme = MaterialYouScheme.fromSeed(seedArgb = previewArgb, dark = dark)
        listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary)
    }

    fun applyHexInput() {
        val parsed = parseHexColor(hexInput)
        if (parsed == null) {
            hexError = "请输入 6 位色值，例如 #1A73E8"
            return
        }
        hexError = null
        hsv = argbToHsv(parsed)
        hexInput = formatHexColor(parsed)
        onColorSelected(parsed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("color_picker_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SaturationValueBoard(
            hue = hsv.hue,
            saturation = hsv.saturation,
            value = hsv.value,
            onDrag = { s, v ->
                hsv = hsv.copy(saturation = s, value = v)
                hexInput = formatHexColor(hsvToArgb(hsv.hue, s, v))
                hexError = null
            },
            onCommit = { s, v ->
                hsv = hsv.copy(saturation = s, value = v)
                val argb = hsvToArgb(hsv.hue, s, v)
                hexInput = formatHexColor(argb)
                hexError = null
                onColorSelected(argb)
            },
        )

        HueSliderBar(
            hue = hsv.hue,
            onDrag = { h ->
                hsv = hsv.copy(hue = h)
                hexInput = formatHexColor(hsvToArgb(h, hsv.saturation, hsv.value))
                hexError = null
            },
            onCommit = { h ->
                hsv = hsv.copy(hue = h)
                val argb = hsvToArgb(h, hsv.saturation, hsv.value)
                hexInput = formatHexColor(argb)
                hexError = null
                onColorSelected(argb)
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(previewArgb))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Text(
                text = "配色预览",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.size(width = 50.dp, height = 24.dp)) {
                previewSwatches.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .padding(start = (index * 13).dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
            Box(Modifier.weight(1f))
            Text(
                text = formatHexColor(previewArgb),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassOutlinedTextField(
                value = hexInput,
                onValueChange = { raw ->
                    if (raw.length <= 7) {
                        hexInput = raw.uppercase()
                        hexError = null
                    }
                },
                singleLine = true,
                placeholder = { Text("#1A73E8") },
                isError = hexError != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { applyHexInput() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("color_picker_hex_input"),
            )
            CampusTonalButton(
                onClick = ::applyHexInput,
                modifier = Modifier.testTag("color_picker_hex_apply"),
            ) {
                Text("应用")
            }
        }

        if (hexError != null) {
            Text(
                text = hexError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = "快捷色卡",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CURATED_SWATCH_COLORS.forEach { swatchArgb ->
                ColorSwatchDot(
                    argb = swatchArgb,
                    selected = swatchArgb == normalizedSelected,
                    onClick = {
                        hsv = argbToHsv(swatchArgb)
                        hexInput = formatHexColor(swatchArgb)
                        hexError = null
                        onColorSelected(swatchArgb)
                    },
                )
            }
        }

        if (extractedBackgroundSeeds.isNotEmpty()) {
            Text(
                text = "从背景图取色",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                extractedBackgroundSeeds.forEachIndexed { index, seedArgb ->
                    ColorSwatchDot(
                        argb = seedArgb,
                        selected = seedArgb == normalizedSelected,
                        onClick = {
                            hsv = argbToHsv(seedArgb)
                            hexInput = formatHexColor(seedArgb)
                            hexError = null
                            onColorSelected(seedArgb)
                        },
                        modifier = Modifier.testTag("background_seed_$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SaturationValueBoard(
    hue: Float,
    saturation: Float,
    value: Float,
    onDrag: (saturation: Float, value: Float) -> Unit,
    onCommit: (saturation: Float, value: Float) -> Unit,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val pureHueColor = Color(hsvToArgb(hue, 1f, 1f))
    val selectedColor = Color(hsvToArgb(hue, saturation, value))
    val shape = RoundedCornerShape(16.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .semantics { contentDescription = "取色板" }
            .testTag("color_picker_sv_board")
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val v = (1f - offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    currentOnCommit(s, v)
                }
            }
            .pointerInput(Unit) {
                var lastS = 0f
                var lastV = 1f
                detectDragGestures(
                    onDragStart = { offset ->
                        lastS = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        lastV = (1f - offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        currentOnDrag(lastS, lastV)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        lastS = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        lastV = (1f - change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        currentOnDrag(lastS, lastV)
                    },
                    onDragEnd = { currentOnCommit(lastS, lastV) },
                    onDragCancel = { currentOnCommit(lastS, lastV) },
                )
            }
            .drawWithCache {
                val horizontal = Brush.horizontalGradient(listOf(Color.White, pureHueColor))
                val vertical = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                val thumbRadius = 10.dp.toPx()
                val outerStroke = Stroke(width = 2.5.dp.toPx())
                val innerStroke = Stroke(width = 1.dp.toPx())

                onDrawBehind {
                    drawRect(horizontal)
                    drawRect(vertical)
                    val center = Offset(
                        x = saturation.coerceIn(0f, 1f) * size.width,
                        y = (1f - value.coerceIn(0f, 1f)) * size.height,
                    )
                    drawCircle(color = selectedColor, radius = thumbRadius, center = center)
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = center,
                        style = outerStroke,
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.35f),
                        radius = thumbRadius + 1.5.dp.toPx(),
                        center = center,
                        style = innerStroke,
                    )
                }
            },
    )
}

@Composable
private fun HueSliderBar(
    hue: Float,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val thumbFill = Color(hsvToArgb(hue, 1f, 1f))
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .semantics { contentDescription = "色相条" }
            .testTag("color_picker_hue_bar")
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    currentOnCommit(fraction * 359.99f)
                }
            }
            .pointerInput(Unit) {
                var lastHue = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        lastHue = fraction * 359.99f
                        currentOnDrag(lastHue)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        lastHue = fraction * 359.99f
                        currentOnDrag(lastHue)
                    },
                    onDragEnd = { currentOnCommit(lastHue) },
                    onDragCancel = { currentOnCommit(lastHue) },
                )
            }
            .drawWithCache {
                val rainbow = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFFF0000),
                        Color(0xFFFFFF00),
                        Color(0xFF00FF00),
                        Color(0xFF00FFFF),
                        Color(0xFF0000FF),
                        Color(0xFFFF00FF),
                        Color(0xFFFF0000),
                    ),
                )
                val thumbRadius = (size.height / 2f - 2.dp.toPx()).coerceAtLeast(8.dp.toPx())
                val outerStroke = Stroke(width = 2.5.dp.toPx())
                val outlineStroke = Stroke(width = 1.dp.toPx())

                onDrawBehind {
                    drawRect(rainbow)
                    val x = (hue.coerceIn(0f, 360f) / 360f) *
                        (size.width - thumbRadius * 2f).coerceAtLeast(0f) + thumbRadius
                    val center = Offset(x = x, y = size.height / 2f)
                    drawCircle(color = thumbFill, radius = thumbRadius, center = center)
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = center,
                        style = outerStroke,
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.28f),
                        radius = thumbRadius + 1.dp.toPx(),
                        center = center,
                        style = outlineStroke,
                    )
                }
            },
    )
}

@Composable
private fun ColorSwatchDot(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = Color(argb)
    val checkTint = if (color.luminance() > 0.55f) Color.Black else Color.White
    val hexLabel = formatHexColor(argb)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(
                role = Role.Button,
                onClickLabel = "选择色卡 $hexLabel",
                onClick = onClick,
            )
            .semantics { contentDescription = "色卡 $hexLabel" },
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = checkTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
