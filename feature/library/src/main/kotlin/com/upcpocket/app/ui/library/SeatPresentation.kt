package com.upcpocket.app.ui.library

import java.util.Locale
import kotlin.math.floor

/** Number of seat tiles per row for the available content width and font scale. */
internal fun seatListColumnCount(
    availableWidthDp: Float,
    fontScale: Float,
    minimumCellWidthDp: Float = 136f,
    horizontalSpacingDp: Float = 8f,
    maximumColumns: Int = 8,
): Int {
    if (!availableWidthDp.isFinite() || availableWidthDp <= 0f ||
        !minimumCellWidthDp.isFinite() || minimumCellWidthDp <= 0f ||
        !horizontalSpacingDp.isFinite() || horizontalSpacingDp < 0f || maximumColumns <= 0
    ) {
        return 1
    }
    val effectiveFontScale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val minimumWidth = minimumCellWidthDp * effectiveFontScale
    if (!minimumWidth.isFinite() || minimumWidth <= 0f) return 1
    val columns = floor(
        (availableWidthDp + horizontalSpacingDp) / (minimumWidth + horizontalSpacingDp),
    ).toInt()
    return columns.coerceIn(1, maximumColumns)
}

/**
 * Sorts room seats for display without changing their source objects or names.
 *
 * ASCII decimal names sort numerically (so leading zeroes remain visible while
 * not affecting numeric order). Other names sort case-insensitively, then by
 * their original spelling. Stable IDs break ties in both groups.
 */
internal fun <T> sortSeatPresentations(
    items: List<T>,
    nameOf: (T) -> String,
    stableIdOf: (T) -> String,
): List<T> {
    if (items.size < 2) return items

    return items
        .map { item ->
            val name = nameOf(item)
            SeatPresentationSortKey(
                item = item,
                name = name,
                stableId = stableIdOf(item),
                numericName = name.asUnsignedDecimalOrNull(),
                foldedName = name.lowercase(Locale.ROOT),
            )
        }
        .sortedWith(Comparator { first, second -> compareSeatPresentationKeys(first, second) })
        .map { it.item }
}

private data class SeatPresentationSortKey<T>(
    val item: T,
    val name: String,
    val stableId: String,
    val numericName: String?,
    val foldedName: String,
)

private fun <T> compareSeatPresentationKeys(
    first: SeatPresentationSortKey<T>,
    second: SeatPresentationSortKey<T>,
): Int {
    val firstIsNumeric = first.numericName != null
    val secondIsNumeric = second.numericName != null
    if (firstIsNumeric != secondIsNumeric) return if (firstIsNumeric) -1 else 1

    val nameOrder = if (firstIsNumeric) {
        compareUnsignedDecimals(first.numericName!!, second.numericName!!)
    } else {
        first.foldedName.compareTo(second.foldedName)
            .takeIf { it != 0 }
            ?: first.name.compareTo(second.name)
    }
    return nameOrder.takeIf { it != 0 } ?: first.stableId.compareTo(second.stableId)
}

private fun String.asUnsignedDecimalOrNull(): String? {
    if (isEmpty() || any { it !in '0'..'9' }) return null
    return dropWhile { it == '0' }.ifEmpty { "0" }
}

private fun compareUnsignedDecimals(first: String, second: String): Int =
    first.length.compareTo(second.length).takeIf { it != 0 } ?: first.compareTo(second)
