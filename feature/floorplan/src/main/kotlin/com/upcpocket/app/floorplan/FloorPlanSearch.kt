package com.upcpocket.app.floorplan

import java.text.Normalizer
import java.util.Locale

/**
 * 房号查找只认图上完整印出的 A/B + 三位数字。
 *
 * 允许全角、大小写和内部空白差异，也允许「卓越 A233」这类前缀；不展开
 * 「A233–A235」中的中间号，也不把 A23 当成 A233 的缩写。
 */
internal object FloorPlanSearch {
    private val ROOM_NUMBER = Regex("(?<![A-Z0-9])[AB][0-9]{3}(?![A-Z0-9])")

    fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .filterNot(Char::isWhitespace)

    /** 返回 label 中所有完整打印的房号；相邻的合并标注会得到多个房号。 */
    fun printedRoomNumbers(label: String): List<String> =
        ROOM_NUMBER.findAll(normalize(label))
            .map { it.value }
            .distinct()
            .toList()

    fun search(
        raw: String,
        roomsByNumber: Map<String, List<FloorPlanRoomRef>>,
    ): FloorPlanSearchResult {
        val query = normalize(raw)
        if (query.isEmpty()) return FloorPlanSearchResult.Empty

        val printedQuery = raw.trim()
        val numbers = printedRoomNumbers(raw).distinct()
        if (numbers.isEmpty()) return FloorPlanSearchResult.NotFound(printedQuery)

        // 查询里出现任一未索引的完整房号时如实报未找到，不忽略它只定位另一半。
        if (numbers.any { it !in roomsByNumber }) {
            return FloorPlanSearchResult.NotFound(printedQuery)
        }

        val matches = numbers
            .flatMap { roomsByNumber.getValue(it) }
            .distinctBy { it.key }
        return when {
            matches.isEmpty() -> FloorPlanSearchResult.NotFound(printedQuery)
            matches.size == 1 -> FloorPlanSearchResult.Match(matches.single())
            else -> FloorPlanSearchResult.Ambiguous(printedQuery, matches)
        }
    }
}
