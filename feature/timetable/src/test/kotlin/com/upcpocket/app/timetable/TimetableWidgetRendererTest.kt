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
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableWidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.now(zone)
    private val testNow: ZonedDateTime = today.atTime(12, 0).atZone(zone)
    private val open = PendingIntent.getActivity(
        context,
        1,
        Intent(context, Activity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun course(
        name: String = "化工原理与传递过程综合实验（合成）",
        location: String? = "卓越教学楼 B205 西侧联合实验区",
        start: Int = 0,
        end: Int = 23 * 60 + 59,
    ) = TimetableWidgetCourse(
        name = name,
        location = location,
        timeLabel = "08:00–09:35",
        startMinute = start,
        endMinute = end,
        periodLabel = "第1–2节",
        colorSlot = 2,
    )

    private fun rendered(
        snapshot: TimetableWidgetSnapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = today,
            courses = listOf(course()),
            fetchedAtEpochMillis = 1_700_000_000_000,
        ),
        layout: TimetableWidgetLayout = TimetableWidgetLayout.forSize(172, 234, 1f),
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
        now: ZonedDateTime = testNow,
    ): RemoteViews = TimetableWidgetRenderer.render(
        context = context,
        snapshot = snapshot,
        layout = layout,
        refreshing = false,
        failed = false,
        openIntent = open,
        appearance = appearance,
        now = now,
    )

    private fun snapshotOf(vararg courses: TimetableWidgetCourse) = TimetableWidgetSnapshot(
        status = TimetableWidgetStatus.OK,
        date = today,
        courses = courses.toList(),
        fetchedAtEpochMillis = 1_700_000_000_000,
    )

    private fun applied(views: RemoteViews, widthDp: Int, heightDp: Int, fontScale: Float = 1f): View {
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

    @Test
    fun `all real widget states build the same bounded layout without pretending`() {
        val states = listOf(
            TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null),
            TimetableWidgetSnapshot(TimetableWidgetStatus.ACCOUNT_LOADING, null),
            TimetableWidgetSnapshot(TimetableWidgetStatus.CLEANUP, null),
            TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today),
            TimetableWidgetSnapshot(TimetableWidgetStatus.OUTSIDE_TERM, today),
            TimetableWidgetSnapshot(TimetableWidgetStatus.OK, today, emptyList()),
            TimetableWidgetSnapshot(
                TimetableWidgetStatus.OK,
                today,
                listOf(course()),
                fetchedAtEpochMillis = 1_700_000_000_000,
            ),
        )
        states.forEach { snapshot ->
            val views = rendered(snapshot)
            assertEquals(R.layout.widget_timetable_today, views.layoutId)
        }
    }

    @Test
    fun `layout tiers preserve the main course before secondary metadata`() {
        val twoByTwo = TimetableWidgetLayout.forSize(172, 234, 1f)
        assertTrue(twoByTwo.compact)
        assertTrue(twoByTwo.showDate)
        assertTrue(twoByTwo.showFooter)
        // 高 234dp 的常规 2×2 有余量放一条次课；短卡才退化为单主课。
        assertTrue(twoByTwo.showSecondary)
        assertFalse(twoByTwo.dense)

        val short = TimetableWidgetLayout.forSize(160, 160, 1f)
        assertTrue(short.showDate)
        assertFalse(short.showFooter)
        assertFalse(short.showSecondary)

        val largeFont = TimetableWidgetLayout.forSize(160, 160, 1.5f)
        assertTrue(largeFont.dense)
        assertFalse(largeFont.showDate)
        assertFalse(largeFont.showFooter)
        assertFalse(largeFont.showLocation)

        val fourByTwo = TimetableWidgetLayout.forSize(360, 234, 1f)
        assertTrue(fourByTwo.showSecondary)
        assertTrue(fourByTwo.showFooter)
        assertFalse(fourByTwo.compact)

        val shortFourByTwo = TimetableWidgetLayout.forSize(360, 180, 1f)
        assertTrue(shortFourByTwo.showSecondary)
        assertFalse(shortFourByTwo.showFooter)

        val largeFourByTwo = TimetableWidgetLayout.forSize(360, 234, 1.5f)
        assertTrue(largeFourByTwo.dense)
        assertFalse(largeFourByTwo.showSecondary)
        assertTrue(largeFourByTwo.showDate)
    }

    @Test
    fun `two by two keeps two complete title lines and a true date line`() {
        val root = applied(
            rendered(layout = TimetableWidgetLayout.forSize(172, 234, 1f)),
            widthDp = 172,
            heightDp = 234,
        )
        val title = root.findViewById<TextView>(R.id.widget_timetable_course)
        assertNotNull(title.layout)
        assertTrue(title.lineCount in 1..2)
        assertEquals(
            "main course must not be clipped into a one-line fragment",
            0,
            (0 until title.lineCount).sumOf { title.layout.getEllipsisCount(it) },
        )
        val date = root.findViewById<TextView>(R.id.widget_timetable_date)
        assertNotNull(date.layout)
        assertEquals("date must never collapse to 11…", 0, date.layout.getEllipsisCount(0))
        assertTrue(title.bottom <= root.height)
        assertNull(root.findViewById<View>(context.resources.getIdentifier("widget_timetable_refresh", "id", context.packageName)))
        assertTrue(root.hasOnClickListeners())
    }

    @Test
    fun `large font uses dense layout without losing the course block`() {
        val root = applied(
            rendered(layout = TimetableWidgetLayout.forSize(160, 160, 1.5f)),
            widthDp = 160,
            heightDp = 160,
            fontScale = 1.5f,
        )
        val title = root.findViewById<TextView>(R.id.widget_timetable_course)
        val time = root.findViewById<TextView>(R.id.widget_timetable_time)
        val location = root.findViewById<TextView>(R.id.widget_timetable_location)
        assertTrue(title.height > 0)
        assertTrue(time.height > 0)
        assertNotNull(time.layout)
        assertTrue(time.height >= time.layout.height)
        // 1.5× 字体的 160dp 短卡先放弃地点行，绝不让地点被压成半行。
        assertEquals(View.GONE, location.visibility)
        assertTrue(time.bottom <= root.height)
    }

    @Test
    fun `four by two shows multiple courses across realistic launcher heights and fonts`() {
        val compactFourByTwo = TimetableWidgetLayout.forSize(320, 145, 1.0f)
        assertTrue(compactFourByTwo.showSecondary)
        assertTrue(compactFourByTwo.showSidePanel)
        assertEquals(2, compactFourByTwo.maxSideCourses)

        val mediumFontFourByTwo = TimetableWidgetLayout.forSize(320, 175, 1.15f)
        assertTrue(mediumFontFourByTwo.showSecondary)
        assertTrue(mediumFontFourByTwo.showSidePanel)

        val courses = listOf(
            course(name = "高等数学", start = 8 * 60, end = 9 * 60 + 35),
            course(name = "大学物理", location = "讲堂群 201", start = 10 * 60 + 45, end = 12 * 60 + 20),
            course(name = "油气地质学", location = "工科楼 C102", start = 14 * 60, end = 15 * 60 + 35),
            course(name = "英语读写", location = "南堂 305", start = 16 * 60 + 45, end = 17 * 60 + 30),
        )
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = today,
            courses = courses,
            fetchedAtEpochMillis = 1_700_000_000_000,
        )
        val morning = today.atTime(8, 30).atZone(zone)
        val root = applied(
            rendered(
                snapshot = snapshot,
                layout = TimetableWidgetLayout.forSize(340, 180, 1.0f),
                now = morning,
            ),
            widthDp = 340,
            heightDp = 180,
        )
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_side_panel).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_upcoming_1).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_upcoming_2).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_timetable_upcoming_3).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_upcoming_more).visibility)
        assertEquals("大学物理", root.findViewById<TextView>(R.id.widget_timetable_upcoming_name_1).text.toString())
        assertEquals("油气地质学", root.findViewById<TextView>(R.id.widget_timetable_upcoming_name_2).text.toString())
        assertEquals("英语读写", root.findViewById<TextView>(R.id.widget_timetable_upcoming_name_3).text.toString())

        val fiveCourses = courses +
            course(name = "体育", location = "田径场", start = 19 * 60, end = 19 * 60 + 45)
        val overflowRoot = applied(
            rendered(
                snapshot = snapshot.copy(courses = fiveCourses),
                layout = TimetableWidgetLayout.forSize(320, 145, 1.0f),
                now = morning,
            ),
            widthDp = 320,
            heightDp = 145,
        )
        assertEquals(View.VISIBLE, overflowRoot.findViewById<View>(R.id.widget_timetable_upcoming_1).visibility)
        assertEquals(View.VISIBLE, overflowRoot.findViewById<View>(R.id.widget_timetable_upcoming_more).visibility)
    }

    @Test
    fun `active course counts whole minutes and clears at the end boundary`() {
        val snapshot = snapshotOf(course(start = 12 * 60, end = 13 * 60))

        fun remainingAt(hour: Int, minute: Int): TextView = applied(
            rendered(snapshot = snapshot, now = today.atTime(hour, minute).atZone(zone)),
            widthDp = 172,
            heightDp = 234,
        ).findViewById(R.id.widget_timetable_remaining)

        val atStart = remainingAt(12, 0)
        assertEquals(View.VISIBLE, atStart.visibility)
        assertEquals("剩 60 分钟", atStart.text.toString())

        assertEquals("剩 59 分钟", remainingAt(12, 1).text.toString())
        assertEquals("剩 1 分钟", remainingAt(12, 59).text.toString())
        assertEquals(View.GONE, remainingAt(13, 0).visibility)
    }

    @Test
    fun `unknown or out of range course times never show a countdown`() {
        val unknownTime = TimetableWidgetCourse(
            name = "时间待确认课程",
            location = null,
            timeLabel = null,
            startMinute = null,
            endMinute = null,
            periodLabel = "第13–14节",
            colorSlot = null,
        )
        val outOfRange = course(start = 23 * 60 + 59, end = 25 * 60)
        listOf(unknownTime, outOfRange).forEach { item ->
            val root = applied(rendered(snapshot = snapshotOf(item)), widthDp = 172, heightDp = 234)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_remaining).visibility)
        }
    }

    @Test
    fun `countdown stays readable in real two by two widths with solid and glass`() {
        val snapshot = snapshotOf(
            course(name = "高等数学", location = "南堂 305", start = 12 * 60, end = 13 * 60),
        )
        val at = today.atTime(12, 30).atZone(zone)
        listOf(112, 236).forEach { widthDp ->
            listOf(WidgetAppearance.SOLID, WidgetAppearance.FROSTED_GLASS).forEach { appearance ->
                val root = applied(
                    rendered(
                        snapshot = snapshot,
                        layout = TimetableWidgetLayout.forSize(widthDp, 233, 1f),
                        appearance = appearance,
                        now = at,
                    ),
                    widthDp = widthDp,
                    heightDp = 233,
                )
                val remaining = root.findViewById<TextView>(R.id.widget_timetable_remaining)
                assertEquals(View.VISIBLE, remaining.visibility)
                assertEquals("剩 30 分钟", remaining.text.toString())
                assertNotNull(remaining.layout)
                assertEquals(0, remaining.layout.getEllipsisCount(0))
                assertTrue(remaining.bottom <= root.height)
                assertEquals(
                    context.getColor(appearance.textColorResource(DesignSystemR.color.widget_accent)),
                    remaining.currentTextColor,
                )
            }
        }
    }

    @Test
    fun `short four by two yields the location line to the countdown`() {
        val layout = TimetableWidgetLayout.forSize(320, 145, 1f)
        assertTrue(layout.tight)
        val root = applied(
            rendered(
                snapshot = snapshotOf(
                    course(name = "高等数学", location = "南堂 305", start = 12 * 60, end = 13 * 60),
                ),
                layout = layout,
                now = today.atTime(12, 30).atZone(zone),
            ),
            widthDp = 320,
            heightDp = 145,
        )
        val remaining = root.findViewById<TextView>(R.id.widget_timetable_remaining)
        assertEquals(View.VISIBLE, remaining.visibility)
        assertEquals("剩 30 分钟", remaining.text.toString())
        assertTrue(remaining.bottom <= root.height)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_location).visibility)
    }

    @Test
    fun `compact failure keeps its hint and leaves the countdown out`() {
        val views = TimetableWidgetRenderer.render(
            context = context,
            snapshot = snapshotOf(
                course(name = "高等数学", location = null, start = 12 * 60, end = 13 * 60),
            ),
            layout = TimetableWidgetLayout.forSize(320, 145, 1f),
            refreshing = false,
            failed = true,
            openIntent = open,
            now = testNow,
        )
        val root = applied(views, widthDp = 320, heightDp = 145)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_timetable_remaining).visibility)
        val failedHint = root.findViewById<TextView>(R.id.widget_timetable_location)
        assertEquals(View.VISIBLE, failedHint.visibility)
        assertEquals("更新失败 · 显示缓存", failedHint.text.toString())
    }
}
