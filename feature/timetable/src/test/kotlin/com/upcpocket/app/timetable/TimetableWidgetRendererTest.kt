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
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val open = PendingIntent.getActivity(
        context,
        1,
        Intent(context, Activity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
    private val refresh = PendingIntent.getBroadcast(
        context,
        2,
        Intent("test.refresh"),
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
    ): RemoteViews = TimetableWidgetRenderer.render(
        context = context,
        snapshot = snapshot,
        layout = layout,
        refreshing = false,
        failed = false,
        openIntent = open,
        refreshIntent = refresh,
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
        val refreshView = root.findViewById<View>(R.id.widget_timetable_refresh)
        val minTouch = (44 * context.resources.displayMetrics.density).toInt()
        assertTrue(refreshView.layoutParams.width >= minTouch)
        assertTrue(refreshView.layoutParams.height >= minTouch)
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
}
