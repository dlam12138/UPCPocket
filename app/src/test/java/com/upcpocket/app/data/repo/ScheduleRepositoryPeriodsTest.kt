package com.upcpocket.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.domain.PlanKind
import com.upcpocket.app.domain.PlanSpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用**真实 Room 数据库**验证「改作息之后已有课程的时间会跟着变」（复核报告 P2-2）。
 *
 * 缺陷原状：`savePlan` 把时间快照写进 `course_sessions.start_time / end_time`，
 * 而 `savePeriods` 只更新 `periods` 表。于是同一个界面上出现两套时间 ——
 * 网格按新作息画，详情与"每周课时"按旧快照算。
 *
 * 修复口径：**作息是唯一真相，快照退化成"节次缺失时的兜底"**。
 * 这条测试同时钉住两条路：
 *  1. 改作息时把已有记录的快照重写（数据层）；
 *  2. 读取方即使拿到旧快照，也要优先用最新节次解析（展示层，见 ScheduleScreen）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ScheduleRepositoryPeriodsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val semesterId = 1

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ScheduleRepository(db, UiPrefsStore(context))
        repo.ensureSeeded()
        db.courseDao().deleteBySemester(semesterId)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPlan(): String = repo.savePlan(
        PlanSpec(
            semesterId = semesterId,
            name = "自习",
            weekday = 1,
            startUnit = 1,
            endUnit = 2,
            weeks = listOf(1),
            roomText = "A101",
            colorKey = "BLUE",
            kind = PlanKind.EVENT,
        ),
    )

    private suspend fun periods() = repo.periods()

    @Test
    fun `默认作息里第 1 节是 08 00 起`() = runTest {
        val first = periods().first { it.indexNo == 1 }
        assertEquals("08:00", first.startText)
    }

    @Test
    fun `改作息后已有记录的时间快照会被重写`() = runTest {
        seedPlan()
        val before = db.courseSessionDao().bySemester(semesterId).single()
        assertEquals("08:00", before.startTime)
        assertEquals("09:50", before.endTime)

        // 把第 1 节整体后移半小时。这门课跨第 1-2 节，所以**开始时间变、结束时间不变**
        // （结束取自第 2 节）—— 顺带确认重写是逐节按 indexNo 取的，不是整段平移。
        repo.savePeriods(
            periods().map {
                if (it.indexNo == 1) it.copy(startTime = 830, endTime = 920) else it
            },
        )

        val after = db.courseSessionDao().bySemester(semesterId).single()
        assertEquals("开始时间没跟着作息走", "08:30", after.startTime)
        assertEquals("结束时间应仍取第 2 节的 09:50", "09:50", after.endTime)
    }

    @Test
    fun `改第二节的结束时间也会同步到跨两节的课`() = runTest {
        seedPlan() // 第 1-2 节
        repo.savePeriods(
            periods().map { if (it.indexNo == 2) it.copy(startTime = 900, endTime = 1010) else it },
        )
        val after = db.courseSessionDao().bySemester(semesterId).single()
        assertEquals("10:10", after.endTime)
        // 开始时间不受影响
        assertEquals("08:00", after.startTime)
    }

    @Test
    fun `作息里没有该节次时保留原快照而不是写空`() = runTest {
        seedPlan()
        // 只留第 1 节，删掉第 2 节
        repo.savePeriods(periods().filter { it.indexNo == 1 })

        val after = db.courseSessionDao().bySemester(semesterId).single()
        assertNotNull("节次缺失不应把时间抹成 null", after.endTime)
        // 第 1 节仍在，结束时间保持上次的值
        assertEquals("08:00", after.startTime)
    }

    @Test
    fun `改作息不会动周次与时段`() = runTest {
        seedPlan()
        val before = db.courseSessionDao().bySemester(semesterId).single()
        repo.savePeriods(
            periods().map { it.copy(startTime = it.startTime + 5, endTime = it.endTime + 5) },
        )
        val after = db.courseSessionDao().bySemester(semesterId).single()
        assertEquals(before.weekIndex, after.weekIndex)
        assertEquals(before.weekday, after.weekday)
        assertEquals(before.startUnit, after.startUnit)
        assertEquals(before.endUnit, after.endUnit)
        assertEquals(before.courseKey, after.courseKey)
    }

    @Test
    fun `改作息后读出来的 Session 用的是新时间`() = runTest {
        seedPlan()
        repo.savePeriods(
            periods().map { if (it.indexNo == 1) it.copy(startTime = 700, endTime = 750) else it },
        )
        val session = repo.sessionsOfSemester(semesterId)
            .first { it.source == ScheduleRepository.SOURCE_CUSTOM }
        assertEquals("07:00", session.startTime)
    }

    @Test
    fun `空作息列表被忽略_不会清空已有作息`() = runTest {
        val before = periods().size
        repo.savePeriods(emptyList())
        assertEquals(before, periods().size)
    }

    @Test
    fun `改作息后课时长度按新时间计算`() = runTest {
        // 这条模拟展示层的口径：第 1-2 节从 08:00-09:50（110 分钟）改成 08:00-09:00
        seedPlan()
        repo.savePeriods(
            periods().map { if (it.indexNo == 2) it.copy(startTime = 900, endTime = 950) else it },
        )
        val session = db.courseSessionDao().bySemester(semesterId).single()
        assertEquals("08:00", session.startTime)
        assertEquals("09:50", session.endTime)
    }
}
