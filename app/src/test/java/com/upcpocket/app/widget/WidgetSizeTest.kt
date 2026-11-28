package com.upcpocket.app.widget

import android.content.res.Configuration
import org.junit.Assert.assertEquals
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
    fun `today tomorrow widget uses portrait current bounds`() {
        val size = WidgetUpdates.todayTomorrowWidgetSizeForOrientation(
            minWidth = 180,
            maxWidth = 320,
            minHeight = 180,
            maxHeight = 360,
            orientation = Configuration.ORIENTATION_PORTRAIT,
        )

        assertEquals(180, size.widthDp)
        assertEquals(360, size.heightDp)
    }

    @Test
    fun `today tomorrow widget uses landscape current bounds`() {
        val size = WidgetUpdates.todayTomorrowWidgetSizeForOrientation(
            minWidth = 180,
            maxWidth = 320,
            minHeight = 180,
            maxHeight = 360,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertEquals(320, size.widthDp)
        assertEquals(180, size.heightDp)
    }

    @Test
    fun `today tomorrow widget falls back to the other positive bound`() {
        val portrait = WidgetUpdates.todayTomorrowWidgetSizeForOrientation(
            minWidth = 0,
            maxWidth = 320,
            minHeight = 0,
            maxHeight = 240,
            orientation = Configuration.ORIENTATION_PORTRAIT,
        )
        val landscape = WidgetUpdates.todayTomorrowWidgetSizeForOrientation(
            minWidth = 180,
            maxWidth = 0,
            minHeight = 0,
            maxHeight = 140,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
        )

        assertEquals(320, portrait.widthDp)
        assertEquals(240, portrait.heightDp)
        assertEquals(180, landscape.widthDp)
        assertEquals(140, landscape.heightDp)
    }
}
