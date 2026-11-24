package com.upcpocket.app.timetable

import java.time.LocalDate

/**
 * 「今明课表」桌面组件的只读模型。
 *
 * 两个日期都来自同一上海实际日期：今天与次日。每天独立判断当前账号缓存是否覆盖、
 * 是否冲突或位于学期外；只有找到成功缓存且当日没有课程时才是“已同步无课”。
 * 缓存整体不可读时仍保留两个日期，并分别标出“读取失败”，不能合并成状态屏。
 */
enum class TimetableTodayTomorrowWidgetStatus {
    READING,
    OK,
    READ_ERROR,
    NO_ACCOUNT,
    ACCOUNT_LOADING,
    CLEANUP,
}

enum class TimetableTodayTomorrowWidgetDayState {
    COURSES,
    EMPTY,
    UNSYNCED,
    OUTSIDE_TERM,
    CONFLICT,
    READ_ERROR,
}

/** 组件只渲染课程名和真实节次；重叠课程与不连续节次在装配层保持独立与原始顺序。 */
data class TimetableTodayTomorrowWidgetCourse(
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

data class TimetableTodayTomorrowWidgetDay(
    val date: LocalDate,
    val state: TimetableTodayTomorrowWidgetDayState,
    val courses: List<TimetableTodayTomorrowWidgetCourse> = emptyList(),
) {
    init {
        require(state == TimetableTodayTomorrowWidgetDayState.COURSES || courses.isEmpty()) {
            "非课程日不能携带课程"
        }
        require(state != TimetableTodayTomorrowWidgetDayState.COURSES || courses.isNotEmpty()) {
            "课程日必须有课程"
        }
    }
}

data class TimetableTodayTomorrowWidgetSnapshot(
    val status: TimetableTodayTomorrowWidgetStatus,
    val today: LocalDate,
    val tomorrow: LocalDate,
    val days: List<TimetableTodayTomorrowWidgetDay> = emptyList(),
    val fetchedAtEpochMillis: Long? = null,
) {
    init {
        require(tomorrow == today.plusDays(1)) { "次日必须是今天的下一天" }
        require(days.isEmpty() || days.size == 2) { "日期列必须恰好包含今明两天" }
        require(days.withIndex().all { (index, day) -> day.date == today.plusDays(index.toLong()) }) {
            "日期列必须从今天连续到明天"
        }
        require(days.isEmpty() || status == TimetableTodayTomorrowWidgetStatus.OK ||
            status == TimetableTodayTomorrowWidgetStatus.READ_ERROR) {
            "账号或读取中状态不能携带日期列"
        }
        require(status != TimetableTodayTomorrowWidgetStatus.OK || days.size == 2) {
            "有效数据状态必须包含今明两天"
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

    internal fun assignRevision(value: Long) {
        revision = value
    }
}

/**
 * 由宿主按真实 widget options + 系统字体缩放算出；两列始终保留，只有课程行数、
 * 日期格式和行内字符预算随空间收缩。
 *
 * - [maxCoursesPerDay]：每日最多直接展示的课程行数，超出部分由独立的
 *   “+N”视图给出实际省略数；
 * - [maxCourseChars]：单行“节次 + 课程名”的字符预算，避免省略号吃掉节次；
 * - [compactPeriod]：窄卡使用“1–2节”，宽卡使用“第1–2节”；
 * - [compactDate]：窄卡或大字号使用“今天 24 / 明天 12/1”，日期仍然完整；
 * - [textScale]：组件物理高度不随系统字号增长，超大字号时压低 sp 值把
 *   有效字号封顶，两日和各自的隐藏数仍然可见。
 */
data class TimetableTodayTomorrowWidgetLayout(
    val maxCoursesPerDay: Int = 2,
    val maxCourseChars: Int = 9,
    val compactPeriod: Boolean = true,
    val compactDate: Boolean = false,
    val dense: Boolean = false,
    val textScale: Float = 1f,
) {
    companion object {
        fun forSize(widthDp: Int, heightDp: Int, fontScale: Float): TimetableTodayTomorrowWidgetLayout {
            val width = widthDp.takeIf { it >= 100 } ?: 250
            val height = heightDp.takeIf { it >= 100 } ?: 140
            val maxCourses = when {
                height >= 180 && fontScale < 1.15f -> 3
                height >= 120 && fontScale < 1.35f -> 2
                else -> 1
            }
            // 组件物理尺寸不随系统字号增长。窄卡上限更低，给双位数节次和课名
            // 留出实际宽度；宽卡才允许接近 1.15× 的课程字号。
            val maxTextScale = when {
                width < 220 -> 1f
                width < 260 -> 1.1f
                else -> 1.15f
            }
            val effectiveFontScale = minOf(fontScale, maxTextScale)
            // 两列等宽：组件内边距 10+10dp，中间间隔 8dp。
            val columnWidth = (width - 28f) / 2f
            val maxChars = (columnWidth / (10.5f * effectiveFontScale))
                .toInt()
                .coerceIn(5, 18)
            return TimetableTodayTomorrowWidgetLayout(
                maxCoursesPerDay = maxCourses,
                maxCourseChars = maxChars,
                compactPeriod = width < 300,
                compactDate = width < 240 || fontScale >= 1.3f,
                dense = fontScale >= 1.3f || height < 120,
                textScale = if (fontScale > maxTextScale) maxTextScale / fontScale else 1f,
            )
        }
    }
}

internal data class TimetableTodayTomorrowWidgetCourseLines(
    val lines: List<String>,
    val hiddenCount: Int,
    val truncated: Boolean,
)

/**
 * 生成单日课程行。每行以真实节次开头，名称超出预算时只在末尾省略，
 * 不会被“+N”后缀挤掉节次；省略数由独立视图展示。非课程日返回空。
 */
internal fun todayTomorrowCourseLines(
    courses: List<TimetableTodayTomorrowWidgetCourse>,
    maxCourses: Int,
    maxChars: Int,
    compactPeriod: Boolean,
): TimetableTodayTomorrowWidgetCourseLines {
    if (courses.isEmpty()) return TimetableTodayTomorrowWidgetCourseLines(emptyList(), 0, false)
    val cap = maxCourses.coerceIn(1, MAX_TODAY_TOMORROW_COURSE_LINES)
    val visible = courses.take(cap)
    val hidden = courses.size - visible.size
    var truncated = false
    val lines = visible.map { course ->
        val period = if (compactPeriod) course.compactPeriodLabel else course.periodLabel
        val fitted = fitTodayTomorrowLine(period, course.name, maxChars)
        truncated = truncated || fitted.second
        fitted.first
    }
    return TimetableTodayTomorrowWidgetCourseLines(
        lines = lines,
        hiddenCount = hidden,
        truncated = truncated,
    )
}

/**
 * 节次是课程行的定位信息，任何宽度/字号下都完整保留；只有课程名按剩余
 * 预算截断。数字、短横和空格按较窄字宽计，双位数节次不会误吃课名空间。
 * 最窄卡仍至少保留一个课程名字符；若连省略号都放不下才只显示节次。
 */
private fun fitTodayTomorrowLine(
    period: String,
    name: String,
    maxChars: Int,
): Pair<String, Boolean> {
    val budget = maxChars.coerceAtLeast(1).toFloat()
    val nameBudget = budget - glyphUnits(period) - SPACE_UNITS
    if (nameBudget <= 0f) return period to true
    var taken = 0f
    var end = 0
    while (end < name.length) {
        val next = taken + glyphUnits(name[end])
        if (next + ELLIPSIS_UNITS > nameBudget) break
        taken = next
        end += 1
    }
    if (end == name.length) return "$period $name" to false
    if (end == 0) return "$period …" to true
    return "$period ${name.take(end)}…" to true
}

private fun glyphUnits(char: Char): Float =
    if (char.code < 0x2E80) NARROW_GLYPH_UNITS else 1f

private fun glyphUnits(text: String): Float =
    text.fold(0f) { total, char -> total + glyphUnits(char) }

private const val NARROW_GLYPH_UNITS = 0.55f
private const val SPACE_UNITS = 0.55f
private const val ELLIPSIS_UNITS = 0.55f

internal const val MAX_TODAY_TOMORROW_COURSE_LINES: Int = 3

/** 同一份已复核缓存读取同时产出的周版与今明版；未请求的一侧为 null。 */
data class TimetableCalendarWidgetSnapshots(
    val week: TimetableWeekWidgetSnapshot? = null,
    val todayTomorrow: TimetableTodayTomorrowWidgetSnapshot? = null,
)
