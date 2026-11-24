package com.upcpocket.app.timetable

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class TimetableTodayTomorrowWidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.parse("2026-11-30")
    private val tomorrow = LocalDate.parse("2026-12-01")
    private val open = PendingIntent.getActivity(
        context,
        1,
        Intent(context, Activity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun course(name: String, start: Int, end: Int) =
        TimetableTodayTomorrowWidgetCourse(name = name, startPeriod = start, endPeriod = end)

    private fun snapshot(
        todayState: TimetableTodayTomorrowWidgetDayState,
        tomorrowState: TimetableTodayTomorrowWidgetDayState,
        todayCourses: List<TimetableTodayTomorrowWidgetCourse> = emptyList(),
        tomorrowCourses: List<TimetableTodayTomorrowWidgetCourse> = emptyList(),
        status: TimetableTodayTomorrowWidgetStatus = TimetableTodayTomorrowWidgetStatus.OK,
    ) = TimetableTodayTomorrowWidgetSnapshot(
        status = status,
        today = today,
        tomorrow = tomorrow,
        days = listOf(
            TimetableTodayTomorrowWidgetDay(today, todayState, todayCourses),
            TimetableTodayTomorrowWidgetDay(tomorrow, tomorrowState, tomorrowCourses),
        ),
    )

    private fun rendered(
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
        layout: TimetableTodayTomorrowWidgetLayout = TimetableTodayTomorrowWidgetLayout.forSize(250, 140, 1f),
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
    ): RemoteViews = TimetableTodayTomorrowWidgetRenderer.render(
        context = context,
        snapshot = snapshot,
        layout = layout,
        openIntent = open,
        appearance = appearance,
    )

    private fun applied(
        views: RemoteViews,
        widthDp: Int = 250,
        heightDp: Int = 140,
        fontScale: Float = 1f,
    ): View {
        val configuration = Configuration(context.resources.configuration).apply {
            this.fontScale = fontScale
        }
        val ctx = context.createConfigurationContext(configuration)
        val root = views.apply(ctx, FrameLayout(ctx))
        val width = (widthDp * ctx.resources.displayMetrics.density).toInt()
        val height = (heightDp * ctx.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return root
    }

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `common width shows both dates, periods, names and independent overflow`() {
        val root = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.COURSES,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.COURSES,
                    todayCourses = listOf(
                        course("高等数学", 1, 2),
                        course("大学物理", 3, 4),
                        course("程序设计", 5, 6),
                    ),
                    tomorrowCourses = listOf(
                        course("英语读写", 5, 6),
                        course("体育", 7, 8),
                    ),
                ),
            ),
        )

        assertEquals("今天 11月30日", text(root, R.id.widget_timetable_tt_today_date))
        assertEquals("明天 12月1日", text(root, R.id.widget_timetable_tt_tomorrow_date))
        assertEquals("1–2节 高等数学", text(root, R.id.widget_timetable_tt_today_course_1))
        assertEquals("3–4节 大学物理", text(root, R.id.widget_timetable_tt_today_course_2))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_today_course_3).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_tt_today_more).visibility)
        assertEquals("+1", text(root, R.id.widget_timetable_tt_today_more))

        assertEquals("5–6节 英语读写", text(root, R.id.widget_timetable_tt_tomorrow_course_1))
        assertEquals("7–8节 体育", text(root, R.id.widget_timetable_tt_tomorrow_course_2))
        // 两列省略数互相独立：明天没有超出就不显示“+0”。
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_tomorrow_more).visibility)

        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_state).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_tt_content).visibility)
        assertTrue(root.hasOnClickListeners())
    }

    @Test
    fun `narrow large font keeps both dates and double digit periods unclipped`() {
        val layout = TimetableTodayTomorrowWidgetLayout.forSize(180, 110, 1.5f)
        val root = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.COURSES,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.COURSES,
                    todayCourses = listOf(
                        course("高等数学", 13, 14),
                        course("大学物理", 1, 2),
                    ),
                    tomorrowCourses = listOf(course("大学物理", 11, 12)),
                ),
                layout = layout,
            ),
            widthDp = 180,
            heightDp = 110,
            fontScale = 1.5f,
        )

        assertEquals("今天 30", text(root, R.id.widget_timetable_tt_today_date))
        assertEquals("明天 12/1", text(root, R.id.widget_timetable_tt_tomorrow_date))
        val todayLine = text(root, R.id.widget_timetable_tt_today_course_1)
        val tomorrowLine = text(root, R.id.widget_timetable_tt_tomorrow_course_1)
        // 双位数节次完整保留，只在课程名末尾省略。
        assertEquals("13–14节 高等…", todayLine)
        assertEquals("11–12节 大学…", tomorrowLine)
        assertEquals("+1", text(root, R.id.widget_timetable_tt_today_more))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_tomorrow_more).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_today_course_2).visibility)

        assertTextFullyShown(
            root.findViewById<TextView>(R.id.widget_timetable_tt_today_date),
            root.findViewById<View>(R.id.widget_timetable_tt_today_column),
            "今天日期",
        )
        assertTextFullyShown(
            root.findViewById<TextView>(R.id.widget_timetable_tt_tomorrow_date),
            root.findViewById<View>(R.id.widget_timetable_tt_tomorrow_column),
            "明天日期",
        )
        assertTextFullyShown(
            root.findViewById<TextView>(R.id.widget_timetable_tt_today_course_1),
            root.findViewById<View>(R.id.widget_timetable_tt_today_column),
            "今天双位数节次课程",
        )
        assertTextFullyShown(
            root.findViewById<TextView>(R.id.widget_timetable_tt_tomorrow_course_1),
            root.findViewById<View>(R.id.widget_timetable_tt_tomorrow_column),
            "明天双位数节次课程",
        )
        assertTextFullyShown(
            root.findViewById<TextView>(R.id.widget_timetable_tt_today_more),
            root.findViewById<View>(R.id.widget_timetable_tt_today_header),
            "今天额外课程数",
        )
    }

    @Test
    fun `each day reports its own state without merging the other column`() {
        val emptyAndUnsynced = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.EMPTY,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.UNSYNCED,
                ),
            ),
        )
        assertEquals("今天 11月30日", text(emptyAndUnsynced, R.id.widget_timetable_tt_today_date))
        assertEquals("明天 12月1日", text(emptyAndUnsynced, R.id.widget_timetable_tt_tomorrow_date))
        assertEquals("无课", text(emptyAndUnsynced, R.id.widget_timetable_tt_today_course_1))
        assertEquals("未同步", text(emptyAndUnsynced, R.id.widget_timetable_tt_tomorrow_course_1))

        val outsideAndConflict = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.OUTSIDE_TERM,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.CONFLICT,
                ),
            ),
        )
        assertEquals("学期外", text(outsideAndConflict, R.id.widget_timetable_tt_today_course_1))
        assertEquals("存在冲突", text(outsideAndConflict, R.id.widget_timetable_tt_tomorrow_course_1))
    }

    @Test
    fun `unreadable cache keeps both real dates and marks each day`() {
        val root = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.READ_ERROR,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.READ_ERROR,
                    status = TimetableTodayTomorrowWidgetStatus.READ_ERROR,
                ),
            ),
        )

        assertEquals("今天 11月30日", text(root, R.id.widget_timetable_tt_today_date))
        assertEquals("明天 12月1日", text(root, R.id.widget_timetable_tt_tomorrow_date))
        assertEquals("读取失败", text(root, R.id.widget_timetable_tt_today_course_1))
        assertEquals("读取失败", text(root, R.id.widget_timetable_tt_tomorrow_course_1))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_state).visibility)
    }

    @Test
    fun `missing timetable capability is explicit instead of unsynced`() {
        val root = applied(
            rendered(
                TimetableTodayTomorrowWidgetSnapshot(
                    status = TimetableTodayTomorrowWidgetStatus.UNAVAILABLE,
                    today = today,
                    tomorrow = tomorrow,
                ),
            ),
        )

        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_content).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_tt_state).visibility)
        assertEquals(
            "课表功能不可用",
            text(root, R.id.widget_timetable_tt_state_title),
        )
        assertTrue(text(root, R.id.widget_timetable_tt_state_hint).isNotEmpty())
        // 整卡导航仍指向课表入口，插件不可用时由应用现有路由回退首页。
        assertTrue(root.hasOnClickListeners())
    }

    @Test
    fun `every account and reading state hides both columns`() {
        val statuses = listOf(
            TimetableTodayTomorrowWidgetStatus.NO_ACCOUNT,
            TimetableTodayTomorrowWidgetStatus.ACCOUNT_LOADING,
            TimetableTodayTomorrowWidgetStatus.CLEANUP,
            TimetableTodayTomorrowWidgetStatus.READING,
        )

        statuses.forEach { status ->
            val views = rendered(
                TimetableTodayTomorrowWidgetSnapshot(
                    status = status,
                    today = today,
                    tomorrow = tomorrow,
                ),
            )
            assertEquals(R.layout.widget_timetable_today_tomorrow, views.layoutId)
            val root = applied(views)
            assertEquals(
                View.GONE,
                root.findViewById<View>(R.id.widget_timetable_tt_content).visibility,
            )
            assertEquals(
                View.VISIBLE,
                root.findViewById<View>(R.id.widget_timetable_tt_state).visibility,
            )
            assertTrue(text(root, R.id.widget_timetable_tt_state_title).isNotEmpty())
            assertTrue(text(root, R.id.widget_timetable_tt_state_hint).isNotEmpty())
        }
    }

    @Test
    fun `data state reapply clears the previous state and course rows`() {
        val configuration = Configuration(context.resources.configuration)
        val ctx = context.createConfigurationContext(configuration)
        val root = rendered(
            snapshot = snapshot(
                todayState = TimetableTodayTomorrowWidgetDayState.COURSES,
                tomorrowState = TimetableTodayTomorrowWidgetDayState.COURSES,
                todayCourses = listOf(course("高等数学", 1, 2), course("大学物理", 3, 4)),
                tomorrowCourses = listOf(course("体育", 5, 6)),
            ),
        ).apply(ctx, FrameLayout(ctx))
        assertEquals("3–4节 大学物理", text(root, R.id.widget_timetable_tt_today_course_2))

        rendered(
            snapshot = TimetableTodayTomorrowWidgetSnapshot(
                status = TimetableTodayTomorrowWidgetStatus.NO_ACCOUNT,
                today = today,
                tomorrow = tomorrow,
            ),
        ).reapply(ctx, root)
        assertEquals(
            View.GONE,
            root.findViewById<View>(R.id.widget_timetable_tt_content).visibility,
        )
        assertEquals(
            View.VISIBLE,
            root.findViewById<View>(R.id.widget_timetable_tt_state).visibility,
        )

        rendered(
            snapshot = snapshot(
                todayState = TimetableTodayTomorrowWidgetDayState.COURSES,
                tomorrowState = TimetableTodayTomorrowWidgetDayState.EMPTY,
                todayCourses = listOf(course("化学原理", 7, 8)),
            ),
        ).reapply(ctx, root)

        assertEquals(
            View.VISIBLE,
            root.findViewById<View>(R.id.widget_timetable_tt_content).visibility,
        )
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_state).visibility)
        assertEquals("7–8节 化学原理", text(root, R.id.widget_timetable_tt_today_course_1))
        // 上一轮的“大学物理”和省略数必须被清空，不能残留。
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_today_course_2).visibility)
        assertEquals("", text(root, R.id.widget_timetable_tt_today_course_2))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_tt_today_more).visibility)
        assertEquals("无课", text(root, R.id.widget_timetable_tt_tomorrow_course_1))
    }

    @Test
    fun `compact cross month date keeps the month`() {
        val root = applied(
            rendered(
                snapshot = snapshot(
                    todayState = TimetableTodayTomorrowWidgetDayState.EMPTY,
                    tomorrowState = TimetableTodayTomorrowWidgetDayState.EMPTY,
                ),
                layout = TimetableTodayTomorrowWidgetLayout.forSize(180, 110, 1.5f),
            ),
            widthDp = 180,
            heightDp = 110,
            fontScale = 1.5f,
        )

        assertEquals("今天 30", text(root, R.id.widget_timetable_tt_today_date))
        assertEquals("明天 12/1", text(root, R.id.widget_timetable_tt_tomorrow_date))
    }

    private fun assertTextFullyShown(view: TextView, container: View, what: String) {
        val textLayout = requireNotNull(view.layout) { "$what 未完成布局" }
        assertTrue("$what 没有可读行", textLayout.lineCount >= 1)
        assertEquals("$what 被省略", 0, textLayout.getEllipsisCount(0))
        assertTrue("$what 高度被裁切", view.height >= textLayout.height)
        assertTrue("$what 超出容器宽度", view.right <= container.width)
    }
}
