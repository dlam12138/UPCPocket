package com.upcpocket.app.ui.library

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A point in the source layout coordinate system or the viewport pixel space. */
internal data class SeatMapPoint(val x: Float, val y: Float)

/** Viewport dimensions in pixels. Source layout coordinates are scaled into these dimensions. */
internal data class SeatMapSize(val width: Float, val height: Float)

/** A source-layout rectangle. Right and bottom edges are exclusive for hit testing. */
internal data class SeatMapRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(point: SeatMapPoint): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom

    /** Hit-tests an axis-aligned item after undoing its rotation around its center. */
    fun containsRotated(point: SeatMapPoint, rotationDegrees: Float): Boolean {
        if (!rotationDegrees.isFinite()) return false
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val dx = point.x - (left + right) / 2f
        val dy = point.y - (top + bottom) / 2f
        val localX = dx * cos(radians).toFloat() + dy * sin(radians).toFloat()
        val localY = -dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
        return abs(localX) <= width / 2f && abs(localY) <= height / 2f
    }

    internal fun isUsable(): Boolean {
        val width = right - left
        val height = bottom - top
        return left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width.isFinite() && height.isFinite() && width > 0f && height > 0f
    }
}

/** Axis-aligned bounds of this rectangle after rotation around its center. */
internal fun SeatMapRect.rotatedBounds(rotationDegrees: Float): SeatMapRect? {
    if (!isUsable() || !rotationDegrees.isFinite()) return null
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosine = abs(cos(radians)).toFloat()
    val sine = abs(sin(radians)).toFloat()
    val rotatedWidth = width * cosine + height * sine
    val rotatedHeight = width * sine + height * cosine
    if (!rotatedWidth.isFinite() || !rotatedHeight.isFinite()) return null
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    return SeatMapRect(
        left = centerX - rotatedWidth / 2f,
        top = centerY - rotatedHeight / 2f,
        right = centerX + rotatedWidth / 2f,
        bottom = centerY + rotatedHeight / 2f,
    ).takeIf { it.isUsable() }
}

/** A cell's original, zero-based row and column in a libseat grid. */
internal data class SeatMapGridPosition(val row: Int, val col: Int)

/**
 * Returns the tight outer bounds of every valid rectangle supplied by a loaded
 * room layout. Callers must pass the complete layout, including hidden,
 * disabled, and non-seat elements; this function has no status-based filtering.
 */
internal fun seatMapBoundsFromRects(elements: Iterable<SeatMapRect>): SeatMapRect? {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY

    for (element in elements) {
        if (!element.isUsable()) continue
        left = min(left, element.left)
        top = min(top, element.top)
        right = max(right, element.right)
        bottom = max(bottom, element.bottom)
    }

    return SeatMapRect(left, top, right, bottom).takeIf { it.isUsable() }
}

/**
 * Crops only empty outer rows and columns around all supplied layout cells.
 * Empty rows or columns inside the returned rectangle remain part of the map.
 */
internal fun <T> seatMapBoundsForGrid(
    rowCount: Int,
    columnCount: Int,
    allLayoutElements: Iterable<T>,
    rowOf: (T) -> Int,
    columnOf: (T) -> Int,
): SeatMapRect? {
    if (rowCount <= 0 || columnCount <= 0) return null

    var minRow = Int.MAX_VALUE
    var minColumn = Int.MAX_VALUE
    var maxRow = Int.MIN_VALUE
    var maxColumn = Int.MIN_VALUE

    for (element in allLayoutElements) {
        val row = rowOf(element)
        val column = columnOf(element)
        if (row !in 0 until rowCount || column !in 0 until columnCount) continue
        minRow = min(minRow, row)
        minColumn = min(minColumn, column)
        maxRow = max(maxRow, row)
        maxColumn = max(maxColumn, column)
    }

    if (minRow == Int.MAX_VALUE) return null
    return SeatMapRect(
        left = minColumn.toFloat(),
        top = minRow.toFloat(),
        right = maxColumn.toFloat() + 1f,
        bottom = maxRow.toFloat() + 1f,
    )
}

/**
 * Uniform map-to-screen transform shared by drawing and hit testing.
 *
 * `scale` converts source layout units to viewport pixels. Positive source Y
 * maps down-screen, matching both the libseat grid and returned Chaoxing map
 * coordinates.
 */
