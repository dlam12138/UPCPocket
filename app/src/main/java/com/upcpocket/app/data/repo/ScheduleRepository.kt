package com.upcpocket.app.data.repo

import androidx.room.withTransaction
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.db.CourseEntity
import com.upcpocket.app.data.db.CourseSessionEntity
import com.upcpocket.app.data.db.DisplayOverrideEntity
import com.upcpocket.app.data.db.PeriodEntity
import com.upcpocket.app.data.db.SemesterEntity
import com.upcpocket.app.data.local.DemoTimetable
import com.upcpocket.app.data.local.LocalDefaults
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.domain.Course
import com.upcpocket.app.domain.Period
import com.upcpocket.app.domain.Plan
import com.upcpocket.app.domain.PlanSpec
import com.upcpocket.app.domain.Semester
import com.upcpocket.app.domain.Session
import com.upcpocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 课表仓库（纯本地）。
 *
 * 与上游 ZZUPocket 的差别：
 *  - 没有 `refreshSemesters` / `refreshCourseTable` / `refreshPeriods`：**不存在任何远端**，
 *    没有任何方法会发网络请求；
 *  - 学期、作息都是本机数据，首次启动种一份默认值（[ensureSeeded]），之后由用户改；
 *  - 课表 = **用户自己的日程**（`source = custom`，落库）**+ 演示课**（`source = demo`，
 *    按演示开关即时生成、永不落库）。两者在读取口合并，写入路径完全不会碰演示数据。
 *
 * 因为全部在内存/Room 里算，切周、翻课程详情、看今天、桌面组件刷新都不联网，也没有 TTL。
 */
