package com.upcpocket.app.data.local

import com.upcpocket.app.domain.Period

/**
 * UPC Pocket 的**本地默认值**。
 *
 * 这里的数据全部是本机初始化用的，不来自任何校园系统：
 *
 *  - [DEFAULT_PERIODS]：一份可编辑的作息表。它是"典型国内高校作息"的默认值，
 *    **不是**从学校系统读到的权威数据 —— 用户可以在「课程表设置 → 节次时间」里改成
 *    自己的实际作息。改完之后课表、今天页、桌面组件都按新时间算。
 *  - [defaultSemesterName] / [DEFAULT_TOTAL_WEEKS]：新建学期时的默认名称与周数，
 *    同样只是省一次输入。
 *
 * 原则：**默认值必须能被用户改，而且界面上说清楚它是默认值。**
 * 任何"看起来像学校下发"的假数据都不放在这里（演示数据在
 * [com.upcpocket.app.data.local.DemoTimetable]，并且带明显的演示标识）。
 */
object LocalDefaults {

    /** 默认总周数（用户可改） */
    const val DEFAULT_TOTAL_WEEKS = 18

    /** 学期 id 的起始值：从 1 开始，避免和"未选中"的 0 混淆 */
    const val FIRST_SEMESTER_ID = 1

    /**
     * 默认作息：上午 4 节、下午 4 节、晚上 2 节，共 10 节。
     *
     * 上下课时间只是常见排法，随时可在设置里改；`dayPart` 用于把节次分组显示。
     */
    val DEFAULT_PERIODS: List<Period> = listOf(
        period(1, "第 1 节", 800, 850, "MORNING", 1),
        period(2, "第 2 节", 900, 950, "MORNING", 1),
        period(3, "第 3 节", 1010, 1100, "MORNING", 2),
        period(4, "第 4 节", 1110, 1200, "MORNING", 2),
        period(5, "第 5 节", 1400, 1450, "AFTERNOON", 3),
        period(6, "第 6 节", 1500, 1550, "AFTERNOON", 3),
        period(7, "第 7 节", 1610, 1700, "AFTERNOON", 4),
        period(8, "第 8 节", 1710, 1800, "AFTERNOON", 4),
        period(9, "第 9 节", 1900, 1950, "EVENING", 5),
        period(10, "第 10 节", 2000, 2050, "EVENING", 5),
    )

    /** 节次总数（课表网格与编辑器都用它做上限，避免出现空节次列） */
    val PERIOD_COUNT: Int get() = DEFAULT_PERIODS.size

    /**
     * 新建学期的默认名称：按今天推断学年与学期。
     *
     * 8 月–次年 1 月算第一学期，2 月–7 月算第二学期 —— 这是国内高校的通行划分，
     * 但**只用于生成一个能改的默认名**，界面上不把它当成校历。
     *
     * ⚠️ 1 月要归到**上一个自然年**开始的那个学年：2026-01 属于 2025-2026 第一学期，
     * 而不是 2026-2027。这一条一开始漏了，会在寒假期间给用户一个错一整个学年的名字。
     */
    fun defaultSemesterName(today: java.time.LocalDate): String {
        val (start, term) = academicYearAndTerm(today)
        return "$start-${start + 1} $term"
    }

    /** 学期名对应的学年串（`2025-2026`），用于学期选择页分组 */
    fun defaultSchoolYear(today: java.time.LocalDate): String {
        val start = academicYearAndTerm(today).first
        return "$start-${start + 1}"
    }

    /** 今天属于哪个学年（起始年）与哪个学期 */
    private fun academicYearAndTerm(today: java.time.LocalDate): Pair<Int, String> {
        val year = today.year
        return when {
            // 8–12 月：新学年的第一学期
            today.monthValue >= 8 -> year to "第一学期"
            // 1 月：仍是上一个自然年开始的那个学年的第一学期
            today.monthValue == 1 -> (year - 1) to "第一学期"
            // 2–7 月：同一学年的第二学期
            else -> (year - 1) to "第二学期"
        }
    }

    /**
     * 新建学期时的默认开学日：**今天所在那一周的周一**。
     *
     * 这样"今天"永远落在第 1 周里，用户第一次进来就能看到课表，
     * 而不是要先搞明白"开学日期怎么填"。他当然可以在设置里改。
     */
    fun defaultStartDate(today: java.time.LocalDate): java.time.LocalDate =
        today.minusDays((today.dayOfWeek.value - 1).toLong())

    private fun period(
        index: Int,
        name: String,
        start: Int,
        end: Int,
        dayPart: String,
        segment: Int,
    ) = Period(
        indexNo = index,
        nameZh = name,
        startTime = start,
        endTime = end,
        dayPart = dayPart,
        segmentIndex = segment,
    )
}