internal class SeatMapViewportTransform private constructor(
    val contentBounds: SeatMapRect,
    val viewportSize: SeatMapSize,
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    private val minimumScale: Float,
    private val maximumScale: Float,
    private val maximumZoomMultiplier: Float,
    private val paddingPx: Float,
) {
    val zoomMultiplier: Float get() = scale / minimumScale

    fun contentToDisplay(point: SeatMapPoint): SeatMapPoint = SeatMapPoint(
        x = point.x * scale + translationX,
        y = point.y * scale + translationY,
    )

    fun displayToContent(point: SeatMapPoint): SeatMapPoint = SeatMapPoint(
        x = (point.x - translationX) / scale,
        y = (point.y - translationY) / scale,
    )

    /** Maps a tap through the inverse of [contentToDisplay] to its original grid cell. */
    fun displayToGridPosition(point: SeatMapPoint): SeatMapGridPosition? {
        val contentPoint = displayToContent(point)
        if (!contentBounds.contains(contentPoint)) return null
        return SeatMapGridPosition(
            row = floor(contentPoint.y).toInt(),
            col = floor(contentPoint.x).toInt(),
        )
    }

    /** Zooms around a screen-space focus, keeping the same source point under that focus. */
    fun zoomAt(focus: SeatMapPoint, factor: Float): SeatMapViewportTransform {
        if (!factor.isFinite() || factor <= 0f || !focus.x.isFinite() || !focus.y.isFinite()) return this
        val nextScale = (scale * factor).coerceIn(minimumScale, maximumScale)
        val focusInContent = displayToContent(focus)
        val translation = clampTranslation(
            x = focus.x - focusInContent.x * nextScale,
            y = focus.y - focusInContent.y * nextScale,
            targetScale = nextScale,
        )
        return SeatMapViewportTransform(
            contentBounds = contentBounds,
            viewportSize = viewportSize,
            scale = nextScale,
            translationX = translation.x,
            translationY = translation.y,
            minimumScale = minimumScale,
            maximumScale = maximumScale,
            maximumZoomMultiplier = maximumZoomMultiplier,
            paddingPx = paddingPx,
        )
    }

    /** Applies a screen-space pan delta. */
    fun panBy(delta: SeatMapPoint): SeatMapViewportTransform {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return this
        val translation = clampTranslation(
            x = translationX + delta.x,
            y = translationY + delta.y,
            targetScale = scale,
        )
        return SeatMapViewportTransform(
            contentBounds = contentBounds,
            viewportSize = viewportSize,
            scale = scale,
            translationX = translation.x,
            translationY = translation.y,
            minimumScale = minimumScale,
            maximumScale = maximumScale,
            maximumZoomMultiplier = maximumZoomMultiplier,
            paddingPx = paddingPx,
        )
    }

    /** Returns to the fitted overview for the same room and viewport. */
    fun reset(): SeatMapViewportTransform? =
        overview(contentBounds, viewportSize, paddingPx, maximumZoomMultiplier)

    private fun clampTranslation(x: Float, y: Float, targetScale: Float): SeatMapPoint =
        SeatMapPoint(
            x = clampAxisTranslation(
                proposed = x,
                viewportLength = viewportSize.width,
                contentStart = contentBounds.left,
                contentLength = contentBounds.width,
                targetScale = targetScale,
            ),
            y = clampAxisTranslation(
                proposed = y,
                viewportLength = viewportSize.height,
                contentStart = contentBounds.top,
                contentLength = contentBounds.height,
                targetScale = targetScale,
            ),
        )

    private fun clampAxisTranslation(
        proposed: Float,
        viewportLength: Float,
        contentStart: Float,
        contentLength: Float,
        targetScale: Float,
    ): Float {
        val availableLength = viewportLength - paddingPx * 2f
        val contentLengthPx = contentLength * targetScale
        if (contentLengthPx <= availableLength) {
            return paddingPx + (availableLength - contentLengthPx) / 2f - contentStart * targetScale
        }

        val contentEnd = contentStart + contentLength
        val minimumTranslation = paddingPx + availableLength - contentEnd * targetScale
        val maximumTranslation = paddingPx - contentStart * targetScale
        return proposed.coerceIn(minimumTranslation, maximumTranslation)
    }

    companion object {
        /** Fits the complete content bounds into the viewport and centers it. */
        fun overview(
            contentBounds: SeatMapRect,
            viewportSize: SeatMapSize,
            paddingPx: Float = 0f,
            maximumZoomMultiplier: Float = 4f,
        ): SeatMapViewportTransform? {
            if (!contentBounds.isUsable() ||
                !viewportSize.width.isFinite() || !viewportSize.height.isFinite() ||
                viewportSize.width <= 0f || viewportSize.height <= 0f ||
                !paddingPx.isFinite() || paddingPx < 0f
            ) {
                return null
            }

            val availableWidth = viewportSize.width - paddingPx * 2f
            val availableHeight = viewportSize.height - paddingPx * 2f
            if (availableWidth <= 0f || availableHeight <= 0f) return null

            val fitScale = min(
                availableWidth / contentBounds.width,
                availableHeight / contentBounds.height,
            )
            if (!fitScale.isFinite() || fitScale <= 0f) return null

            val zoomMultiplier = if (maximumZoomMultiplier.isFinite()) {
                max(1f, maximumZoomMultiplier)
            } else {
                1f
            }
            val maxScale = fitScale * zoomMultiplier
            if (!maxScale.isFinite() || maxScale <= 0f) return null

            val drawnWidth = contentBounds.width * fitScale
            val drawnHeight = contentBounds.height * fitScale
            val x = paddingPx + (availableWidth - drawnWidth) / 2f - contentBounds.left * fitScale
            val y = paddingPx + (availableHeight - drawnHeight) / 2f - contentBounds.top * fitScale
            if (!x.isFinite() || !y.isFinite()) return null

            return SeatMapViewportTransform(
                contentBounds = contentBounds,
                viewportSize = viewportSize,
                scale = fitScale,
                translationX = x,
                translationY = y,
                minimumScale = fitScale,
                maximumScale = maxScale,
                maximumZoomMultiplier = zoomMultiplier,
                paddingPx = paddingPx,
            )
        }
    }
}

