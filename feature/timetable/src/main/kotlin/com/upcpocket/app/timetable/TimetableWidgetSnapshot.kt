package com.upcpocket.app.timetable

import java.time.LocalDate

/** 桌面课表组件只读取今天所需的最小快照；不把整周课程或账号信息带进 RemoteViews。 */
data class TimetableWidgetCourse(
    val name: String,
    val location: String?,
    val timeLabel: String?,
    val startMinute: Int?,
    val endMinute: Int?,
    val periodLabel: String,
    val colorSlot: Int?,
)

enum class TimetableWidgetStatus {
    OK,
    NEEDS_SYNC,
    NO_ACCOUNT,
    ACCOUNT_LOADING,
    CLEANUP,
    OUTSIDE_TERM,
}

data class TimetableWidgetSnapshot(
    val status: TimetableWidgetStatus,
    val date: LocalDate?,
    val courses: List<TimetableWidgetCourse> = emptyList(),
    val fetchedAtEpochMillis: Long? = null,
) {
    val hasReliableData: Boolean
        get() = status == TimetableWidgetStatus.OK || status == TimetableWidgetStatus.OUTSIDE_TERM
}

/**
 * 由宿主按真实 widget options + 系统字体缩放算出，不在 XML 写死高度。
 *
 * - [dense]：短矮网格或大字号，使用更紧的内边距与 16sp 主课；
 * - [showDate]：日期/状态行，空间不足时整行让位给主课；
 * - [showSecondary]：宽度足够（4×2）或高度充裕的 2×2 时展示后续课程；
 * - [showSidePanel]：4×2 宽卡启用右侧双栏后续课程列表（即使在 135~165dp 矮 2 行桌面下也能显示多节课）；
 * - [maxSideCourses]：右侧最多直接展开的后续课程条数（2 或 3 节）。
 * - [showFooter]：更新时间行，空间不足时先让位给主课/次课。
 */
data class TimetableWidgetLayout(
    val showDate: Boolean = true,
    val showFooter: Boolean = true,
    val showSecondary: Boolean = false,
    val showLocation: Boolean = true,
    val dense: Boolean = false,
    val compact: Boolean = false,
    val showSidePanel: Boolean = false,
    val maxSideCourses: Int = 2,
) {
    companion object {
        /**
         * 纯函数，便于单测覆盖 160/172/180/234/360 宽与 1.0/1.5 字体。
         * 先保证主课两行 + 时间，再按剩余高度/宽度逐项恢复地点、日期、页脚与多课列表。
         */
        fun forSize(widthDp: Int, heightDp: Int, fontScale: Float): TimetableWidgetLayout {
            val width = widthDp.takeIf { it >= 100 } ?: 180
            val height = heightDp.takeIf { it >= 100 } ?: 180
            val wide = width >= 245
            val dense = (!wide && height < 150) || (wide && height < 125) || (fontScale >= 1.3f && height < 240)
            val showSidePanel = wide && !dense && height >= 130 && fontScale < 1.3f
            val maxSideCourses = if (height >= 170 && fontScale < 1.15f) 3 else 2
            val showSecondary = showSidePanel || (
                !dense && !wide && height >= 215 && fontScale < 1.2f
                )
            var showFooter = height >= if (fontScale >= 1.3f) 200 else 165
            if (fontScale >= 1.1f && height < 190) showFooter = false
            if (showSecondary && height < 215) showFooter = false
            val showDate = height >= if (fontScale >= 1.3f) 180 else if (wide) 135 else 150
            val showLocation = height >= if (fontScale >= 1.3f) 175 else if (wide) 135 else 155
            return TimetableWidgetLayout(
                showDate = showDate,
                showFooter = showFooter,
                showSecondary = showSecondary,
                showLocation = showLocation,
                dense = dense,
                compact = !wide,
                showSidePanel = showSidePanel,
                maxSideCourses = maxSideCourses,
            )
        }
    }
}
