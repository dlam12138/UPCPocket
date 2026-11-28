package com.upcpocket.app.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val FOCUS_ZOOM = 2.4f

/**
 * 画布视口记忆。中心存图面坐标而不是屏幕平移量，尺寸或比例变化后可重新换算平移并约束，
 * 旋转、键盘引起的紧凑/常规布局切换不会丢用户当前视图。
 */
internal class FloorPlanViewportState(
    zoom: Float = MIN_ZOOM,
    centerX: Float,
    centerY: Float,
    handledFocusRevision: Int = 0,
) {
    var zoom by mutableFloatStateOf(zoom)
    var centerX by mutableFloatStateOf(centerX)
    var centerY by mutableFloatStateOf(centerY)
    var handledFocusRevision by mutableIntStateOf(handledFocusRevision)

    val center: Offset get() = Offset(centerX, centerY)

    companion object {
        fun initial(sheet: FloorPlanSheet): FloorPlanViewportState = FloorPlanViewportState(
            centerX = sheet.width / 2f,
            centerY = sheet.height / 2f,
        )
    }
}

internal fun floorPlanViewportStateSaver(
    viewportKey: String,
): Saver<FloorPlanViewportState, Any> = listSaver(
    save = { state ->
        listOf<Any>(viewportKey, state.zoom, state.centerX, state.centerY, state.handledFocusRevision)
    },
    restore = { saved ->
        // rememberSaveable 的 inputs 不参与校验恢复出的 Saver 数据。旧单栋状态没有图面键，
        // 因而不能恢复到新共绘图；新状态只有图面键完全相同才可恢复。
        val values = when {
            saved.size == 5 && saved[0] == viewportKey -> saved.drop(1)
            saved.size == 4 && !viewportKey.startsWith("page-5:") -> saved
            else -> null
        }
        val zoom = values?.getOrNull(0) as? Float
        val centerX = values?.getOrNull(1) as? Float
        val centerY = values?.getOrNull(2) as? Float
        val handledFocusRevision = values?.getOrNull(3) as? Int
        if (zoom == null || centerX == null || centerY == null || handledFocusRevision == null) {
            null
        } else {
            FloorPlanViewportState(
                zoom = zoom,
                centerX = centerX,
                centerY = centerY,
                handledFocusRevision = handledFocusRevision,
            )
        }
    },
)