/**
 * Room-scoped, saveable viewport pose independent of screen pixels. It preserves
 * the relative zoom and the source point centered in the viewport across resize
 * or rotation.
 */
internal data class SeatMapViewportPose(
    val zoomMultiplier: Float = 1f,
    val centerXFraction: Float = 0.5f,
    val centerYFraction: Float = 0.5f,
) {
    fun toTransform(
        contentBounds: SeatMapRect,
        viewportSize: SeatMapSize,
        paddingPx: Float,
        maximumZoomMultiplier: Float,
    ): SeatMapViewportTransform? {
        val overview = SeatMapViewportTransform.overview(
            contentBounds = contentBounds,
            viewportSize = viewportSize,
            paddingPx = paddingPx,
            maximumZoomMultiplier = maximumZoomMultiplier,
        ) ?: return null
        val focus = SeatMapPoint(viewportSize.width / 2f, viewportSize.height / 2f)
        val zoomed = overview.zoomAt(focus, zoomMultiplier)
        val desiredCenter = SeatMapPoint(
            x = contentBounds.left + contentBounds.width * centerXFraction.coerceIn(0f, 1f),
            y = contentBounds.top + contentBounds.height * centerYFraction.coerceIn(0f, 1f),
        )
        val displayedCenter = zoomed.contentToDisplay(desiredCenter)
        return zoomed.panBy(
            SeatMapPoint(focus.x - displayedCenter.x, focus.y - displayedCenter.y),
        )
    }

    companion object {
        fun from(transform: SeatMapViewportTransform): SeatMapViewportPose {
            val center = transform.displayToContent(
                SeatMapPoint(transform.viewportSize.width / 2f, transform.viewportSize.height / 2f),
            )
            return SeatMapViewportPose(
                zoomMultiplier = transform.zoomMultiplier,
                centerXFraction = ((center.x - transform.contentBounds.left) / transform.contentBounds.width)
                    .coerceIn(0f, 1f),
                centerYFraction = ((center.y - transform.contentBounds.top) / transform.contentBounds.height)
                    .coerceIn(0f, 1f),
            )
        }
    }
}
