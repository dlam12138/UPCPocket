package com.upcpocket.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavRouteTest {

    @Test
    fun `route selection follows route after tab ordering changes`() {
        val routes = listOf("home", "services", "timetable", "todo", "focus")

        assertEquals(3, pageIndexForRoute(routes, "todo"))
        assertEquals(-1, pageIndexForRoute(routes, "removed-plugin-route"))
    }

    @Test
    fun `page index lookup follows the merged route order`() {
        // 真实 bottomNav 的待办过滤由 HostPluginUiTest 基于已装配插件验证。
        val merged = listOf("home", "timetable.week", "services", "focus")

        assertEquals(1, pageIndexForRoute(merged, "timetable.week"))
        assertEquals(2, pageIndexForRoute(merged, "services"))
        assertEquals(3, pageIndexForRoute(merged, "focus"))
        assertEquals(-1, pageIndexForRoute(merged, "todo.list"))
    }

    @Test
    fun `page index lookup accepts a todo tab fallback list`() {
        val fallback = listOf("home", "services", "todo.list", "focus")

        assertEquals(2, pageIndexForRoute(fallback, "todo.list"))
        assertEquals(-1, pageIndexForRoute(fallback, "timetable.week"))
    }

    @Test
    fun `merge requires timetable page todo page and final store capability`() {
        assertTrue(
            shouldMergeTodoIntoTimetable(
                timetablePageRegistered = true,
                todoPageRegistered = true,
                todoStoreAvailable = true,
            ),
        )
        assertFalse(
            shouldMergeTodoIntoTimetable(
                timetablePageRegistered = false,
                todoPageRegistered = true,
                todoStoreAvailable = true,
            ),
        )
        assertFalse(
            shouldMergeTodoIntoTimetable(
                timetablePageRegistered = true,
                todoPageRegistered = false,
                todoStoreAvailable = true,
            ),
        )
        assertFalse(
            shouldMergeTodoIntoTimetable(
                timetablePageRegistered = true,
                todoPageRegistered = true,
                todoStoreAvailable = false,
            ),
        )
    }
}
