package com.upcpocket.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.db.DisplayOverrideEntity
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.domain.PlanKind
import com.upcpocket.app.domain.PlanSpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用**真实 Room 数据库**验证「日程 ↔ 课程」类型切换不留残留（复核报告 P1）。
 *
 * 为什么必须用真库：缺陷的症状是"旧行没被删除"，纯函数层面看不出来。
 * 之前的实现先把 code 前缀换成新的、再按**新** code 删除 ——
 * 删的是新标识，旧标识的行一条都没动，于是：
 *
 *  · 网格里同一门课出现两次（旧 P… 行 + 新 C… 行）；
 *  · 课程总览留下一条 `courses` 残留（删日程时按新 code 删，旧行删不掉）；
 *  · 显示覆盖跟着旧 code 漂着，改过课名之后行为莫名其妙。
 *
 * 这条测试把"新建 → 切换 → 查询 → 删除"整条链路走一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ScheduleRepositoryPlanKindTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository

    private val semesterId = 1

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // UiPrefsStore 用 DataStore，会落盘；这里只需要 demoMode 的默认值（关掉），
        // 直接用真实实例即可 —— 测试里不读它。
        repo = ScheduleRepository(db, UiPrefsStore(context))
        repo.ensureSeeded()

        // 清掉种子数据里的示例课程，避免干扰断言
        db.courseDao().deleteBySemester(semesterId)
        db.displayOverrideDao().deleteBySemester(semesterId)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun spec(
        code: String?,
        kind: PlanKind,
        name: String = "自习",
        weeks: List<Int> = listOf(1, 2),
        weekday: Int = 1,
        startUnit: Int = 1,
        endUnit: Int = 2,
    ) = PlanSpec(
        code = code,
        semesterId = semesterId,
        name = name,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        weeks = weeks,
        roomText = "A101",
        colorKey = "BLUE",
        teacherName = if (kind == PlanKind.COURSE) "张老师" else null,
        kind = kind,
    )

    private suspend fun sessionRows() = db.courseSessionDao().bySemester(semesterId)
    private suspend fun courseRows() = db.courseDao().bySemester(semesterId)
    private suspend fun overrideRows() = db.displayOverrideDao().bySemester(semesterId)

    @Test
    fun `新建日程不写 courses 表`() = runTest {
        val code = repo.savePlan(spec(null, PlanKind.EVENT))
        assertEquals(2, sessionRows().size)
        assertTrue(courseRows().isEmpty())
        assertTrue(PlanKind.EVENT == PlanKind.ofCode(code))
    }

    @Test
    fun `新建课程同时写 sessions 与 courses`() = runTest {
        val code = repo.savePlan(spec(null, PlanKind.COURSE, name = "人体寄生虫学"))
        assertEquals(2, sessionRows().size)
        assertEquals(1, courseRows().size)
        assertEquals(code, courseRows().single().code)
    }

    @Test
    fun `日程改课程后旧标识的行必须全部消失`() = runTest {
        val eventCode = repo.savePlan(spec(null, PlanKind.EVENT, name = "自习"))

        // 切换到「课程」
        val courseCode = repo.savePlan(
            spec(eventCode, PlanKind.COURSE, name = "自习", startUnit = 3, endUnit = 4),
        )

        // 标识变了（这是清理旧行的依据）
        assertNotEquals(eventCode, courseCode)
        assertEquals(PlanKind.COURSE, PlanKind.ofCode(courseCode))

        // 只剩新标识的 2 行；不能出现 4 行（旧 2 + 新 2）
        val rows = sessionRows()
        assertEquals("切换类型后留下了旧行", 2, rows.size)
        assertTrue(rows.all { it.courseKey == "$semesterId:$courseCode" })
        assertTrue(rows.all { it.startUnit == 3 })

        // 课程总览恰好一条，且是新标识
        assertEquals(1, courseRows().size)
        assertEquals(courseCode, courseRows().single().code)
    }

    @Test
    fun `课程改日程后 courses 里不能留残留`() = runTest {
        val courseCode = repo.savePlan(spec(null, PlanKind.COURSE, name = "高等数学"))
        assertEquals(1, courseRows().size)

        val eventCode = repo.savePlan(spec(courseCode, PlanKind.EVENT, name = "高等数学"))
        assertEquals(PlanKind.EVENT, PlanKind.ofCode(eventCode))

        assertEquals("切换成日程后 courses 表应清空", 0, courseRows().size)
        assertEquals(2, sessionRows().size)
        assertTrue(sessionRows().all { it.courseKey == "$semesterId:$eventCode" })
    }

    @Test
    fun `来回切换类型不会累积记录`() = runTest {
        var code = repo.savePlan(spec(null, PlanKind.EVENT))
        repeat(3) {
            code = repo.savePlan(spec(code, PlanKind.COURSE))
            code = repo.savePlan(spec(code, PlanKind.EVENT))
        }
        // 无论切几次，都只有一份日程
        assertEquals(2, sessionRows().size)
        assertEquals(0, courseRows().size)
        assertEquals(PlanKind.EVENT, PlanKind.ofCode(code))
    }

    @Test
    fun `切换类型会清掉旧标识的显示覆盖`() = runTest {
        val eventCode = repo.savePlan(spec(null, PlanKind.EVENT))
        repo.saveDisplayNameOverride(semesterId, eventCode, "改过的名字")
        assertEquals(1, overrideRows().size)

        val courseCode = repo.savePlan(spec(eventCode, PlanKind.COURSE))

        // 覆盖跟着旧 code 一起清掉：否则改回日程时，那个名字会"突然复活"
        assertTrue("切换类型后旧标识的显示覆盖残留", overrideRows().isEmpty())
        assertNotEquals(eventCode, courseCode)
    }

    @Test
    fun `类型不变时仍能正常改写周次`() = runTest {
        val code = repo.savePlan(spec(null, PlanKind.EVENT, weeks = listOf(1, 2, 3)))
        assertEquals(3, sessionRows().size)

        repo.savePlan(spec(code, PlanKind.EVENT, weeks = listOf(1, 2)))
        // 同一标识下的旧周次也要清（不能留下第 3 周的孤儿行）
        assertEquals(2, sessionRows().size)
        assertTrue(sessionRows().none { it.weekIndex == 3 })
    }

    @Test
    fun `删除日程会把 sessions 与 courses 一起清掉`() = runTest {
        val courseCode = repo.savePlan(spec(null, PlanKind.COURSE))
        repo.deletePlan(semesterId, courseCode)
        assertTrue(sessionRows().isEmpty())
        assertTrue(courseRows().isEmpty())
    }

    @Test
    fun `切换类型后删除能清干净`() = runTest {
        val eventCode = repo.savePlan(spec(null, PlanKind.EVENT))
        val courseCode = repo.savePlan(spec(eventCode, PlanKind.COURSE))
        repo.deletePlan(semesterId, courseCode)
        assertTrue("删除后仍有残留", sessionRows().isEmpty())
        assertTrue(courseRows().isEmpty())
    }

    @Test
    fun `课程总览不会出现两门同名课`() = runTest {
        val eventCode = repo.savePlan(spec(null, PlanKind.EVENT, name = "体育"))
        repo.savePlan(spec(eventCode, PlanKind.COURSE, name = "体育"))

        val courses = repo.courses(semesterId)
        assertEquals("课程总览出现重复课程", 1, courses.size)
        assertEquals("体育", courses.single().nameZh)
        // 网格里也只有 2 行（2 周 × 1 个时段），不是 4 行
        assertEquals(2, sessionRows().size)
    }
}
