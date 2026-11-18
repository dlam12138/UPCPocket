package com.upcpocket.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavRouteTest {

    @Test
    fun `route selection follows route after tab ordering changes`() {
        val routes = listOf("home", "services", "timetable", "todo", "focus")

        assertEquals(3, pageIndexForRoute(routes, "todo"))
        assertEquals(-1, pageIndexForRoute(routes, "removed-plugin-route"))
    }
}
