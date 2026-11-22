package com.upcpocket.app.timetable

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class TimetableWeekWidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val weekStart = LocalDate.parse("2026-10-25")
    private val today = LocalDate.parse("2026-10-28")
    private val testNow: ZonedDateTime = today.atTime(12, 0).atZone(zone)
    private val open = PendingIntent.getActivity(
        context,
        1,
        Intent(context, Activity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun course(name: String, start: Int, end: Int) =
        TimetableWeekWidgetCourse(name = name, startPeriod = start, endPeriod = end)

    private fun day(
        offset: Int,
        state: TimetableWeekWidgetDayState,
        vararg courses: TimetableWeekWidgetCourse,
    ) = TimetableWeekWidgetDay(
        date = weekStart.plusDays(offset.toLong()),
        state = state,
        courses = courses.toList(),
    )

    private fun partialSnapshot() = TimetableWeekWidgetSnapshot(
        status = TimetableWeekWidgetStatus.PARTIAL,
        weekStart = weekStart,
        today = today,
        days = listOf(
            day(0, TimetableWeekWidgetDayState.EMPTY),
            day(1, TimetableWeekWidgetDayState.COURSES, course("高等数学", 1, 2)),
            day(
                2,
                TimetableWeekWidgetDayState.COURSES,
                course("大学物理", 3, 4),
                course("英语读写", 5, 6),
            ),
            day(
                3,
                TimetableWeekWidgetDayState.COURSES,
                course("化学原理", 1, 2),
                course("体育", 3, 4),
                course("程序设计", 5, 6),
            ),
            day(4, TimetableWeekWidgetDayState.UNSYNCED),
            day(5, TimetableWeekWidgetDayState.UNSYNCED),
            day(6, TimetableWeekWidgetDayState.EMPTY),
        ),
        fetchedAtEpochMillis = 1_700_000_000_000,
    )

    private fun rendered(
        snapshot: TimetableWeekWidgetSnapshot = partialSnapshot(),
        layout: TimetableWeekWidgetLayout = TimetableWeekWidgetLayout.forSize(250, 250, 1f),
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
        now: ZonedDateTime = testNow,
    ): RemoteViews = TimetableWeekWidgetRenderer.render(
        context = context,
        snapshot = snapshot,
        layout = layout,
        openIntent = open,
        appearance = appearance,
        now = now,
    )

    private fun applied(
        views: RemoteViews,
        widthDp: Int = 250,
        heightDp: Int = 250,
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

    private fun row(root: View, index: Int): View {
        val days = root.findViewById<ViewGroup>(R.id.widget_timetable_week_days)
        return days.getChildAt(index)
    }

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `four by four identifies all seven days and marks today`() {
        val root = applied(rendered())
        val days = root.findViewById<ViewGroup>(R.id.widget_timetable_week_days)
        assertEquals(7, days.childCount)
        assertEquals(
            listOf("周日 25", "周一 26", "周二 27", "周三 28", "周四 29", "周五 30", "周六 31"),
            (0 until days.childCount).map {
                days.getChildAt(it)
                    .findViewById<TextView>(R.id.widget_timetable_week_day_label)
                    .text
                    .toString()
            },
        )
        (0 until days.childCount).forEach { index ->
            val bar = row(root, index).findViewById<View>(R.id.widget_timetable_week_today_bar)
            assertEquals(
                if (index == 3) View.VISIBLE else View.GONE,
                bar.visibility,
            )
        }
        assertEquals(
            View.GONE,
            root.findViewById<View>(R.id.widget_timetable_week_state).visibility,
        )
        assertEquals(
            "部分日期未同步 · 点开查看",
            text(root, R.id.widget_timetable_week_footer),
        )
        assertEquals(
            "未同步",
            text(row(root, 4), R.id.widget_timetable_week_day_course_1),
        )
        assertEquals(
            "无课",
            text(row(root, 0), R.id.widget_timetable_week_day_course_1),
        )
        assertTrue(root.hasOnClickListeners())
    }

    @Test
    fun `partial footer only reports conflict or actually unsynced days`() {
        val outsideTermOnly = TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.PARTIAL,
            weekStart = weekStart,
            today = today,
            days = listOf(
                day(0, TimetableWeekWidgetDayState.OUTSIDE_TERM),
                day(1, TimetableWeekWidgetDayState.OUTSIDE_TERM),
                day(2, TimetableWeekWidgetDayState.COURSES, course("高等数学", 1, 2)),
                day(3, TimetableWeekWidgetDayState.COURSES, course("大学物理", 3, 4)),
                day(4, TimetableWeekWidgetDayState.EMPTY),
                day(5, TimetableWeekWidgetDayState.EMPTY),
                day(6, TimetableWeekWidgetDayState.EMPTY),
            ),
        )
        val outsideRoot = applied(rendered(snapshot = outsideTermOnly))
        // 只有学期外、没有待同步日期时不出现“部分日期未同步”页脚。
        assertEquals(
            View.GONE,
            outsideRoot.findViewById<View>(R.id.widget_timetable_week_footer).visibility,
        )
        assertEquals(
            "学期外",
            text(row(outsideRoot, 0), R.id.widget_timetable_week_day_course_1),
        )

        val conflictAndUnsynced = TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.PARTIAL,
            weekStart = weekStart,
            today = today,
            days = listOf(
                day(0, TimetableWeekWidgetDayState.CONFLICT),
                day(1, TimetableWeekWidgetDayState.UNSYNCED),
                day(2, TimetableWeekWidgetDayState.EMPTY),
                day(3, TimetableWeekWidgetDayState.EMPTY),
                day(4, TimetableWeekWidgetDayState.EMPTY),
                day(5, TimetableWeekWidgetDayState.EMPTY),
                day(6, TimetableWeekWidgetDayState.EMPTY),
            ),
        )
        val conflictRoot = applied(rendered(snapshot = conflictAndUnsynced))
        // 冲突优先于未同步。
        assertEquals(
            "存在冲突 · 点开查看",
            text(conflictRoot, R.id.widget_timetable_week_footer),
        )
    }

    @Test
    fun `four by four reports the actual hidden course count`() {
        val snapshot = TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.OK,
            weekStart = weekStart,
            today = today,
            days = (0..6).map { offset ->
                if (offset == 3) {
                    day(
                        3,
                        TimetableWeekWidgetDayState.COURSES,
                        course("高等数学", 1, 2),
                        course("大学物理", 3, 4),
                        course("化学原理", 5, 6),
                    )
                } else {
                    day(offset, TimetableWeekWidgetDayState.EMPTY)
                }
            },
            fetchedAtEpochMillis = 1_700_000_000_000,
        )

        val root = applied(
            rendered(
                snapshot = snapshot,
                layout = TimetableWeekWidgetLayout.forSize(250, 250, 1f),
            ),
        )

        assertEquals(
            "第1–2节 高等数学",
            text(row(root, 3), R.id.widget_timetable_week_day_course_1),
        )
        assertEquals(
            View.GONE,
            row(root, 3).findViewById<View>(R.id.widget_timetable_week_day_course_2).visibility,
        )
        // 省略数是独立视图，课程名的二次省略不会把它吃掉。
        val more = row(root, 3).findViewById<TextView>(R.id.widget_timetable_week_day_more)
        assertEquals(View.VISIBLE, more.visibility)
        assertEquals("+2", more.text.toString())
        assertEquals(
            "点开查看完整课表",
            text(root, R.id.widget_timetable_week_footer),
        )
    }

    @Test
    fun `minimum width with large font keeps all seven dates and the hidden count`() {
        val snapshot = TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.OK,
            weekStart = weekStart,
            today = today,
            days = (0..6).map { offset ->
                if (offset == 3) {
                    day(
                        3,
                        TimetableWeekWidgetDayState.COURSES,
                        course("高等数学", 1, 2),
                        course("大学物理", 3, 4),
                        course("化学原理", 5, 6),
                    )
                } else {
                    day(offset, TimetableWeekWidgetDayState.EMPTY)
                }
            },
            fetchedAtEpochMillis = 1_700_000_000_000,
        )
        val layout = TimetableWeekWidgetLayout.forSize(180, 180, 1.4f)

        // 真实 180×180 布局：最后一日底边在组件内，日期与独立的 +2 都不省略、不裁切。
        val root = applied(
            rendered(snapshot = snapshot, layout = layout),
            widthDp = 180,
            heightDp = 180,
            fontScale = 1.4f,
        )

        val days = root.findViewById<ViewGroup>(R.id.widget_timetable_week_days)
        assertEquals(7, days.childCount)
        // 窄卡大字号用单字星期，日期数字仍完整可见。
        assertEquals(
            listOf(
                "日 25", "一 26", "二 27", "三 28", "四 29", "五 30", "六 31",
            ),
            (0 until days.childCount).map {
                days.getChildAt(it)
                    .findViewById<TextView>(R.id.widget_timetable_week_day_label)
                    .text
                    .toString()
            },
        )
        val lastDay = days.getChildAt(days.childCount - 1)
        assertTrue(
            "最后一日超出组件：${topIn(lastDay, root) + lastDay.height} > ${root.height}",
            topIn(lastDay, root) + lastDay.height <= root.height,
        )
        (0 until days.childCount).forEach { index ->
            val dayRow = days.getChildAt(index)
            assertTextFullyShown(
                dayRow.findViewById<TextView>(R.id.widget_timetable_week_day_label),
                dayRow.width,
                "第 ${index + 1} 日日期",
            )
        }
        val overflowRow = days.getChildAt(3)
        assertEquals("+2", text(overflowRow, R.id.widget_timetable_week_day_more))
        assertTextFullyShown(
            overflowRow.findViewById<TextView>(R.id.widget_timetable_week_day_more),
            overflowRow.width,
            "额外课程数",
        )
        assertEquals(
            View.GONE,
            root.findViewById<View>(R.id.widget_timetable_week_footer).visibility,
        )
    }

    /**
     * 省略与裁切都是实际布局结果：TextView 的 Layout 已算好省略号，宽高来自父容器的
     * 实测布局。启用 [GraphicsMode.Mode.NATIVE] 才能得到接近真机的文字行高；旧文字
     * 度量会把 180dp 内的后几行挤掉，无法验证真实高度约束。
     */
    private fun assertTextFullyShown(view: TextView, rowWidth: Int, what: String) {
        val textLayout = requireNotNull(view.layout) { "$what 未完成布局" }
        assertTrue("$what 没有可读行", textLayout.lineCount >= 1)
        assertEquals("$what 被省略", 0, textLayout.getEllipsisCount(0))
        assertTrue("$what 高度被裁切", view.height >= textLayout.height)
        assertTrue("$what 超出当日行宽", view.right <= rowWidth)
    }

    private fun topIn(view: View, ancestor: View): Int {
        var top = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            top += current.top
            current = current.parent as? View
        }
        return top
    }

    @Test
    fun `complete empty week is shown as synced empty`() {
        val snapshot = TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.EMPTY,
            weekStart = weekStart,
            today = today,
            days = (0..6).map { day(it, TimetableWeekWidgetDayState.EMPTY) },
            fetchedAtEpochMillis = 1_700_000_000_000,
        )

        val root = applied(rendered(snapshot = snapshot))

        assertEquals(
            "全周无课 · 已同步",
            text(root, R.id.widget_timetable_week_footer),
        )
        (0..6).forEach { index ->
            assertEquals(
                "无课",
                text(row(root, index), R.id.widget_timetable_week_day_course_1),
            )
        }
    }

    @Test
    fun `every global state renders its own bounded state without days`() {
        val statuses = listOf(
            TimetableWeekWidgetStatus.NO_ACCOUNT,
            TimetableWeekWidgetStatus.ACCOUNT_LOADING,
            TimetableWeekWidgetStatus.CLEANUP,
            TimetableWeekWidgetStatus.READING,
            TimetableWeekWidgetStatus.NEEDS_SYNC,
            TimetableWeekWidgetStatus.CONFLICT,
            TimetableWeekWidgetStatus.READ_ERROR,
            TimetableWeekWidgetStatus.OUTSIDE_TERM,
        )

        statuses.forEach { status ->
            val views = rendered(
                snapshot = TimetableWeekWidgetSnapshot(
                    status = status,
                    weekStart = weekStart,
                    today = today,
                ),
            )
            assertEquals(R.layout.widget_timetable_week, views.layoutId)
            val root = applied(views)
            assertEquals(
                View.VISIBLE,
                root.findViewById<View>(R.id.widget_timetable_week_state).visibility,
            )
            assertTrue(text(root, R.id.widget_timetable_week_state_title).isNotEmpty())
            assertTrue(text(root, R.id.widget_timetable_week_state_hint).isNotEmpty())
            assertEquals(
                View.GONE,
                root.findViewById<View>(R.id.widget_timetable_week_days).visibility,
            )
        }
    }

    @Test
    fun `state reapply clears the previous course subtree`() {
        val configuration = Configuration(context.resources.configuration)
        val ctx = context.createConfigurationContext(configuration)
        val root = rendered().apply(ctx, FrameLayout(ctx))
        val days = root.findViewById<ViewGroup>(R.id.widget_timetable_week_days)
        assertEquals(7, days.childCount)

        rendered(
            snapshot = TimetableWeekWidgetSnapshot(
                status = TimetableWeekWidgetStatus.NO_ACCOUNT,
                weekStart = weekStart,
                today = today,
            ),
        ).reapply(ctx, root)

        assertEquals(0, days.childCount)
        assertEquals(View.GONE, days.visibility)
        assertEquals(
            View.VISIBLE,
            root.findViewById<View>(R.id.widget_timetable_week_state).visibility,
        )
        assertEquals(
            "未绑定学校账号",
            text(root, R.id.widget_timetable_week_state_title),
        )
    }
}
