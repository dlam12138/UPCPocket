package com.upcpocket.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.local.LocalDefaults
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.school.SchoolClassBlock
import com.upcpocket.app.data.school.SchoolScheduleSync
import com.upcpocket.app.data.school.SchoolSyncPlan
import com.upcpocket.app.data.school.SchoolTerm
import com.upcpocket.app.domain.PlanKind
import com.upcpocket.app.domain.PlanSpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 真实 Room 验证学校课表落库：
 * 整批事务、失败回滚、重复同步幂等、只替换「该学期 + 学校来源」、
 * 保留自定义日程 / 显示覆盖 / 用户学期设置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ScheduleRepositorySchoolSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val localSemesterId = 1
    private val term = SchoolTerm("2026", "1", 2, "2026 秋季学期")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ScheduleRepository(db, UiPrefsStore(context))
        repo.ensureSeeded()
        db.courseDao().deleteBySemester(localSemesterId)
    }

    @After
    fun tearDown() = db.close()

    private fun block(name: String, weekday: Int, start: Int, end: Int) =
        SchoolClassBlock(name, weekday, start, end, "张老师", "南教101", "ID-$name")

    private fun plan(
        weeks: List<List<SchoolClassBlock>>,
        currentWeek: Int? = null,
    ): SchoolSyncPlan = SchoolScheduleSync.build(
        term = term,
        weeklyWeeks = weeks,
        periods = LocalDefaults.DEFAULT_PERIODS,
        today = LocalDate.of(2026, 10, 28),
        schoolCurrentWeek = currentWeek,
    )

    private suspend fun schoolRows(semesterId: Int) =
        db.courseSessionDao().bySemesterAndSource(semesterId, ScheduleRepository.SOURCE_SCHOOL)

    @Test
    fun `首次同步创建学校学期并写入课程时段`() = runTest {
        val id = repo.saveSchoolTerm(plan(listOf(listOf(block("高数", 1, 1, 2)), emptyList())), true)
        assertNotEquals(localSemesterId, id)
        // 第 1 周有 1 条、第 2 周合法为空 → 只写 1 条时段。
        assertEquals(1, schoolRows(id).size)
        val semester = repo.semesterByCode(term.key)
        assertNotNull(semester)
        assertTrue(semester!!.isCurrent)
        assertEquals(2, semester.weekCount)
        assertEquals(1, repo.courses(id).size)
    }

    @Test
    fun `重复同步幂等且不丢自定义日程`() = runTest {
        val customCode = repo.savePlan(
            PlanSpec(
                semesterId = localSemesterId,
                name = "自习",
                weekday = 3,
                startUnit = 5,
                endUnit = 6,
                weeks = listOf(1, 2),
                roomText = "图书馆",
                colorKey = "BLUE",
                kind = PlanKind.EVENT,
            ),
        )
        val id = repo.saveSchoolTerm(plan(listOf(listOf(block("高数", 1, 1, 2)), emptyList())), false)
        assertEquals(1, schoolRows(id).size)

        // 第二次同步：课程变少，仍必须精确替换学校来源，自定义日程照旧。
        repo.saveSchoolTerm(plan(listOf(emptyList(), emptyList())), false)
        assertEquals(0, schoolRows(id).size)
        val custom = db.courseSessionDao().bySemesterAndSource(localSemesterId, ScheduleRepository.SOURCE_CUSTOM)
        assertTrue(custom.any { it.courseKey.endsWith(customCode) })
        assertEquals(2, custom.count { it.courseKey.endsWith(customCode) })
    }

    @Test
    fun `中途失败整批回滚且旧学校数据保留`() = runTest {
        val id = repo.saveSchoolTerm(plan(listOf(listOf(block("高数", 1, 1, 2)), emptyList())), false)
        assertEquals(1, schoolRows(id).size)

        val failure = runCatching {
            repo.saveSchoolTerm(plan(listOf(emptyList(), emptyList())), false) {
                error("模拟写入中途失败")
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        // 新批次没有提交：旧记录仍在，且不会被清成 0 条。
        assertEquals(1, schoolRows(id).size)
        assertEquals("高数", schoolRows(id).single().courseName)
    }

    @Test
    fun `用户确认的日期与周起始不被重复同步改掉`() = runTest {
        val firstStart = LocalDate.of(2026, 9, 7).toString()
        val id = repo.saveSchoolTerm(
            plan = plan(listOf(emptyList(), emptyList())),
            makeCurrent = true,
            startDateOverride = LocalDate.of(2026, 9, 7),
        )
        val saved = db.semesterDao().byCode(term.key)!!
        assertEquals(firstStart, saved.startDate)
        assertTrue(saved.isCurrent)
        // 用户把每周起始改成周日（直接改库模拟设置页写入）。
        db.semesterDao().upsertAll(listOf(saved.copy(weekStartOnSunday = true)))

        // 第二次同步带了一个推算出的不同日期，但没有用户确认的 override：
        // 已保存日期与周起始必须保留。
        repo.saveSchoolTerm(
            plan = plan(listOf(emptyList(), emptyList()), currentWeek = 9),
            makeCurrent = false,
            startDateOverride = null,
        )
        val again = db.semesterDao().byCode(term.key)!!
        assertEquals(firstStart, again.startDate)
        assertTrue(again.weekStartOnSunday)
        assertTrue(again.isCurrent) // makeCurrent=false 不清当前状态
    }

    @Test
    fun `用户确认新日期时覆盖旧日期并重算结束日`() = runTest {
        repo.saveSchoolTerm(
            plan = plan(listOf(emptyList(), emptyList())),
            makeCurrent = false,
            startDateOverride = LocalDate.of(2026, 9, 7),
        )
        repo.saveSchoolTerm(
            plan = plan(listOf(emptyList(), emptyList())),
            makeCurrent = false,
            startDateOverride = LocalDate.of(2026, 9, 14),
        )
        val saved = db.semesterDao().byCode(term.key)!!
        assertEquals("2026-09-14", saved.startDate)
        assertEquals("2026-09-27", saved.endDate) // 2 周 - 1 天
    }

    @Test
    fun `显示覆盖在同步后仍保留`() = runTest {
        val code = repo.savePlan(
            PlanSpec(
                semesterId = localSemesterId,
                name = "自习",
                weekday = 1,
                startUnit = 1,
                endUnit = 2,
                weeks = listOf(1),
                roomText = "A101",
                colorKey = "BLUE",
                kind = PlanKind.COURSE,
            ),
        )
        repo.saveDisplayNameOverride(localSemesterId, code, "我的自习")
        repo.saveSchoolTerm(plan(listOf(listOf(block("高数", 1, 1, 2)), emptyList())), false)
        val overrides = repo.displayOverrides(localSemesterId)
        assertTrue(overrides.any { it.courseCode == code && it.value == "我的自习" })
        assertNull(repo.semesterByCode("school:2099:9"))
    }
}
