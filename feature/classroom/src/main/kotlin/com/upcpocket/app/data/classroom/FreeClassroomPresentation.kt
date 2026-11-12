package com.upcpocket.app.data.classroom

/**
 * 学校公开页面内联的 `jiedate` 定义（2026-10-29 从
 * `https://app.upc.edu.cn/freeclass/wap/default/all` 的页面脚本核对）：
 *
 * 上午 [1,2,3,4,5]、下午 [6,7,8,9]、晚上 [10,11,12,13]。
 *
 * 这是本页唯一的节次分组来源；不要套用通用 4/4/5 或校历时间。
 */
enum class ClassroomPeriodGroup(
    val label: String,
    val periods: Set<Int>,
) {
    MORNING("上午", setOf(1, 2, 3, 4, 5)),
    AFTERNOON("下午", setOf(6, 7, 8, 9)),
    EVENING("晚上", setOf(10, 11, 12, 13)),
    ;

    /** 例如“1–5”。 */
    val rangeLabel: String get() = "${periods.min()}–${periods.max()}"
}

/**
 * 节次选择模式。
 *
 * 预设模式使用上午/下午/晚上快捷组，条件变化后自动查询；自定义模式保留自选节次，
 * 只有用户点“查询”才发请求。
 */
enum class ClassroomQueryMode { PRESET, CUSTOM }

/** 古镇口卓越教学楼的本地展示分类，不作为学校查询参数。 */
enum class ZhuoyueWingFilter(val label: String, private val wing: String?) {
    ALL("全部", null),
    A("卓越 A", "A"),
    B("卓越 B", "B"),
    ;

    private val roomNamePattern = wing?.let { Regex("^卓越\\s*$it\\s*\\d") }

    fun matches(room: FreeClassroomRoom): Boolean = this == ALL ||
        (room.building == "卓越教学楼" && roomNamePattern!!.containsMatchIn(room.name.trim()))
}

/** 未识别的名称保留在“全部”；不从不透明教室编码推断分区。 */
fun FreeClassroomResult.filterZhuoyueWing(filter: ZhuoyueWingFilter): FreeClassroomResult =
    if (query.campus != FreeClassroomCampus.GUZHENKOU || filter == ZhuoyueWingFilter.ALL) {
        this
    } else {
        copy(rooms = rooms.filter(filter::matches))
    }

enum class GroupSelectionState { NONE, PARTIAL, ALL }

fun groupSelectionState(selected: Set<Int>, group: ClassroomPeriodGroup): GroupSelectionState {
    val count = group.periods.count { it in selected }
    return when (count) {
        0 -> GroupSelectionState.NONE
        group.periods.size -> GroupSelectionState.ALL
        else -> GroupSelectionState.PARTIAL
    }
}

/**
 * 组合快捷选择的一致规则：整组已全选时点击移除整组；否则补齐整组。
 * 部分选中点击后变成整组全选，避免“部分组再点一下却取消”的歧义。
 */
fun toggleGroup(selected: Set<Int>, group: ClassroomPeriodGroup): Set<Int> =
    if (groupSelectionState(selected, group) == GroupSelectionState.ALL) {
        selected - group.periods
    } else {
        selected + group.periods
    }

/** 将节次压成连续区间，例如 {1,2,3,5,10} → “1–3、5、10”。 */
fun formatPeriods(periods: Set<Int>): String {
    if (periods.isEmpty()) return ""
    val sorted = periods.sorted()
    val ranges = mutableListOf<IntRange>()
    var start = sorted.first()
    var previous = start
    for (value in sorted.drop(1)) {
        if (value == previous + 1) {
            previous = value
        } else {
            ranges += start..previous
            start = value
            previous = value
        }
    }
    ranges += start..previous
    return ranges.joinToString("、") { range ->
        if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
    }
}

/** 按官方返回的教学楼分组；组内保持学校顺序，附带该楼全部/空闲计数。 */
data class BuildingRoomGroup(
    val building: String,
    val rooms: List<FreeClassroomRoom>,
    val freeCount: Int,
    val totalCount: Int,
)

/**
 * 展示用分组。默认展示全部教室（空闲+占用）；“仅看空闲”只过滤格子，
 * 组头的空闲/总数仍按学校返回的整组计算。
 *
 * “空闲”只来自 `js` 的明确命中；未命中一律按占用展示，避免把未知当空闲。
 */
fun FreeClassroomResult.displayGroups(onlyAvailable: Boolean): List<BuildingRoomGroup> =
    rooms.groupBy { it.building }
        .map { (building, groupRooms) ->
            val shown = if (onlyAvailable) groupRooms.filter { it.available } else groupRooms
            BuildingRoomGroup(
                building = building,
                rooms = shown,
                freeCount = groupRooms.count { it.available },
                totalCount = groupRooms.size,
            )
        }
        .filter { it.rooms.isNotEmpty() }

/** 查询条件变了就不能把旧结果当成当前结果。 */
fun FreeClassroomResult.matches(query: FreeClassroomQuery?): Boolean = query != null && this.query == query
