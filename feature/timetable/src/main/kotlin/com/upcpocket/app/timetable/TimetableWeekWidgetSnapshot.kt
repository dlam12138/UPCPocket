package com.upcpocket.app.timetable

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 「本周课表」桌面组件的只读模型。
 *
 * 一周固定为上海时区实际今天所在的周日至周六。每个日期单独判断缓存覆盖情况：
 * 只有七日都被成功缓存覆盖且没有课程时才允许表达“全周无课”；缺日、冲突、学期外
 * 和读取失败必须分别呈现，不能合并成空周。
 */
enum class TimetableWeekWidgetStatus {
    READING,
    OK,
    EMPTY,
    PARTIAL,
    NEEDS_SYNC,
    CONFLICT,
    READ_ERROR,
    NO_ACCOUNT,
    ACCOUNT_LOADING,
    CLEANUP,
    OUTSIDE_TERM,
}

enum class TimetableWeekWidgetDayState {
    COURSES,
    EMPTY,
    UNSYNCED,
    OUTSIDE_TERM,
    CONFLICT,
}

/** 组件只渲染课程名和真实节次；重叠课程与非连续节次在装配层保持独立。 */
data class TimetableWeekWidgetCourse(
    val name: String,
    val startPeriod: Int,
    val endPeriod: Int,
) {
    init {
        require(name.isNotBlank()) { "课程名不能为空" }
        require(startPeriod in 1..14 && endPeriod in startPeriod..14) { "节次超出 1–14" }
    }

    val periodLabel: String
        get() = if (startPeriod == endPeriod) {
            "第${startPeriod}节"
        } else {
            "第${startPeriod}–${endPeriod}节"
        }

    val compactPeriodLabel: String
        get() = if (startPeriod == endPeriod) {
            "${startPeriod}节"
        } else {
            "${startPeriod}–${endPeriod}节"
        }
}

data class TimetableWeekWidgetDay(
    val date: LocalDate,
    val state: TimetableWeekWidgetDayState,
    val courses: List<TimetableWeekWidgetCourse> = emptyList(),
) {
    init {
        require(state == TimetableWeekWidgetDayState.COURSES || courses.isEmpty()) {
            "非课程日不能携带课程"
        }
        require(state != TimetableWeekWidgetDayState.COURSES || courses.isNotEmpty()) {
            "课程日必须有课程"
        }
    }
}

data class TimetableWeekWidgetSnapshot(
    val status: TimetableWeekWidgetStatus,
    val weekStart: LocalDate,
    val today: LocalDate,
    val days: List<TimetableWeekWidgetDay> = emptyList(),
    val fetchedAtEpochMillis: Long? = null,
) {
    init {
        require(weekStart.dayOfWeek == DayOfWeek.SUNDAY) { "周起点必须是周日" }
        require(today in weekStart..weekStart.plusDays(6)) { "今天必须在本周内" }
        require(days.isEmpty() || days.size == 7) { "覆盖周必须恰好包含七天" }
        require(days.withIndex().all { (index, day) -> day.date == weekStart.plusDays(index.toLong()) }) {
            "周内日期必须从周日连续到周六"
        }
    }

    /**
     * 只有服务完成一次在提交锁内复核过的缓存读取才会被赋非零修订号；
     * 宿主据此区分“已读取的真实结果”和“撤下内容后的占位状态”。修订号
     * 不进入 RemoteViews，也不代表任何账号信息。
     */
    internal var revision: Long = 0L
        private set

    val isReadResult: Boolean get() = revision > 0L

    val weekEnd: LocalDate get() = weekStart.plusDays(6)

    internal fun assignRevision(value: Long) {
        revision = value
    }

    companion object {
        fun weekStartOf(date: LocalDate): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    }
}

