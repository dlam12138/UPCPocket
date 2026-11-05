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
