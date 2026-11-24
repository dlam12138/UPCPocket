package com.upcpocket.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 分段比例条的单段数据。 */
data class RatioSegment(
    val label: String,
    val value: Float,
    val color: Color,
    val valueText: String? = null,
)

/** 柱状图单个柱体数据。 */
data class ChartBarItem(
    val label: String,
    val value: Float,
    val valueLabel: String,
    val highlighted: Boolean = false,
)

/**
 * Material 3 多段比例横条，带可选图例。
 *
 * 用于校园卡余额构成、收支对比、教室空闲率等直观比例展示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedRatioBar(
    segments: List<RatioSegment>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 8.dp,
    showLegend: Boolean = true,
) {
    val positive = remember(segments) { segments.filter { it.value > 0f } }
    val total = remember(positive) { positive.sumOf { it.value.toDouble() }.toFloat() }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = radius,
            )
            if (total > 0f && positive.isNotEmpty()) {
                val gapPx = if (positive.size > 1) 2.dp.toPx() else 0f
                val totalGaps = gapPx * (positive.size - 1)
                val availableWidth = (size.width - totalGaps).coerceAtLeast(0f)
                val weights = FloatArray(positive.size) { idx ->
                    (positive[idx].value / total).coerceAtLeast(0.02f)
                }
                val weightSum = weights.sum().coerceAtLeast(0.0001f)
                var x = 0f
                for (index in positive.indices) {
                    val segWidth = availableWidth * (weights[index] / weightSum)
                    if (segWidth > 0f) {
                        drawRoundRect(
                            color = positive[index].color,
                            topLeft = Offset(x, 0f),
                            size = Size(segWidth, size.height),
                            cornerRadius = radius,
                        )
                    }
                    x += segWidth + gapPx
                }
            }
        }
        if (showLegend && segments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                segments.forEach { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(segment.color),
                        )
                        val text = if (segment.valueText != null) {
                            "${segment.label} ${segment.valueText}"
                        } else {
                            segment.label
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 通用横向指标条：标题 + 右侧数值 + 圆角占比条。
 */
@Composable
fun HorizontalMetricBar(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    barHeight: Dp = 7.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor),
        ) {
            if (clamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped.coerceAtLeast(0.03f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 轻量 Material 3 柱状趋势图（支持点选单柱反馈）。
 */
@Composable
fun DailyBarChart(
    bars: List<ChartBarItem>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    selectedColor: Color = MaterialTheme.colorScheme.tertiary,
    chartHeight: Dp = 108.dp,
) {
    if (bars.isEmpty()) return
    val maxVal = bars.maxOf { it.value }.coerceAtLeast(1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { index, item ->
            val isSelected = selectedIndex == index || item.highlighted
            val ratio = (item.value / maxVal).coerceIn(0f, 1f)
            val fillColor = when {
                isSelected -> selectedColor
                item.value <= 0f -> MaterialTheme.colorScheme.surfaceVariant
                else -> barColor.copy(alpha = 0.78f)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (onSelect != null) {
                            Modifier.clickable { onSelect(index) }
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        contentDescription = "${item.label} ${item.valueLabel}"
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = item.valueLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.54f)
                        .height(chartHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    )
                    val visualHeightFraction = if (item.value <= 0f) 0.04f else ratio.coerceAtLeast(0.08f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(visualHeightFraction)
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(fillColor),
                    )
                }
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 环形百分比徽标，用于空闲率、达成率等单值可视化。
 */
@Composable
fun RatioRingBadge(
    fraction: Float,
    centerText: String,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    size: Dp = 56.dp,
    strokeWidth: Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (clamped > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clamped,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Text(
                text = centerText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
