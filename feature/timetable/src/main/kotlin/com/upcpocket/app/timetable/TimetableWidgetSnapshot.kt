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
 * - [showSecondary]：只有宽度足够的 4×2 才加一行次课；
 * - [showFooter]：更新时间行，空间不足时先让位给主课/次课。
 */
data class TimetableWidgetLayout(
    val showDate: Boolean = true,
    val showFooter: Boolean = true,
    val showSecondary: Boolean = false,
    val showLocation: Boolean = true,
    val dense: Boolean = false,
    val compact: Boolean = false,
) {
    companion object {
        /**
         * 纯函数，便于单测覆盖 160/172/180/234/360 宽与 1.0/1.5 字体。
         * 先保证主课两行 + 时间，再按剩余高度逐项恢复地点、日期、页脚、次课。
         */
        fun forSize(widthDp: Int, heightDp: Int, fontScale: Float): TimetableWidgetLayout {
            val width = widthDp.takeIf { it >= 100 } ?: 180
            val height = heightDp.takeIf { it >= 100 } ?: 180
            val dense = height < 150 || (fontScale >= 1.3f && height < 240)
            val wide = width >= 260
            val showSecondary = !dense && (
                (wide && height >= 200 && fontScale < 1.25f) ||
                    (wide && height >= 170 && fontScale <= 1.05f) ||
                    (!wide && height >= 215 && fontScale < 1.2f)
                )
            var showFooter = height >= if (fontScale >= 1.3f) 200 else 165
            if (fontScale >= 1.1f && height < 190) showFooter = false
            if (showSecondary && height < 215) showFooter = false
            val showDate = height >= if (fontScale >= 1.3f) 180 else 150
            val showLocation = height >= if (fontScale >= 1.3f) 175 else 155
            return TimetableWidgetLayout(
                showDate = showDate,
                showFooter = showFooter,
                showSecondary = showSecondary,
                showLocation = showLocation,
                dense = dense,
                compact = width < 260,
            )
        }
    }
}
