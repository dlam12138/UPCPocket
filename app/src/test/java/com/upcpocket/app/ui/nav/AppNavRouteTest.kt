package com.upcpocket.app.ui.nav

import com.upcpocket.app.data.prefs.TodoHostPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `learning platform stays a host second level route`() {
        val pagerRoutes = listOf(
            AppRoutes.HOME,
            "timetable.week",
            AppRoutes.SERVICES,
            "focus",
        )

        assertEquals(-1, pageIndexForRoute(pagerRoutes, AppRoutes.LEARNING_PLATFORMS))
        assertEquals("learning-platforms", AppRoutes.LEARNING_PLATFORMS)
    }

    @Test
    fun `both hosts keep four first level entries for either placement`() {
        TodoHostPlacement.entries.forEach { placement ->
            val resolution = resolve(placement)

            assertTrue(resolution.embedTodo)
            assertEquals(placement, resolution.host)
            assertTrue(resolution.canChooseHost)
            assertFalse(resolution.awaitingPreference)
        }
    }

    @Test
    fun `preference read pending still embeds todo when both hosts are available`() {
        val resolution = resolve(preferredHost = null)

        // 一级入口始终四项，深链可独立于偏好立即按稳定路由处理；槽位等偏好到达，
        // 避免先闪在默认页面再跳到另一页。
        assertTrue(resolution.embedTodo)
        assertNull(resolution.host)
        assertTrue(resolution.canChooseHost)
        assertFalse(resolution.awaitingPreference)
    }

    @Test
    fun `single available host embeds only when it is the saved choice`() {
        val timetableOnlySavedTimetable =
            resolve(TodoHostPlacement.TIMETABLE, focusHost = false)
        assertTrue(timetableOnlySavedTimetable.embedTodo)
        assertEquals(TodoHostPlacement.TIMETABLE, timetableOnlySavedTimetable.host)
        assertFalse(timetableOnlySavedTimetable.canChooseHost)
        assertFalse(timetableOnlySavedTimetable.awaitingPreference)

        val focusOnlySavedFocus = resolve(TodoHostPlacement.FOCUS, timetableHost = false)
        assertTrue(focusOnlySavedFocus.embedTodo)
        assertEquals(TodoHostPlacement.FOCUS, focusOnlySavedFocus.host)
        assertFalse(focusOnlySavedFocus.canChooseHost)
        assertFalse(focusOnlySavedFocus.awaitingPreference)
    }

    @Test
    fun `missing selected host keeps the registered todo tab`() {
        // 只剩课表但偏好专注，或只剩专注但偏好课表：都不改挂另一页，
        // 保留独立待办 tab，也不改写用户保存的偏好。
        val timetableOnlySavedFocus =
            resolve(TodoHostPlacement.FOCUS, focusHost = false)
        assertFalse(timetableOnlySavedFocus.embedTodo)
        assertNull(timetableOnlySavedFocus.host)

        val focusOnlySavedTimetable =
            resolve(TodoHostPlacement.TIMETABLE, timetableHost = false)
        assertFalse(focusOnlySavedTimetable.embedTodo)
        assertNull(focusOnlySavedTimetable.host)
    }

    @Test
    fun `single host waits for the preference before deciding tab or second level`() {
        val pendingTimetable = resolve(preferredHost = null, focusHost = false)
        assertFalse(pendingTimetable.embedTodo)
        assertNull(pendingTimetable.host)
        assertTrue(pendingTimetable.awaitingPreference)

        val pendingFocus = resolve(preferredHost = null, timetableHost = false)
        assertFalse(pendingFocus.embedTodo)
        assertNull(pendingFocus.host)
        assertTrue(pendingFocus.awaitingPreference)
    }

    @Test
    fun `missing todo page or store leaves no slot and no placement choice`() {
        listOf(
            resolve(TodoHostPlacement.TIMETABLE, todoPage = false),
            resolve(TodoHostPlacement.TIMETABLE, todoStore = false),
            resolve(TodoHostPlacement.FOCUS, todoStore = false),
        ).forEach { resolution ->
            assertFalse(resolution.embedTodo)
            assertNull(resolution.host)
            assertFalse(resolution.canChooseHost)
            assertFalse(resolution.awaitingPreference)
        }
    }

    @Test
    fun `no available host keeps todo as a standalone first level entry`() {
        val resolution = resolve(
            preferredHost = TodoHostPlacement.FOCUS,
            timetableHost = false,
            focusHost = false,
        )

        assertFalse(resolution.embedTodo)
        assertNull(resolution.host)
        assertFalse(resolution.canChooseHost)
        assertFalse(resolution.awaitingPreference)
    }

    private fun resolve(
        preferredHost: TodoHostPlacement?,
        timetableHost: Boolean = true,
        focusHost: Boolean = true,
        todoPage: Boolean = true,
        todoStore: Boolean = true,
    ): TodoHostResolution = resolveTodoHost(
        pagerRoutes = buildSet {
            if (timetableHost) add("timetable.week")
            if (focusHost) add("focus")
            if (todoPage) add("todo.list")
        },
        timetablePageRegistered = timetableHost,
        focusPageRegistered = focusHost,
        todoPageRegistered = todoPage,
        todoStoreAvailable = todoStore,
        preferredHost = preferredHost,
    )
}
