package com.upcpocket.app.timetable

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableWidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
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

    private fun rendered(snapshot: TimetableWidgetSnapshot): RemoteViews =
        TimetableWidgetRenderer.render(
            context = context,
            snapshot = snapshot,
            showSecondary = false,
            refreshing = false,
            failed = false,
            openIntent = open,
            refreshIntent = refresh,
        )

    @Test
    fun `all real widget states build the same bounded layout without pretending`() {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
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
                listOf(
                    TimetableWidgetCourse(
                        name = "示例课程",
                        location = "B101",
                        timeLabel = "08:00–09:35",
                        startMinute = 8 * 60,
                        endMinute = 9 * 60 + 35,
                        periodLabel = "第1–2节",
                        colorSlot = 0,
                    ),
                ),
                fetchedAtEpochMillis = 1_700_000_000_000,
            ),
        )
        states.forEach { snapshot ->
            val views = rendered(snapshot)
            assertEquals(R.layout.widget_timetable_today, views.layoutId)
        }
    }
}
