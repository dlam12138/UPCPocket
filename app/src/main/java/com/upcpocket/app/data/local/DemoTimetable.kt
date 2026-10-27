package com.upcpocket.app.data.local

import com.upcpocket.app.domain.Period
import com.upcpocket.app.domain.Session

/**
 * 演示课表：**只在内存里生成，永远不写数据库**。
 *
 * 这是 M1「无需账号即可看到完整课表」那条验收要求的数据来源，也是整个演示模式的核心约束：
 *
 *  1. **隔离**：演示课不落库。数据库里只有用户自己创建的日程（[Session.source] = `custom`）；
 *     演示课每次读取时按当前学期即时生成（[source][Session.source] = `demo`）。
 *     所以关掉演示模式、或卸载重装，用户数据一条都不会被演示数据污染或覆盖。
 *  2. **不伪装**：课名、教室、教师全部是虚构的（`演示楼`、`示例教师`），
 *     界面上另有「演示数据」横幅与角标；桌面组件也会在表头标出「演示」。
 *     不存在任何"看起来像从学校系统读来的"字段。
 *  3. **确定**：内容固定、无随机数，同一教学周每次生成结果完全一致 —— 方便测试与截图对比。
 *
 * 课表里出现的都是 UPC 常见的课程类型（高数、大英、程序设计、大物、体育），
 * 但**名称带「（演示）」后缀**，绝不与真实课表混淆。
 */
object DemoTimetable {

    const val SOURCE = "demo"

    /** 演示模式在界面上的统一说明（今天页、课表页、组件摘要都用这一句） */
    const val BANNER = "演示数据 · 非真实课表"

    const val BANNER_DETAIL =
        "下面是虚构的示例课程，只用来体验课表、今天、专注与桌面组件；" +
            "关闭演示后只显示你自己添加的日程。"

    /**
     * 一条固定演示课（按星期 + 节次重复于每一周）。
     *
     * [colorKey] 用 [com.upcpocket.app.domain.PlanColor] 的名字，让演示课与自定义日程
     * 共用同一套配色渲染（但底色以 `demo` 来源单独降饱和，见课表格子）。
     */
    private data class Slot(
        val name: String,
        val room: String,
        val teacher: String,
        val weekday: Int,
        val startUnit: Int,
        val endUnit: Int,
        val colorKey: String,
    )

    private val SLOTS = listOf(
        Slot("高等数学（演示）", "演示楼 A201", "示例教师", 1, 1, 2, "BLUE"),
        Slot("大学英语（演示）", "演示楼 B305", "示例教师", 1, 3, 4, "GREEN"),
        Slot("程序设计基础（演示）", "演示楼 机房 2", "示例教师", 2, 1, 2, "PURPLE"),
        Slot("大学物理（演示）", "演示楼 C102", "示例教师", 2, 5, 6, "ORANGE"),
        Slot("高等数学（演示）", "演示楼 A201", "示例教师", 3, 1, 2, "BLUE"),
        Slot("体育（演示）", "演示操场", "示例教师", 3, 7, 8, "TEAL"),
        Slot("程序设计基础（演示）", "演示楼 机房 2", "示例教师", 4, 3, 4, "PURPLE"),
        Slot("大学英语（演示）", "演示楼 B305", "示例教师", 4, 5, 6, "GREEN"),
        Slot("大学物理（演示）", "演示楼 C102", "示例教师", 5, 1, 2, "ORANGE"),
    )

    /** 演示课覆盖哪些教学周：跟着学期走，避免"学期 18 周、演示只有 16 周"的空白 */
    fun weeksOf(semesterWeeks: List<Int>): List<Int> =
        semesterWeeks.filter { it > 0 }.ifEmpty { (1..LocalDefaults.DEFAULT_TOTAL_WEEKS).toList() }

    /**
     * 课名 → 稳定的演示课程代码。
     *
     * ⚠️ 必须按**课名**归并，不能按"模板里的第几条"：高数在周一和周三各上一次，
     * 用下标当代码会把它拆成两门课，课程总览里就会出现两条「高等数学（演示）」。
     * 用课名的哈希做代码，同一门课的所有时段自然聚合到一起。
     */
    private fun codeOf(name: String): String = "demo-" + name.hashCode().toUInt().toString(16)

    /**
     * 生成整个演示课表。
     *
     * @param semesterId 当前学期 id —— 演示课的 id 里也带上它，
     *   这样切换到另一个学期时网格不会把两个学期的演示课混在一起
     * @param periods 当前作息；用它的起止时间填充每条课，课表/今天/组件才不用各自再查一遍
     */
    fun sessions(
        semesterId: Int,
        weeks: List<Int>,
        periods: List<Period>,
    ): List<Session> {
        if (semesterId == 0) return emptyList()
        val byUnit = periods.associateBy { it.indexNo }
        return weeks.distinct().sorted().flatMap { week ->
            SLOTS.mapIndexed { index, slot ->
                // 节次越界（用户把作息删到只剩 8 节）时跳过这节课，而不是生成一条
                // 没有时间的记录 —— 后者会在网格里被画成 00:00 的幽灵块
                val start = byUnit[slot.startUnit] ?: return@mapIndexed null
                val end = byUnit[slot.endUnit] ?: start
                Session(
                    // 主键仍带下标：同一门课的多个时段本来就是不同的行
                    id = "demo:$semesterId:$week:$index",
                    semesterId = semesterId,
                    source = SOURCE,
                    courseCode = codeOf(slot.name),
                    courseName = slot.name,
                    weekIndex = week,
                    weekday = slot.weekday,
                    startUnit = slot.startUnit,
                    endUnit = slot.endUnit,
                    // 演示课不绑定真实日期：它按"教学周 + 星期"重复，
                    // 与自定义日程同一口径，调休之类的真实校历概念在这里不存在
                    date = null,
                    startTime = start.startText,
                    endTime = end.endText,
                    teacherName = slot.teacher,
                    roomText = slot.room,
                    campusText = null,
                    color = slot.colorKey,
                    experiment = false,
                )
            }.filterNotNull()
        }
    }

    /** 演示课的课程聚合（课程总览里单独分组显示） */
    fun courses(
        semesterId: Int,
        sessions: List<Session>,
    ): List<com.upcpocket.app.domain.Course> =
        sessions.filter { it.source == SOURCE }
            .groupBy { it.courseCode }
            .map { (code, list) ->
                com.upcpocket.app.domain.Course(
                    key = "$semesterId:$code",
                    semesterId = semesterId,
                    code = code,
                    nameZh = list.first().courseName,
                    credits = 0.0,
                    openDepartment = null,
                    teachers = listOfNotNull(list.first().teacherName).distinct(),
                    sessions = list,
                )
            }
            .sortedBy { it.nameZh }
}
