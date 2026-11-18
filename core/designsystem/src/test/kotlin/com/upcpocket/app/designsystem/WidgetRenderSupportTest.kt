package com.upcpocket.app.designsystem

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRenderSupportTest {

    @Test
    fun `only frosted glass remaps secondary and semantic text colors`() {
        assertEquals(
            R.color.widget_frosted_secondary,
            WidgetAppearance.FROSTED_GLASS.textColorResource(R.color.widget_secondary),
        )
        assertEquals(
            R.color.widget_frosted_tertiary,
            WidgetAppearance.FROSTED_GLASS.textColorResource(R.color.widget_tertiary),
        )
        assertEquals(
            R.color.widget_frosted_accent,
            WidgetAppearance.FROSTED_GLASS.textColorResource(R.color.widget_accent),
        )
        assertEquals(
            R.color.widget_frosted_error,
            WidgetAppearance.FROSTED_GLASS.textColorResource(R.color.widget_error),
        )
        assertEquals(
            R.color.widget_primary,
            WidgetAppearance.FROSTED_GLASS.textColorResource(R.color.widget_primary),
        )
        val originalColorResources = listOf(
            R.color.widget_primary,
            R.color.widget_secondary,
            R.color.widget_tertiary,
            R.color.widget_accent,
            R.color.widget_error,
        )
        listOf(WidgetAppearance.SOLID, WidgetAppearance.TRANSLUCENT, WidgetAppearance.GLASS)
            .forEach { appearance ->
                originalColorResources.forEach { colorResource ->
                    assertEquals(colorResource, appearance.textColorResource(colorResource))
                }
            }
    }

    @Test
    fun `unknown widget appearance falls back to the solid launcher surface`() {
        assertEquals(WidgetAppearance.SOLID, WidgetAppearance.fromStoredValue(null))
        assertEquals(WidgetAppearance.SOLID, WidgetAppearance.fromStoredValue("UNKNOWN"))
        assertEquals(WidgetAppearance.SOLID, WidgetAppearance.fromStoredValue("SOLID"))
        assertEquals(WidgetAppearance.TRANSLUCENT, WidgetAppearance.fromStoredValue("TRANSLUCENT"))
        assertEquals(WidgetAppearance.GLASS, WidgetAppearance.fromStoredValue("GLASS"))
        assertEquals(WidgetAppearance.SOLID, WidgetAppearance.fromStoredValue("FROSTED_GLASS"))
    }

    @Test
    fun `query time is one line with day context only when needed`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
                .toInstant()
                .toEpochMilli()

        assertEquals(
            "17:20",
            WidgetRenderSupport.queryTimeShort(
                fetchedAt = at(2026, 10, 29, 17, 20),
                now = at(2026, 10, 29, 20, 0),
                zone = zone,
            ),
        )
        assertEquals(
            "10-29 17:20",
            WidgetRenderSupport.queryTimeShort(
                fetchedAt = at(2026, 10, 29, 17, 20),
                now = at(2026, 10, 30, 8, 0),
                zone = zone,
            ),
        )
        assertEquals(
            "2025-12-31 23:59",
            WidgetRenderSupport.queryTimeShort(
                fetchedAt = at(2025, 12, 31, 23, 59),
                now = at(2026, 1, 1, 0, 5),
                zone = zone,
            ),
        )
    }
}
