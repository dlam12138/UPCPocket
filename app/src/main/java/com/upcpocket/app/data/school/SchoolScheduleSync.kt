package com.upcpocket.app.data.school

import com.upcpocket.app.core.WeekMath
import com.upcpocket.app.core.WeekStartDay
import com.upcpocket.app.domain.Period
import java.security.MessageDigest
import java.time.LocalDate

/**
 * 把逐周读取到的学校课程整理成一次可落库的同步计划。
 *
 * 纯函数：不读写数据库、不发网络请求，便于在 JVM 测试里复现“中途失败/超出作息/
 * 空周/重复同步”等路径。真正的写库见 `ScheduleRepository.saveSchoolTerm`。
 */
object SchoolScheduleSync {

    /**
     * @param weeklyWeeks 下标 0 对应第 1 周；长度必须等于 [SchoolTerm.totalWeeks]，
     *   少一周就说明同步不完整，直接报错，不生成半份课表。
     * @param schoolCurrentWeek 学校页面 params 的当前教学周，仅在所选学期**就是学校当前学期**时传入。
     * @param savedStart 本地已保存的第 1 周周一（用户可能校正过），优先级最高。
     * @param schoolTermStart 学校 params.startday 给出的第 1 周周一（仅当前学期线索）。
     */
    fun build(
        term: SchoolTerm,
        weeklyWeeks: List<List<SchoolClassBlock>>,
        periods: List<Period>,
        today: LocalDate = LocalDate.now(),
        schoolCurrentWeek: Int? = null,
        savedStart: LocalDate? = null,
        schoolTermStart: LocalDate? = null,
    ): SchoolSyncPlan {
        if (weeklyWeeks.size != term.totalWeeks) {
            throw SchoolScheduleFormatException(
                "只读取到 ${weeklyWeeks.size}/${term.totalWeeks} 周，不能生成本学期课表",
            )
        }
        val byUnit = periods.associateBy { it.indexNo }
        val maxUnit = periods.maxOfOrNull { it.indexNo } ?: 0
        val (weekOneStart, dateSource) = when {
            savedStart != null -> savedStart to SchoolDateSource.SAVED
            schoolTermStart != null -> schoolTermStart to SchoolDateSource.SCHOOL_PARAMS
            else -> weekOneStart(today, schoolCurrentWeek, term.totalWeeks)
                ?.let { it to SchoolDateSource.DERIVED_CURRENT_WEEK }
                ?: (null to SchoolDateSource.NONE)
        }
        val sessions = LinkedHashMap<String, SchoolSessionDraft>()
        val courses = LinkedHashMap<String, SchoolCourseDraft>()
        val emptyWeeks = ArrayList<Int>()
        var slotCount = 0

        weeklyWeeks.forEachIndexed { index, blocks ->
            val week = index + 1
            if (blocks.isEmpty()) emptyWeeks += week
            blocks.forEach { block ->
                val missing = (block.startUnit..block.endUnit).filter { it !in byUnit }
                if (missing.isNotEmpty()) {
                    throw SchoolUnitsOutOfRangeException(
                        message = "第 $week 周 星期${block.weekday}《${block.name}》含第 " +
                            "${missing.min()}" +
                            (if (missing.size > 1) "–${missing.max()}" else "") +
                            " 节，超出当前作息（1–$maxUnit 节）。请先扩充节次，再重新同步；不会丢弃该课程。",
                        requiredUnit = missing.max(),
                        currentMaxUnit = maxUnit,
                    )
                }
                val code = courseCode(term, block)
                val start = byUnit.getValue(block.startUnit)
                val end = byUnit.getValue(block.endUnit)
                val id = listOf("school", term.key, week, block.weekday, block.startUnit, block.endUnit, code)
                    .joinToString(":")
                sessions.putIfAbsent(
                    id,
                    SchoolSessionDraft(
                        id = id,
                        courseCode = code,
                        name = block.name,
                        week = week,
                        weekday = block.weekday,
                        startUnit = block.startUnit,
                        endUnit = block.endUnit,
                        date = weekOneStart?.plusDays(
                            ((week - 1) * 7 + (block.weekday - 1)).toLong(),
                        )?.toString(),
                        startTime = start.startText,
                        endTime = end.endText,
                        teacher = block.teacher,
                        room = block.room,
                    ),
                )
                courses.putIfAbsent(
                    code,
                    SchoolCourseDraft(
                        code = code,
                        name = block.name,
                        teachers = block.teacher?.let { listOf(it) }.orEmpty(),
                    ),
                )
                slotCount++
            }
        }

        return SchoolSyncPlan(
            term = term,
            sessions = sessions.values.toList(),
            courses = courses.values.toList(),
            weekOneStart = weekOneStart,
            dateSource = dateSource,
            emptyWeeks = emptyWeeks,
            slotCount = slotCount,
        )
    }