/**
 * 由宿主按真实 widget options + 系统字体缩放算出；四类尺寸都保证七日始终可辨识。
 *
 * - [maxCoursesPerDay]：每日最多直接展示的课程行数，超出部分由独立的
 *   “+N”视图给出实际省略数，避免 TextView 二次省略把数字吞掉；
 * - [maxCourseChars]：单行“节次 + 课程名”的字符预算，避免省略号吃掉节次；
 * - [compactPeriod]：窄卡使用“1–2节”，宽卡使用“第1–2节”；
 * - [compactDayLabel]：窄卡且压缩排版时用“日 25”这类单字星期，保证日期
 *   不被省略，同时给课程名让出宽度；
 * - [textScale]：组件物理高度不随系统字号增长，超大字号时压低 sp 值把
 *   有效字号封顶，合法最小尺寸下七天仍然全部可见；
 * - [showRange] / [showFooter]：空间不足时先让位给七日课程行。
 */
data class TimetableWeekWidgetLayout(
    val showTitle: Boolean = true,
    val showRange: Boolean = true,
    val showFooter: Boolean = true,
    val compact: Boolean = false,
    val dense: Boolean = false,
    val maxCoursesPerDay: Int = 1,
    val maxCourseChars: Int = 16,
    val compactPeriod: Boolean = false,
    val compactDayLabel: Boolean = false,
    val textScale: Float = 1f,
) {
    companion object {
        fun forSize(widthDp: Int, heightDp: Int, fontScale: Float): TimetableWeekWidgetLayout {
            val width = widthDp.takeIf { it >= 100 } ?: 250
            val height = heightDp.takeIf { it >= 100 } ?: 250
            val compact = width < 230
            val dense = fontScale >= 1.3f || height < 190
            val maxCourses = when {
                height >= 400 && fontScale < 1.15f -> 3
                height >= 300 && fontScale < 1.25f -> 2
                else -> 1
            }
            val maxChars = when {
                width >= 300 -> 20
                width >= 240 -> 16
                width >= 200 -> 12
                else -> 9
            }
            return TimetableWeekWidgetLayout(
                showTitle = height >= if (fontScale >= 1.3f) 200 else 165,
                showRange = !compact && height >= if (fontScale >= 1.3f) 220 else 185,
                showFooter = height >= if (fontScale >= 1.3f) 230 else 200,
                compact = compact,
                dense = dense,
                maxCoursesPerDay = maxCourses,
                maxCourseChars = maxChars,
                compactPeriod = compact,
                compactDayLabel = compact && dense,
                textScale = if (fontScale > MAX_TEXT_SCALE) {
                    MAX_TEXT_SCALE / fontScale
                } else {
                    1f
                },
            )
        }

        /** 与 10.5sp 课程行相乘后，七行仍能放进 180dp 的最小组件高度。 */
        private const val MAX_TEXT_SCALE = 1.4f
    }
}

internal data class TimetableWeekWidgetCourseLines(
    val lines: List<String>,
    val hiddenCount: Int,
    val truncated: Boolean,
)

/**
 * 生成每日课程行。每行以真实节次开头，名称超出预算时只在末尾省略，
 * 不会被“+N”后缀挤掉节次；省略数由独立视图展示，不进入这里。非课程日返回空。
 */
internal fun weekCourseLines(
    courses: List<TimetableWeekWidgetCourse>,
    maxCourses: Int,
    maxChars: Int,
    compactPeriod: Boolean,
): TimetableWeekWidgetCourseLines {
    if (courses.isEmpty()) return TimetableWeekWidgetCourseLines(emptyList(), 0, false)
    val cap = maxCourses.coerceIn(1, MAX_COURSE_LINES)
    val visible = courses.take(cap)
    val hidden = courses.size - visible.size
    var truncated = false
    val lines = visible.map { course ->
        val period = if (compactPeriod) course.compactPeriodLabel else course.periodLabel
        val base = "$period ${course.name}"
        val fitted = fitLine(base, maxChars)
        truncated = truncated || fitted != base
        fitted
    }
    return TimetableWeekWidgetCourseLines(
        lines = lines,
        hiddenCount = hidden,
        truncated = truncated,
    )
}

private fun fitLine(text: String, maxChars: Int): String {
    val budget = maxChars.coerceAtLeast(1)
    if (text.length <= budget) return text
    if (budget == 1) return "…"
    return text.take(budget - 1).trimEnd() + "…"
}

internal const val MAX_COURSE_LINES: Int = 3
