package com.upcpocket.app.floorplan

/**
 * 卓越教学楼平面图数据。
 *
 * 所有坐标都在单张图的像素坐标内，楼区来自资产文件名，页序就是资产数组顺序。
 * 这里不推断图中未印出的房号或楼层。
 */
internal enum class FloorPlanBuilding(
    val id: String,
    val label: String,
    val assetName: String,
) {
    A("a", "A 栋", "a.json"),
    B("b", "B 栋", "b.json"),
    ;

    companion object {
        fun fromId(id: String): FloorPlanBuilding? = entries.firstOrNull { it.id == id }
    }
}

internal enum class FloorPlanAreaKind(val markerLabel: String?) {
    FLOOR(null),
    STAIRS("楼梯"),
    ELEVATOR("电梯"),
    ;

    companion object {
        fun fromJson(value: String): FloorPlanAreaKind? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

internal data class FloorPlanPoint(val x: Int, val y: Int)

internal data class FloorPlanBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        fun of(points: List<FloorPlanPoint>): FloorPlanBounds = FloorPlanBounds(
            left = points.minOf { it.x },
            top = points.minOf { it.y },
            right = points.maxOf { it.x },
            bottom = points.maxOf { it.y },
        )
    }
}

internal data class FloorPlanArea(
    val kind: FloorPlanAreaKind,
    val points: List<FloorPlanPoint>,
) {
    val bounds: FloorPlanBounds = FloorPlanBounds.of(points)
}

internal data class FloorPlanRoom(
    val label: String,
    val points: List<FloorPlanPoint>,
) {
    val bounds: FloorPlanBounds = FloorPlanBounds.of(points)
}

internal data class FloorPlanSheet(
    val id: String,
    val building: FloorPlanBuilding,
    /** 图注原文，例如「二层」「地下二层」；不由房号推断。 */
    val level: String,
    val sourcePage: Int,
    val width: Int,
    val height: Int,
    val areas: List<FloorPlanArea>,
    val rooms: List<FloorPlanRoom>,
    /** 有来源依据的实际楼栋楼层；空值表示只能展示原图图注。 */
    val buildingLevel: String? = null,
) {
    val displayLevel: String get() = buildingLevel ?: "图注$level"
}

/** A 三层与 B 五层共享的教务处第 5 页线稿。 */
internal data class FloorPlanSharedLinework(
    val sourcePage: Int,
    val sheetIds: List<String>,
    val width: Int,
    val height: Int,
    val contours: List<List<FloorPlanPoint>>,
)

/** 一处图上房间。房间身份是「图层 + 房间下标」，label 可能重复，不能作为主键。 */
internal data class FloorPlanRoomRef(
    val sheet: FloorPlanSheet,
    val roomIndex: Int,
) {
    init {
        require(roomIndex in sheet.rooms.indices) { "房间下标越界" }
    }

    val building: FloorPlanBuilding get() = sheet.building
    val room: FloorPlanRoom get() = sheet.rooms[roomIndex]
    val key: String get() = "${sheet.id}#$roomIndex"
}

internal sealed interface FloorPlanSearchResult {
    data object Empty : FloorPlanSearchResult
    data class Match(val room: FloorPlanRoomRef) : FloorPlanSearchResult
    data class Ambiguous(val query: String, val matches: List<FloorPlanRoomRef>) : FloorPlanSearchResult
    data class NotFound(val query: String) : FloorPlanSearchResult
}

internal class FloorPlanCatalog(
    sheets: List<FloorPlanSheet>,
    val sharedLinework: FloorPlanSharedLinework? = null,
) {
    val sheets: List<FloorPlanSheet> = sheets.toList()
    val buildings: List<FloorPlanBuilding> = this.sheets.map { it.building }.distinct()
    private val sheetsById: Map<String, FloorPlanSheet> = this.sheets.associateBy { it.id }
    private val roomsByNumber: Map<String, List<FloorPlanRoomRef>> = buildMap<String, MutableList<FloorPlanRoomRef>> {
        this@FloorPlanCatalog.sheets.forEach { sheet ->
            sheet.rooms.forEachIndexed { index, room ->
                FloorPlanSearch.printedRoomNumbers(room.label).forEach { number ->
                    getOrPut(number) { mutableListOf() } += FloorPlanRoomRef(sheet, index)
                }
            }
        }
    }.mapValues { (_, rooms) -> rooms.toList() }

    init {
        require(this.sheets.isNotEmpty()) { "平面图至少需要一个图层" }
        require(sheetsById.size == this.sheets.size) { "平面图图层 ID 重复" }
        require(buildings.isNotEmpty()) { "平面图缺少楼区" }
        sharedLinework?.let { linework ->
            require(linework.sheetIds == PAGE_5_SHEET_IDS) { "第 5 页共用图层无效" }
            val members = linework.sheetIds.map { id ->
                requireNotNull(sheetsById[id]) { "第 5 页缺少图层 $id" }
            }
            require(linework.sourcePage == 5) { "第 5 页来源非法" }
            require(members.all { sheet ->
                sheet.sourcePage == linework.sourcePage &&
                    sheet.width == linework.width && sheet.height == linework.height
            }) { "第 5 页共用图层尺寸或来源不一致" }
        }
    }

    val defaultBuilding: FloorPlanBuilding get() = buildings.first()

    fun sheetsFor(building: FloorPlanBuilding): List<FloorPlanSheet> = sheets.filter { it.building == building }

    fun sheetsForCanvas(sheet: FloorPlanSheet): List<FloorPlanSheet> {
        val shared = sharedLinework?.takeIf { sheet.id in it.sheetIds } ?: return listOf(sheet)
        return shared.sheetIds.mapNotNull(sheetsById::get)
    }

    fun sharedLineworkFor(sheet: FloorPlanSheet): FloorPlanSharedLinework? =
        sharedLinework?.takeIf { sheet.id in it.sheetIds }

    fun viewportKey(sheet: FloorPlanSheet): String = sharedLineworkFor(sheet)?.let { linework ->
        "page-${linework.sourcePage}:${linework.width}x${linework.height}"
    } ?: "sheet-v2:${sheet.id}:${sheet.width}x${sheet.height}"

    fun sheetForBuildingSwitch(current: FloorPlanSheet, target: FloorPlanBuilding): FloorPlanSheet =
        sharedLineworkFor(current)
            ?.sheetIds
            ?.asSequence()
            ?.mapNotNull(sheetsById::get)
            ?.firstOrNull { it.building == target }
            ?: defaultSheet(target)

    fun sheet(buildingId: String, sheetId: String): FloorPlanSheet? {
        val building = FloorPlanBuilding.fromId(buildingId) ?: return null
        return sheetsById[sheetId]?.takeIf { it.building == building }
    }

    fun roomRef(sheetId: String, roomIndex: Int): FloorPlanRoomRef? {
        val sheet = sheetsById[sheetId] ?: return null
        if (roomIndex !in sheet.rooms.indices) return null
        return FloorPlanRoomRef(sheet, roomIndex)
    }

    fun search(raw: String): FloorPlanSearchResult = FloorPlanSearch.search(raw, roomsByNumber)

    fun defaultSheet(building: FloorPlanBuilding): FloorPlanSheet =
        sheetsFor(building).firstOrNull() ?: this.sheets.first()
}

private val PAGE_5_SHEET_IDS = listOf("a-3", "b-3")