    /**
     * 用户在预览里确认/校正了第 1 周日期后，按新日期重算每节课的日期。
     * 课程、周次、节次都不变，只改 `date` 与日期来源。
     */
    fun rebase(plan: SchoolSyncPlan, start: LocalDate?): SchoolSyncPlan = plan.copy(
        weekOneStart = start,
        dateSource = if (start == null) SchoolDateSource.NONE else SchoolDateSource.USER_CONFIRMED,
        sessions = plan.sessions.map { draft ->
            draft.copy(
                date = start?.plusDays(
                    ((draft.week - 1) * 7 + (draft.weekday - 1)).toLong(),
                )?.toString(),
            )
        },
    )

    /**
     * 第 1 周周一。
     *
     * 学校课表接口没有开学日期，只有“当前是第几周”。用今天反推：
     * `本周周一 - (当前周 - 1) * 7`。当前周不可用时返回 null，
     * 由用户在设置里自己定开学日 —— 绝不假装知道。
     */
    fun weekOneStart(today: LocalDate, schoolCurrentWeek: Int?, totalWeeks: Int): LocalDate? {
        val week = schoolCurrentWeek ?: return null
        if (week !in 1..totalWeeks) return null
        val monday = WeekMath.startOfWeek(today, WeekStartDay.MONDAY)
        return monday.minusWeeks((week - 1).toLong())
    }

    /** 课程身份：优先学校课程号；没有课程号时用「名称 + 教师」，不依赖显示顺序。 */
    fun courseCode(term: SchoolTerm, block: SchoolClassBlock): String {
        val identity = block.courseId?.takeIf { it.isNotBlank() }
            ?.let { "id:$it" }
            ?: "name:${block.name.trim()}|teacher:${block.teacher?.trim().orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${term.key}|$identity".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // 前缀 S 与用户自定义日程（C/P）区分开，删除同步数据时只动这一类。
        return "S" + digest.take(16)
    }
}

data class SchoolSyncPlan(
    val term: SchoolTerm,
    val sessions: List<SchoolSessionDraft>,
    val courses: List<SchoolCourseDraft>,
    val weekOneStart: LocalDate?,
    /** 第 1 周日期来自哪里；界面必须如实标注“推算”与“用户确认”。 */
    val dateSource: SchoolDateSource,
    val emptyWeeks: List<Int>,
    val slotCount: Int,
) {
    val weekCount: Int get() = term.totalWeeks
    val courseCount: Int get() = courses.size
}

enum class SchoolDateSource {
    /** 本地学期里已保存的日期（可能是用户校正过的）。 */
    SAVED,

    /** 学校 params.startday（只观察到当前学期线索）。 */
    SCHOOL_PARAMS,

    /** 由“今天 + 学校当前教学周”反推。 */
    DERIVED_CURRENT_WEEK,

    /** 用户在预览里确认/修改。 */
    USER_CONFIRMED,

    /** 没有可用日期，需要用户确认。 */
    NONE,
}

data class SchoolSessionDraft(
    val id: String,
    val courseCode: String,
    val name: String,
    val week: Int,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val date: String?,
    val startTime: String,
    val endTime: String,
    val teacher: String?,
    val room: String?,
)

data class SchoolCourseDraft(
    val code: String,
    val name: String,
    val teachers: List<String>,
)

/**
 * 学校课程使用了当前作息没有的节次。
 *
 * 界面据此提供“扩充作息”的动作，而不是丢课或整份失败不给路走。
 */
class SchoolUnitsOutOfRangeException(
    message: String,
    val requiredUnit: Int,
    val currentMaxUnit: Int,
) : SchoolScheduleFormatException(message)