class ScheduleRepository(
    private val db: AppDatabase,
    private val uiPrefs: UiPrefsStore,
) {

    // ── 本地初始化 ───────────────────────────────────────────────────────

    /**
     * 首次启动的本地种子：作息表 + 一个当前学期。
     *
     * 幂等 —— 已经有数据就什么都不做，所以每次 App 启动都可以安全调用。
     * 刻意**不**给学期种任何课程：课表要么是演示（内存），要么是用户自己加的。
     */
    suspend fun ensureSeeded(today: LocalDate = LocalDate.now()) {
        if (db.periodDao().all().isEmpty()) {
            val now = System.currentTimeMillis()
            db.periodDao().upsertAll(LocalDefaults.DEFAULT_PERIODS.map { it.toEntity(now) })
        }
        if (db.semesterDao().all().isEmpty()) {
            val start = LocalDefaults.defaultStartDate(today)
            val weeks = (1..LocalDefaults.DEFAULT_TOTAL_WEEKS).toList()
            db.withTransaction {
                db.semesterDao().upsertAll(
                    listOf(
                        SemesterEntity(
                            id = LocalDefaults.FIRST_SEMESTER_ID,
                            code = "local",
                            nameZh = LocalDefaults.defaultSemesterName(today),
                            schoolYear = LocalDefaults.defaultSchoolYear(today),
                            startDate = start.toString(),
                            endDate = start.plusWeeks(LocalDefaults.DEFAULT_TOTAL_WEEKS.toLong())
                                .minusDays(1).toString(),
                            weekStartOnSunday = false,
                            weekIndicesCsv = weeks.joinToString(","),
                            isCurrent = true,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
        }
    }

    /**
     * 更新当前学期的本地设置（开学日 / 总周数 / 名称）。
     *
     * 开学日改了，周次与日期自然跟着变；总周数只影响显示范围，不删任何日程数据。
     */
    suspend fun saveSemesterSettings(
        semesterId: Int,
        startDate: LocalDate?,
        totalWeeks: Int?,
        nameZh: String? = null,
    ) {
        val existing = db.semesterDao().all().firstOrNull { it.id == semesterId } ?: return
        val total = (totalWeeks ?: existing.weekCount()).coerceIn(1, 30)
        val weeks = (1..total).toList()
        val end = startDate?.plusWeeks(total.toLong())?.minusDays(1)
        db.semesterDao().upsertAll(
            listOf(
                existing.copy(
                    nameZh = nameZh?.trim()?.takeIf { it.isNotEmpty() } ?: existing.nameZh,
                    startDate = startDate?.toString() ?: existing.startDate,
                    endDate = end?.toString() ?: existing.endDate,
                    weekIndicesCsv = weeks.joinToString(","),
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /** 覆盖写作息（用户在设置里改起止时间）。不满 10 节也允许，网格按最大节次收缩。 */
    suspend fun savePeriods(periods: List<Period>) {
        if (periods.isEmpty()) return
        val now = System.currentTimeMillis()
        db.periodDao().upsertAll(periods.map { it.toEntity(now) })
    }

    /** 当前是否开着演示模式（读 DataStore；每次读都是最新值） */
    suspend fun demoEnabled(): Boolean = uiPrefs.flow.first().demoMode

    // ── 读取 ─────────────────────────────────────────────────────────────

    /** 番茄钟标签候选：自己补录过的课名 */
    fun observeCourseNames(): Flow<List<String>> = db.courseDao().observeAllNames()

    suspend fun periods(): List<Period> = db.periodDao().all().map { it.toDomain() }

    suspend fun semesters(): List<Semester> = db.semesterDao().all().map { it.toDomain() }

    suspend fun semester(id: Int): Semester? =
        db.semesterDao().all().firstOrNull { it.id == id }?.toDomain()

    suspend fun currentSemester(): Semester? =
        db.semesterDao().current()?.toDomain() ?: db.semesterDao().all().firstOrNull()?.toDomain()

    // ── 课表：合并用户日程与演示 ────────────────────────────────────────

    /**
     * 某学期整学期的课（网格一次性取全，翻周在内存里过滤）。
     *
     * 合并顺序固定：用户自己的日程在前、演示课后 —— 同一格冲突时网格画第一条，
     * 这样用户加的内容永远不会被演示数据盖住。
     */
    suspend fun sessionsOfSemester(semesterId: Int): List<Session> {
        val semester = semester(semesterId)
        val custom = db.courseSessionDao().bySemester(semesterId)
            .map { it.toDomain() }
            .withOverrides(semesterId)
        val demo = if (semester != null && demoEnabled()) {
            DemoTimetable.sessions(
                semesterId = semesterId,
                weeks = semester.weekIndices,
                periods = periods(),
            )
        } else {
            emptyList()
        }
        return custom + demo
    }

    /** 某一教学周的课（课前过滤，网格直接用） */
    suspend fun sessionsOfWeek(semesterId: Int, weekIndex: Int): List<Session> =
        sessionsOfSemester(semesterId).filter { it.weekIndex == weekIndex }

    suspend fun hasCourseData(semesterId: Int): Boolean =
        db.courseSessionDao().countBySemester(semesterId) > 0 || demoEnabled()

    /** 自己补录的课程聚合（课程总览用） */
    suspend fun courses(semesterId: Int): List<Course> {
        val sessions = db.courseSessionDao().bySemester(semesterId)
            .map { it.toDomain() }
            .withOverrides(semesterId)
        val byCode = sessions.groupBy { it.courseCode }
        val stored = db.courseDao().bySemester(semesterId).map { c ->
            Course(
                key = c.key,
                semesterId = c.semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachers = c.teachersCsv.split(",").filter { it.isNotBlank() },
                sessions = byCode[c.code].orEmpty(),
            )
        }
        return stored.withNameOverrides(semesterId)
    }

    /** 演示课的课程聚合（与用户课程分开，总览里单独一组） */
    suspend fun demoCourses(semesterId: Int): List<Course> {
        if (!demoEnabled()) return emptyList()
        val semester = semester(semesterId) ?: return emptyList()
        return DemoTimetable.courses(
            semesterId = semesterId,
            sessions = DemoTimetable.sessions(
                semesterId = semesterId,
                weeks = semester.weekIndices,
                periods = periods(),
            ),
        )
    }


    /**
     * 某一天的课（今天页与桌面组件用）。
     *
     * 演示课与自定义日程都没有真实日期（都按"教学周 + 星期"重复），所以这里统一按
     * **星期 + 当前教学周**匹配；学期外（假期）返回空表。
     */
    suspend fun sessionsOn(
        semesterId: Int,
        date: LocalDate,
        currentWeek: Int? = null,
    ): List<Session> {
        val week = currentWeek ?: semester(semesterId)?.let { resolveCurrentWeek(it, date) }
            ?: return emptyList()
        val iso = date.toString()
        val weekday = date.dayOfWeek.value
        return sessionsOfSemester(semesterId)
            .filter { s ->
                // 有真实日期的记录优先按日期匹配（本原型暂时没有写入方，但保留这条口径，
                // 将来若支持本地日历导入可以直接复用）；没日期的按周 + 星期匹配
                if (s.date != null) s.date == iso else s.weekIndex == week && s.weekday == weekday
            }
            .distinctBy { it.id }
            .sortedBy { it.startUnit }
    }

    // ── 本地计算 ─────────────────────────────────────────────────────────

    /**
     * 由学期起始日推算某一天属于第几教学周；落在学期外（假期）返回 null。
     *
     * 算法在 [WeekAlignment.indexOf]（纯函数、有单测）：
     * 对齐到周首再算，这样即使 startDate 是周中某天也不会错一周。
     */
    fun resolveCurrentWeek(semester: Semester, today: LocalDate = LocalDate.now()): Int? =
        WeekAlignment.indexOf(
            startDate = semester.startDate,
            weekStartOnSunday = semester.weekStartOnSunday,
            weekIndices = semester.weekIndices,
            date = today,
        )

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /**
     * 一个自定义日程 = 若干周的同一条记录（每周一行）。
     * 这样网格、今天、翻周全都不用改，而且演示数据那条链路完全碰不到它。
     */
    suspend fun customPlans(semesterId: Int): List<Plan> =
        db.courseSessionDao().bySemesterAndSource(semesterId, SOURCE_CUSTOM)
            .groupBy { it.courseKey }
            .map { (courseKey, rows) ->
                val head = rows.minByOrNull { it.weekIndex } ?: rows.first()
                Plan(
                    // 表格里没有单列 courseCode，日程标识就存在 courseKey 里
                    code = courseKey.removePrefix("$semesterId:"),
                    semesterId = semesterId,
                    name = head.courseName,
                    weekday = head.weekday,
                    startUnit = head.startUnit,
                    endUnit = head.endUnit,
                    weeks = rows.map { it.weekIndex }.distinct().sorted(),
                    roomText = head.roomText,
                    colorKey = head.color,
                    teacherName = head.teacherName,
                )
            }
            .sortedWith(compareBy({ it.weekday }, { it.startUnit }))

    /** 新建或改写一个日程；返回它的 code（新建时是刚生成的） */
    suspend fun savePlan(spec: PlanSpec): String {
        val wanted = com.upcpocket.app.domain.PlanKind.codePrefix(spec.kind)
        // 类型记在 code 前缀上：编辑时如果换了类型（日程↔课程），前缀也要跟着换，
        // 否则读回来的 kind 还是旧的（2026-10-25 真机验证时踩到）
        val code = spec.code
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith(wanted)) it else wanted + it.drop(1) }
            ?: newPlanCode(spec.kind)
        val dao = db.courseSessionDao()
        // 先删旧行：改了周次/时段之后不能留下孤儿记录
        dao.deleteByCourseKey(courseKeyOf(spec.semesterId, code))

        val periods = periods().associateBy { it.indexNo }
        val startTime = periods[spec.startUnit]?.startText
        val endTime = periods[spec.endUnit]?.endText
        val now = System.currentTimeMillis()
        val rows = spec.weeks.distinct().sorted().map { week ->
            CourseSessionEntity(
                id = sessionIdOf(spec.semesterId, code, week, spec.weekday, spec.startUnit),
                courseKey = courseKeyOf(spec.semesterId, code),
                semesterId = spec.semesterId,
                source = SOURCE_CUSTOM,
                courseName = spec.name.trim(),
                weekIndex = week,
                weekday = spec.weekday,
                startUnit = spec.startUnit,
                endUnit = spec.endUnit,
                // 自定义日程不绑定真实日期：它按"教学周 + 星期"重复，
                // 学校调休时不会跟着跑（那本来也不是这门"课"的事）。
                date = null,
                startTime = startTime,
                endTime = endTime,
                teacherName = spec.teacherName?.trim()?.takeIf { it.isNotEmpty() },
                roomText = spec.roomText?.trim()?.takeIf { it.isNotEmpty() },
                campusText = null,
                color = spec.colorKey,
                updatedAt = now,
            )
        }
        db.withTransaction {
            if (rows.isNotEmpty()) dao.upsertAll(rows)
            // 课程总览的数据源：手动补录的「课程」才进 courses 表，普通日程不进
            if (spec.kind == com.upcpocket.app.domain.PlanKind.COURSE) {
                db.courseDao().upsertAll(
                    listOf(
                        CourseEntity(
                            key = courseKeyOf(spec.semesterId, code),
                            semesterId = spec.semesterId,
                            code = code,
                            nameZh = spec.name.trim(),
                            credits = 0.0,
                            openDepartment = null,
                            teachersCsv = spec.teacherName?.trim().orEmpty(),
                            updatedAt = now,
                        ),
                    ),
                )
            } else {
                // 普通日程不进 courses 表；如果这个 code 曾经是「课程」后改成日程，把旧行清掉
                db.courseDao().deleteOne(spec.semesterId, code)
            }
        }
        return code
    }

    suspend fun deletePlan(semesterId: Int, code: String) = db.withTransaction {
        db.courseSessionDao().deleteByCourseKey(courseKeyOf(semesterId, code))
        db.courseDao().deleteOne(semesterId, code)
        db.displayOverrideDao().deleteByCourse(semesterId, code)
    }

    /**
     * 这个日程和哪些**自己添加的**内容撞了（同一天、周次有交集、节次有重叠）。
     *
     * 刻意不把演示课算进来：演示课是虚构的，拿它提示"撞课"只会让人以为要改真实安排。
     * 只提醒不阻止 —— 有些"撞课"其实是自己想要的重叠安排。
     */
    suspend fun planConflicts(semesterId: Int, spec: PlanSpec): List<Session> {
        val weeks = spec.weeks.toSet()
        if (weeks.isEmpty()) return emptyList()
        return db.courseSessionDao().bySemesterAndSource(semesterId, SOURCE_CUSTOM)
            .map { it.toDomain() }
            .filter { it.courseCode != spec.code }
            .filter {
                it.weekday == spec.weekday &&
                    it.weekIndex in weeks &&
                    it.startUnit <= spec.endUnit &&
                    spec.startUnit <= it.endUnit
            }
            .distinctBy { it.courseName to it.startUnit }
            .sortedBy { it.startUnit }
    }

    // ── 显示覆盖 ────────────────────────────────────────────────────────

    /** 某学期全部显示覆盖（详情页编辑用） */
    suspend fun displayOverrides(semesterId: Int): List<DisplayOverrideEntity> =
        db.displayOverrideDao().bySemester(semesterId)

    /**
     * 保存/清除课程名覆盖。[value] 空白 = 清除。
     * @return 落库后的值（null = 已无覆盖）
     */
    suspend fun saveDisplayNameOverride(semesterId: Int, courseCode: String, value: String?): String? {
        val id = nameOverrideId(semesterId, courseCode)
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            db.displayOverrideDao().deleteById(id)
        } else {
            db.displayOverrideDao().upsert(
                DisplayOverrideEntity(
                    id = id, semesterId = semesterId, courseCode = courseCode,
                    kind = KIND_NAME, weekday = null, startUnit = null,
                    value = v, updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return v.ifEmpty { null }
    }

    /**
     * 保存/清除某时段的教室覆盖（同一门课不同时段可不同教室）。空白 = 清除。
     *
     * [weeks] 为 null = 该时段**全部周**（保存前清场）；非 null = 只作用这些教学周。
     */
    suspend fun saveDisplayRoomOverride(
        semesterId: Int,
        courseCode: String,
        weekday: Int,
        startUnit: Int,
        weeks: List<Int>?,
        value: String?,
    ): String? {
        val v = value?.trim().orEmpty()
        if (weeks == null) {
            db.displayOverrideDao().deleteRoomBySlot(semesterId, courseCode, weekday, startUnit)
        } else {
            db.displayOverrideDao().deleteById(
                roomOverrideId(semesterId, courseCode, weekday, startUnit, null),
            )
        }
        if (v.isNotEmpty()) {
            db.displayOverrideDao().upsert(
                DisplayOverrideEntity(
                    id = roomOverrideId(semesterId, courseCode, weekday, startUnit, weeks),
                    semesterId = semesterId, courseCode = courseCode,
                    kind = KIND_ROOM, weekday = weekday, startUnit = startUnit,
                    weeks = weeks?.joinToString(","),
                    value = v, updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return v.ifEmpty { null }
    }

    /** 清掉一门课的全部覆盖（恢复默认） */
    suspend fun clearDisplayOverrides(semesterId: Int, courseCode: String) =
        db.displayOverrideDao().deleteByCourse(semesterId, courseCode)


    private suspend fun overridesOrNull(semesterId: Int): List<DisplayOverrideEntity>? =
        runCatching { db.displayOverrideDao().bySemester(semesterId) }.getOrNull()

    private suspend fun List<Session>.withOverrides(semesterId: Int): List<Session> {
        val ovs = overridesOrNull(semesterId) ?: return this
        if (ovs.isEmpty()) return this
        return applyDisplayOverrides(this, ovs)
    }

    /** 课程名覆盖作用于 [Course.nameZh]（详情页/总览的标题） */
    private suspend fun List<Course>.withNameOverrides(semesterId: Int): List<Course> {
        val ovs = overridesOrNull(semesterId)?.filter { it.kind == KIND_NAME } ?: return this
        if (ovs.isEmpty()) return this
        val by = ovs.associate { it.courseCode to it.value }
        return map { c -> by[c.code]?.let { c.copy(nameZh = it) } ?: c }
    }


    private fun nameOverrideId(semesterId: Int, courseCode: String) =
        "$semesterId|$courseCode|$KIND_NAME"

    private fun roomOverrideId(
        semesterId: Int,
        courseCode: String,
        weekday: Int,
        startUnit: Int,
        weeks: List<Int>?,
    ) = "$semesterId|$courseCode|$KIND_ROOM|$weekday|$startUnit|" + (weeks?.joinToString(",") ?: "all")

    companion object {
        /** 用户自己创建的内容。**唯一会被写入 `course_sessions` 的来源。** */
        const val SOURCE_CUSTOM = "custom"

        /** 显示覆盖的类型：课程名（整门课）/ 教室（单时段） */
        const val KIND_NAME = "name"
        const val KIND_ROOM = "room"

        /**
         * 把显示覆盖应用到 Session 列表（纯函数，单测见 DisplayOverrideTest）。
         *
         * 名称覆盖键 = (semesterId, courseCode)，替换 courseName（网格/今天/组件全生效）；
         * 教室覆盖键 = (semesterId, courseCode, weekday, startUnit) + 周次——
         * `weeks == null` 作用该时段全部周；否则只作用 [DisplayOverrideEntity.weeks]
         * 列的教学周（单双周换教室的课拆成两条互不干扰），**精确周优先于全部周**。
         */
        fun applyDisplayOverrides(
            sessions: List<Session>,
            overrides: List<DisplayOverrideEntity>,
        ): List<Session> {
            if (overrides.isEmpty()) return sessions
            val nameBy = overrides.filter { it.kind == KIND_NAME }
                .associate { (it.semesterId to it.courseCode) to it.value }
            // 键 = (学期, 课程代码, 星期 to 起始节)。写成显式的 Pair/Triple 局部变量，
            // 避免 Triple(a, b, x to y) 这类嵌套调用把 	o 的优先级读错。
            val roomBy = overrides
                .filter { it.kind == KIND_ROOM && it.weekday != null && it.startUnit != null }
                .groupBy { o ->
                    val slot: Pair<Int, Int> = o.weekday!! to o.startUnit!!
                    Triple(o.semesterId, o.courseCode, slot)
                }
            return sessions.map { s ->
                var out = s
                nameBy[s.semesterId to s.courseCode]?.let { out = out.copy(courseName = it) }
                val slot: Pair<Int, Int> = s.weekday to s.startUnit
                roomBy[Triple(s.semesterId, s.courseCode, slot)]
                    ?.let { slotOverrides ->
                        val exact = slotOverrides.firstOrNull {
                            parseWeeks(it.weeks)?.contains(s.weekIndex) == true
                        }
                        val anyWeek = slotOverrides.firstOrNull { it.weeks == null }
                        (exact ?: anyWeek)?.let { out = out.copy(roomText = it.value) }
                    }
                out
            }
        }

        /** "1,3,5" → {1,3,5}；null/空/全非法 → null（= 全部周） */
        internal fun parseWeeks(csv: String?): Set<Int>? =
            csv?.takeIf { it.isNotBlank() }
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }?.toSet()

        fun courseKeyOf(semesterId: Int, courseCode: String) = "$semesterId:$courseCode"

        /**
         * 稳定主键：同一学期 + 同一门课 + 同一周 + 同一天 + 同一节 + **同一日期** = 同一节课。
         *
         * 自定义日程没有日期（按周重复），传 null 即可。
         */
        fun sessionIdOf(
            semesterId: Int,
            courseCode: String,
            weekIndex: Int,
            weekday: Int,
            startUnit: Int,
            date: String? = null,
        ) = "$semesterId:$courseCode:$weekIndex:$weekday:$startUnit:${date.orEmpty()}"

        /** code 前缀记类型：C = 手动补录的课程，P = 普通日程（老数据全是 P） */
        fun newPlanCode(
            kind: com.upcpocket.app.domain.PlanKind = com.upcpocket.app.domain.PlanKind.EVENT,
        ): String = com.upcpocket.app.domain.PlanKind.codePrefix(kind) +
            System.currentTimeMillis().toString(36)
    }
}

// ── 实体 ↔ 领域模型 ─────────────────────────────────────────────────────

private fun SemesterEntity.toDomain() = Semester(
    id = id,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    weekStartOnSunday = weekStartOnSunday,
    weekIndices = weekIndicesCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted(),
    isCurrent = isCurrent,
)

private fun SemesterEntity.weekCount(): Int =
    weekIndicesCsv.split(",").count { it.trim().toIntOrNull() != null }.takeIf { it > 0 }
        ?: LocalDefaults.DEFAULT_TOTAL_WEEKS

private fun CourseSessionEntity.toDomain() = Session(
    id = id,
    semesterId = semesterId,
    source = source,
    courseCode = courseKey.substringAfterLast(':'),
    courseName = courseName,
    weekIndex = weekIndex,
    weekday = weekday,
    startUnit = startUnit,
    endUnit = endUnit,
    date = date,
    startTime = startTime,
    endTime = endTime,
    teacherName = teacherName,
    roomText = roomText,
    campusText = campusText,
    color = color,
    experiment = experiment,
)

private fun Period.toEntity(now: Long) = PeriodEntity(
    indexNo = indexNo,
    nameZh = nameZh,
    startTime = startTime,
    endTime = endTime,
    dayPart = dayPart,
    segmentIndex = segmentIndex,
    updatedAt = now,
)

private fun PeriodEntity.toDomain() = Period(
    indexNo = indexNo,
    nameZh = nameZh,
    startTime = startTime,
    endTime = endTime,
    dayPart = dayPart,
    segmentIndex = segmentIndex,
)
