package com.upcpocket.app.widget

import android.util.SizeF
import com.upcpocket.app.timetable.TimetableTodayTomorrowWidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本周课表只接受保守尺寸：Launcher 给出横竖屏两套上下界时取较小高度，
 * 否则横屏较矮的组件会被当成高组件，七日行被裁掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WidgetSizeTest {

    @Test
    fun `week widget uses the smaller reported bounds so landscape does not clip rows`() {
        val size = WidgetUpdates.conservativeWidgetSize(
            minWidth = 180,
            maxWidth = 320,
            minHeight = 180,
            maxHeight = 250,
        )

        assertEquals(180, size.widthDp)
        assertEquals(180, size.heightDp)
    }

    @Test
    fun `missing lower bounds fall back to the only available bound`() {
        val size = WidgetUpdates.conservativeWidgetSize(
            minWidth = 0,
            maxWidth = 250,
            minHeight = 0,
            maxHeight = 250,
        )

        assertEquals(250, size.widthDp)
        assertEquals(250, size.heightDp)
    }

    @Test
    fun `unknown options stay zero and are handled by the layout defaults`() {
        val size = WidgetUpdates.conservativeWidgetSize(
            minWidth = 0,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 0,
        )

        assertEquals(0, size.widthDp)
        assertEquals(0, size.heightDp)
    }

    @Test
    fun `today tomorrow widget prefers supplied sizes and removes invalid duplicates`() {
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = listOf(
                SizeF(180f, 140f),
                null,
                SizeF(0f, 140f),
                SizeF(320f, 200f),
                SizeF(180f, 140f),
                SizeF(-1f, 140f),
            ),
            minWidth = 180,
            maxWidth = 320,
            minHeight = 140,
            maxHeight = 200,
        )

        assertEquals(listOf(SizeF(180f, 140f), SizeF(320f, 200f)), sizes)
    }

    @Test
    fun `today tomorrow widget builds portrait and landscape candidates from bounds`() {
        val expected = listOf(SizeF(180f, 300f), SizeF(320f, 110f))
        val fromMissingOption = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = null,
            minWidth = 180,
            maxWidth = 320,
            minHeight = 110,
            maxHeight = 300,
        )
        val fromEmptyOption = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = emptyList(),
            minWidth = 180,
            maxWidth = 320,
            minHeight = 110,
            maxHeight = 300,
        )

        assertEquals(expected, fromMissingOption)
        assertEquals(expected, fromEmptyOption)
    }

    @Test
    fun `today tomorrow widget deduplicates equal or one-sided orientation bounds`() {
        val sameBounds = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = null,
            minWidth = 240,
            maxWidth = 240,
            minHeight = 140,
            maxHeight = 140,
        )
        val oneUsableOrientation = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = null,
            minWidth = 180,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 340,
        )

        assertEquals(listOf(SizeF(240f, 140f)), sameBounds)
        assertEquals(listOf(SizeF(180f, 140f)), oneUsableOrientation)
    }

    @Test
    fun `one supplied size keeps both legacy orientation candidates`() {
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = listOf(SizeF(210f, 150f)),
            minWidth = 180,
            maxWidth = 320,
            minHeight = 110,
            maxHeight = 300,
        )

        assertEquals(
            listOf(
                SizeF(210f, 150f),
                SizeF(180f, 300f),
                SizeF(320f, 110f),
            ),
            sizes,
        )
        assertTrue(
            TimetableTodayTomorrowWidgetLayout.forSize(320, 110, 1f).maxCoursesPerDay <
                TimetableTodayTomorrowWidgetLayout.forSize(180, 300, 1f).maxCoursesPerDay,
        )
    }

    @Test
    fun `single tall reported size gets an additional safe height candidate`() {
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = listOf(SizeF(210f, 300f)),
            minWidth = 0,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 0,
        )

        assertEquals(listOf(SizeF(210f, 300f), SizeF(210f, 140f)), sizes)
        assertTrue(
            TimetableTodayTomorrowWidgetLayout.forSize(210, 140, 1f).maxCoursesPerDay <
                TimetableTodayTomorrowWidgetLayout.forSize(210, 300, 1f).maxCoursesPerDay,
        )
    }

    @Test
    fun `single tall reported size adds safe height when legacy bounds add another tall candidate`() {
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = listOf(SizeF(210f, 300f)),
            minWidth = 180,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 300,
        )

        assertEquals(
            listOf(
                SizeF(210f, 300f),
                SizeF(180f, 300f),
                SizeF(180f, 140f),
            ),
            sizes,
        )
        assertTrue(
            TimetableTodayTomorrowWidgetLayout.forSize(180, 140, 1f).maxCoursesPerDay <
                TimetableTodayTomorrowWidgetLayout.forSize(180, 300, 1f).maxCoursesPerDay,
        )
    }

    @Test
    fun `incomplete height bounds stay conservative`() {
        val cappedMissingMinimum = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = null,
            minWidth = 180,
            maxWidth = 320,
            minHeight = 0,
            maxHeight = 340,
        )
        val keepsKnownMinimum = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = emptyList(),
            minWidth = 180,
            maxWidth = 320,
            minHeight = 120,
            maxHeight = 0,
        )

        assertEquals(
            listOf(SizeF(180f, 140f), SizeF(320f, 140f)),
            cappedMissingMinimum,
        )
        assertEquals(listOf(SizeF(320f, 120f)), keepsKnownMinimum)
    }

    @Test
    fun `today tomorrow widget returns no candidates when every bound is missing`() {
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = null,
            minWidth = 0,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 0,
        )

        assertEquals(emptyList<SizeF>(), sizes)
        // An empty candidate list selects the single RemoteViews path; zero dimensions let
        // forSize use its standard 250×140dp default.
        assertEquals(
            5,
            TimetableTodayTomorrowWidgetLayout.forSize(0, 0, 1f).maxCoursesPerDay,
        )
    }

    @Test
    fun `today tomorrow widget caps responsive options at the platform limit`() {
        val reported = (1..20).map { index ->
            SizeF((100 + index).toFloat(), (120 + index).toFloat())
        }
        val sizes = WidgetUpdates.todayTomorrowWidgetSizeCandidates(
            reportedSizes = reported,
            minWidth = 0,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 0,
        )

        assertEquals(16, sizes.size)
        assertEquals(reported.take(16), sizes)
    }
}