@Composable
internal fun FloorPlanCanvas(
    building: FloorPlanBuilding,
    sheet: FloorPlanSheet,
    renderedSheets: List<FloorPlanSheet>,
    sharedLinework: FloorPlanSharedLinework?,
    highlight: FloorPlanRoomRef?,
    focusRevision: Int,
    viewport: FloorPlanViewportState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val canvasBackground = colors.surfaceContainerLowest
    val floorFill = colors.surfaceContainerHigh
    val floorOutline = colors.outline.copy(alpha = 0.6f)
    val stairsFill = colors.secondaryContainer
    val elevatorFill = colors.tertiaryContainer
    val roomFill = colors.surface
    val roomOutline = colors.outlineVariant
    val roomLabelColor = colors.onSurfaceVariant
    val publicLabelColor = colors.onSecondaryContainer
    val highlightFill = colors.primaryContainer
    val highlightOutline = colors.primary
    val highlightLabelColor = colors.onPrimaryContainer
    val sharedLineColor = colors.onSurfaceVariant.copy(alpha = 0.78f)

    val textMeasurer = rememberTextMeasurer()
    val sharedPath = remember(sharedLinework) { sharedLinework?.asPath() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val fitPadding = with(density) { 12.dp.toPx() }
    val labelPadding = with(density) { 4.dp.toPx() }
    val minLabelWidth = with(density) { 34.dp.toPx() }
    val minLabelHeight = with(density) { 12.dp.toPx() }
    val fitScale = remember(viewportSize, sheet.width, sheet.height, fitPadding) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) {
            1f
        } else {
            val availableWidth = (viewportSize.width - fitPadding * 2f).coerceAtLeast(1f)
            val availableHeight = (viewportSize.height - fitPadding * 2f).coerceAtLeast(1f)
            minOf(availableWidth / sheet.width, availableHeight / sheet.height).coerceAtLeast(0.01f)
        }
    }

    fun zoomBy(factor: Float) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return
        val target = (viewport.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (target == viewport.zoom) return
        // 用户主动缩放时以当下可见的有效中心为基准，并保存缩放后的有效位置。
        val effectiveCenter = viewport.effectiveCenter(
            totalScale = fitScale * viewport.zoom,
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
            sheet = sheet,
        )
        viewport.zoom = target
        viewport.centerX = effectiveCenter.x
        viewport.centerY = effectiveCenter.y
        viewport.clampTo(fitScale * target, viewportSize, sheet)
    }

    fun resetView() {
        viewport.zoom = MIN_ZOOM
        viewport.centerX = sheet.width / 2f
        viewport.centerY = sheet.height / 2f
    }

    // 选房定位只由 sheet.id 和 focusRevision 驱动，并保留房间图面中心；旋转、键盘等
    // 尺寸变化不在这里改写保存的图面锚点，只影响绘制时换算出的有效中心。
    LaunchedEffect(sheet.id, focusRevision) {
        val room = highlight?.room ?: return@LaunchedEffect
        if (viewport.handledFocusRevision == focusRevision) return@LaunchedEffect
        viewport.zoom = FOCUS_ZOOM
        viewport.centerX = room.bounds.centerX
        viewport.centerY = room.bounds.centerY
        viewport.handledFocusRevision = focusRevision
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // 缩放后的整张图会超出画布布局范围，必须裁剪，避免盖住上方搜索和楼层切换。
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .pointerInput(sheet.id, viewportSize, fitScale) {
                    if (viewportSize.width <= 0 || viewportSize.height <= 0) return@pointerInput
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val oldZoom = viewport.zoom
                        val newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        // 已到缩放边界且没有平移时不动保存的锚点。
                        if (newZoom == oldZoom && panChange == Offset.Zero) {
                            return@detectTransformGestures
                        }
                        val oldTotal = fitScale * oldZoom
                        val newTotal = fitScale * newZoom
                        val viewCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                        // 从当下夹紧后的有效中心开始，保持手势中心下的图面点跟随手指。
                        val baseCenter = viewport.effectiveCenter(
                            totalScale = oldTotal,
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                            sheet = sheet,
                        )
                        val newCenter = if (newTotal != oldTotal) {
                            baseCenter +
                                (centroid - viewCenter) / oldTotal -
                                (centroid + panChange - viewCenter) / newTotal
                        } else {
                            baseCenter - panChange / newTotal
                        }
                        viewport.zoom = newZoom
                        viewport.centerX = newCenter.x
                        viewport.centerY = newCenter.y
                        viewport.clampTo(newTotal, viewportSize, sheet)
                    }
                }
                .semantics {
                    val figureLabels = renderedSheets
                        .map { it.level }
                        .distinct()
                        .joinToString(" / ") { "图注「$it」" }
                    contentDescription = if (sharedLinework == null) {
                        "${building.label}${sheet.displayLevel}平面图，来源第${sheet.sourcePage}页，$figureLabels"
                    } else {
                        "${renderedSheets.joinToString(" / ") { "${it.building.label}${it.buildingLevel ?: it.displayLevel}" }}联图，" +
                            "来源第${sharedLinework.sourcePage}页，$figureLabels"
                    }
                },
        ) {
            drawRect(color = canvasBackground)
            val totalScale = fitScale * viewport.zoom
            // 保存的中心不因尺寸变化改写；绘制时按当前画布尺寸取有效中心，避免画出空隙。
            val effectiveCenter = viewport.effectiveCenter(
                totalScale = totalScale,
                viewportWidth = size.width,
                viewportHeight = size.height,
                sheet = sheet,
            )
            val origin = Offset(
                x = size.width / 2f - effectiveCenter.x * totalScale,
                y = size.height / 2f - effectiveCenter.y * totalScale,
            )
            val hairline = 1.dp.toPx() / totalScale
            val outlineWidth = 2.dp.toPx() / totalScale

            withTransform({
                translate(origin.x, origin.y)
                scale(totalScale, totalScale, pivot = Offset.Zero)
            }) {
                if (sharedPath == null) {
                    sheet.areas.forEach { area ->
                        val path = area.points.asPath()
                        drawPath(
                            path = path,
                            color = when (area.kind) {
                                FloorPlanAreaKind.FLOOR -> floorFill
                                FloorPlanAreaKind.STAIRS -> stairsFill
                                FloorPlanAreaKind.ELEVATOR -> elevatorFill
                            },
                        )
                        drawPath(
                            path = path,
                            color = floorOutline,
                            style = Stroke(width = hairline),
                        )
                    }
                    sheet.rooms.forEach { room ->
                        val path = room.points.asPath()
                        drawPath(path = path, color = roomFill)
                        drawPath(path = path, color = roomOutline, style = Stroke(width = hairline))
                    }
                    highlight?.let { room ->
                        val path = room.room.points.asPath()
                        drawPath(path = path, color = highlightFill)
                        drawPath(
                            path = path,
                            color = highlightOutline,
                            style = Stroke(width = outlineWidth),
                        )
                    }
                } else {
                    renderedSheets.forEach { renderSheet ->
                        renderSheet.areas.forEach { area ->
                            if (area.kind == FloorPlanAreaKind.FLOOR) {
                                drawPath(area.points.asPath(), floorFill)
                            }
                        }
                    }
                    renderedSheets.forEach { renderSheet ->
                        renderSheet.areas.forEach { area ->
                            val fill = when (area.kind) {
                                FloorPlanAreaKind.FLOOR -> null
                                FloorPlanAreaKind.STAIRS -> stairsFill
                                FloorPlanAreaKind.ELEVATOR -> elevatorFill
                            }
                            if (fill != null) drawPath(area.points.asPath(), fill)
                        }
                    }
                    renderedSheets.forEach { renderSheet ->
                        renderSheet.rooms.forEachIndexed { index, room ->
                            val selectedRoom = highlight?.let {
                                it.sheet.id == renderSheet.id && it.roomIndex == index
                            } == true
                            drawPath(room.points.asPath(), if (selectedRoom) highlightFill else roomFill)
                        }
                    }
                    drawPath(sharedPath, sharedLineColor)
                }
            }

            clipRect(
                left = origin.x,
                top = origin.y,
                right = origin.x + sheet.width * totalScale,
                bottom = origin.y + sheet.height * totalScale,
            ) {
                renderedSheets.forEach { renderSheet ->
                    renderSheet.areas.forEach { area ->
                        area.kind.markerLabel?.let { marker ->
                            drawMapLabel(
                                textMeasurer = textMeasurer,
                                text = marker,
                                bounds = area.bounds,
                                origin = origin,
                                scale = totalScale,
                                style = TextStyle(
                                    color = publicLabelColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                minWidth = minLabelWidth,
                                minHeight = minLabelHeight,
                                padding = labelPadding,
                            )
                        }
                    }
                    renderSheet.rooms.forEachIndexed { index, room ->
                        val selectedRoom = highlight?.let {
                            it.sheet.id == renderSheet.id && it.roomIndex == index
                        } == true
                        drawMapLabel(
                            textMeasurer = textMeasurer,
                            text = room.label,
                            bounds = room.bounds,
                            origin = origin,
                            scale = totalScale,
                            style = TextStyle(
                                color = if (selectedRoom) highlightLabelColor else roomLabelColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            minWidth = minLabelWidth,
                            minHeight = minLabelHeight,
                            padding = labelPadding,
                        )
                    }
                }
                if (sharedLinework == null) {
                    highlight?.let { room ->
                        drawHighlightLabel(
                            textMeasurer = textMeasurer,
                            text = room.room.label,
                            bounds = room.room.bounds,
                            origin = origin,
                            scale = totalScale,
                            style = TextStyle(
                                color = highlightLabelColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            background = highlightFill,
                        )
                    }
                }
            }
        }

        GlassCard(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .fillMaxWidth(if (sharedLinework == null) 0.64f else 0.76f),
        ) {
            val title = if (sharedLinework == null) {
                "${building.label} · ${sheet.displayLevel}"
            } else {
                renderedSheets.joinToString(" / ") {
                    "${it.building.label}${it.buildingLevel ?: it.displayLevel}"
                }
            }
            val sourceDetail = if (sharedLinework == null) {
                "来源第 ${sheet.sourcePage} 页 · 图注「${sheet.level}」"
            } else {
                "来源第 ${sharedLinework.sourcePage} 页 · " + renderedSheets
                    .map { it.level }
                    .distinct()
                    .joinToString(" / ") { "图注「$it」" }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = sourceDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassIconButton(
                onClick = { zoomBy(1.6f) },
                enabled = viewport.zoom < MAX_ZOOM - 0.01f,
            ) {
                Icon(Icons.Default.Add, contentDescription = "放大")
            }
            GlassIconButton(
                onClick = { zoomBy(1f / 1.6f) },
                enabled = viewport.zoom > MIN_ZOOM + 0.01f,
            ) {
                Icon(Icons.Default.Remove, contentDescription = "缩小")
            }
            GlassIconButton(onClick = ::resetView) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "复位视图")
            }
        }

        highlight?.let { room ->
            GlassCard(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .fillMaxWidth(0.84f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = room.room.label,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = buildString {
                            append(room.building.label)
                            append(" · ")
                            append(room.sheet.displayLevel)
                            if (room.sheet.buildingLevel != null) {
                                append(" · 图注「${room.sheet.level}」")
                            }
                            append(" · 来源第 ${room.sheet.sourcePage} 页")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun FloorPlanViewportState.effectiveCenter(
    totalScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    sheet: FloorPlanSheet,
): Offset = clampViewportCenter(
    center = center,
    totalScale = totalScale,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    imageWidth = sheet.width.toFloat(),
    imageHeight = sheet.height.toFloat(),
)

/**
 * 把图面中心约束在当前比例对应的可视范围内：图比视口小时固定居中，只有图大于视口时
 * 才允许平移到边缘，等价于旧实现的平移限制，但可在任意新尺寸下按图面坐标重新计算。
 */
internal fun clampViewportCenter(
    center: Offset,
    totalScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Offset {
    if (totalScale <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) return center
    val halfWidth = viewportWidth / 2f / totalScale
    val halfHeight = viewportHeight / 2f / totalScale
    val x = if (halfWidth * 2f >= imageWidth) {
        imageWidth / 2f
    } else {
        center.x.coerceIn(halfWidth, imageWidth - halfWidth)
    }
    val y = if (halfHeight * 2f >= imageHeight) {
        imageHeight / 2f
    } else {
        center.y.coerceIn(halfHeight, imageHeight - halfHeight)
    }
    return Offset(x, y)
}

private fun FloorPlanViewportState.clampTo(
    totalScale: Float,
    viewportSize: IntSize,
    sheet: FloorPlanSheet,
) {
    val clamped = clampViewportCenter(
        center = center,
        totalScale = totalScale,
        viewportWidth = viewportSize.width.toFloat(),
        viewportHeight = viewportSize.height.toFloat(),
        imageWidth = sheet.width.toFloat(),
        imageHeight = sheet.height.toFloat(),
    )
    centerX = clamped.x
    centerY = clamped.y
}

private fun FloorPlanSharedLinework.asPath(): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    contours.forEach { points ->
        if (points.isEmpty()) return@forEach
        moveTo(points.first().x.toFloat(), points.first().y.toFloat())
        for (index in 1 until points.size) {
            lineTo(points[index].x.toFloat(), points[index].y.toFloat())
        }
        close()
    }
}

private fun List<FloorPlanPoint>.asPath(): Path = Path().apply {
    if (isEmpty()) return@apply
    moveTo(first().x.toFloat(), first().y.toFloat())
    for (index in 1 until size) {
        lineTo(this@asPath[index].x.toFloat(), this@asPath[index].y.toFloat())
    }
    close()
}

private fun FloorPlanBounds.toScreenRect(origin: Offset, scale: Float): Rect = Rect(
    left = origin.x + left * scale,
    top = origin.y + top * scale,
    right = origin.x + right * scale,
    bottom = origin.y + bottom * scale,
)

private fun DrawScope.drawMapLabel(
    textMeasurer: TextMeasurer,
    text: String,
    bounds: FloorPlanBounds,
    origin: Offset,
    scale: Float,
    style: TextStyle,
    minWidth: Float,
    minHeight: Float,
    padding: Float,
) {
    val screen = bounds.toScreenRect(origin, scale)
    if (screen.width < minWidth || screen.height < minHeight) return
    val maxWidth = (screen.width - padding * 2f).toInt().coerceAtLeast(1)
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = screen.center.x - layout.size.width / 2f,
            y = screen.center.y - layout.size.height / 2f,
        ),
    )
}

private fun DrawScope.drawHighlightLabel(
    textMeasurer: TextMeasurer,
    text: String,
    bounds: FloorPlanBounds,
    origin: Offset,
    scale: Float,
    style: TextStyle,
    background: Color,
) {
    val screen = bounds.toScreenRect(origin, scale)
    val horizontalPadding = 4.dp.toPx()
    val verticalPadding = 2.dp.toPx()
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = 220.dp.toPx().toInt()),
    )
    val topLeft = Offset(
        x = screen.center.x - layout.size.width / 2f,
        y = screen.center.y - layout.size.height / 2f,
    )
    drawRoundRect(
        color = background,
        topLeft = Offset(topLeft.x - horizontalPadding, topLeft.y - verticalPadding),
        size = Size(
            width = layout.size.width + horizontalPadding * 2f,
            height = layout.size.height + verticalPadding * 2f,
        ),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(textLayoutResult = layout, topLeft = topLeft)
}
