package com.upcpocket.app.data.classroom

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClassroomPresentationTest {

    private val query = FreeClassroomQuery(
        campus = FreeClassroomCampus.TANGDAOWAN,
        date = LocalDate.of(2026, 10, 29),
        building = "全部",
        periods = ClassroomPeriodGroup.MORNING.periods,
    )

    @Test
    fun `official jiedate groups are five four four`() {
        assertEquals(setOf(1, 2, 3, 4, 5), ClassroomPeriodGroup.MORNING.periods)
        assertEquals(setOf(6, 7, 8, 9), ClassroomPeriodGroup.AFTERNOON.periods)
        assertEquals(setOf(10, 11, 12, 13), ClassroomPeriodGroup.EVENING.periods)
    }

    @Test
    fun `group toggle removes only when the whole group is selected`() {
        assertEquals(
            ClassroomPeriodGroup.MORNING.periods,
            toggleGroup(emptySet(), ClassroomPeriodGroup.MORNING),
        )
        assertEquals(
            ClassroomPeriodGroup.MORNING.periods + ClassroomPeriodGroup.AFTERNOON.periods,
            toggleGroup(
                ClassroomPeriodGroup.MORNING.periods,
                ClassroomPeriodGroup.AFTERNOON,
            ),
        )
        // 部分选中时点击补齐整组，而不是意外取消。
        assertEquals(
            ClassroomPeriodGroup.MORNING.periods,
            toggleGroup(setOf(1, 2), ClassroomPeriodGroup.MORNING),
        )
        // 整组全选时点击才移除整组，其它组不受影响。
        assertEquals(
            ClassroomPeriodGroup.AFTERNOON.periods,
            toggleGroup(
                ClassroomPeriodGroup.MORNING.periods + ClassroomPeriodGroup.AFTERNOON.periods,
                ClassroomPeriodGroup.MORNING,
            ),
        )
    }

    @Test
    fun `group selection state distinguishes none partial and all`() {
        assertEquals(GroupSelectionState.NONE, groupSelectionState(emptySet(), ClassroomPeriodGroup.MORNING))
        assertEquals(GroupSelectionState.PARTIAL, groupSelectionState(setOf(1, 3), ClassroomPeriodGroup.MORNING))
        assertEquals(
            GroupSelectionState.ALL,
            groupSelectionState(ClassroomPeriodGroup.MORNING.periods, ClassroomPeriodGroup.MORNING),
        )
    }

    @Test
    fun `periods compress into readable ranges`() {
        assertEquals("", formatPeriods(emptySet()))
        assertEquals("1–5", formatPeriods((1..5).toSet()))
        assertEquals("1–3、5、10", formatPeriods(setOf(1, 2, 3, 5, 10)))
        assertEquals("1、3、5", formatPeriods(setOf(1, 3, 5)))
    }

    @Test
    fun `display groups keep free and occupied rooms with exact counts`() {
        val all = sampleResult(
            rooms = listOf(
                room("A101", "A楼", occupied = false),
                room("A102", "A楼", occupied = true),
                room("B201", "B楼", occupied = false),
                room("B202", "B楼", occupied = false),
            ),
        )
        val groups = all.displayGroups(onlyAvailable = false)
        assertEquals(listOf("A楼", "B楼"), groups.map { it.building })
        assertEquals(2, groups[0].rooms.size)
        assertEquals(1, groups[0].freeCount)
        assertEquals(2, groups[0].totalCount)
        assertEquals(2, groups[1].freeCount)
        assertEquals(2, groups[1].totalCount)
    }

    @Test
    fun `only available filters cells but keeps group totals and drops empty groups`() {
        val all = sampleResult(
            rooms = listOf(
                room("A101", "A楼", occupied = false),
                room("A102", "A楼", occupied = true),
                room("B201", "B楼", occupied = true),
            ),
        )
        val groups = all.displayGroups(onlyAvailable = true)
        assertEquals(listOf("A楼"), groups.map { it.building })
        assertEquals(listOf("A101"), groups.single().rooms.map { it.code })
        assertEquals(1, groups.single().freeCount)
        assertEquals(2, groups.single().totalCount)
    }

    @Test
    fun `all free keeps every room and zero free is displayed as all occupied`() {
        val allFree = sampleResult(
            rooms = listOf(
                room("A101", "A楼", occupied = false),
                room("A102", "A楼", occupied = false),
            ),
        )
        assertEquals(2, allFree.displayGroups(false).single().freeCount)
        assertEquals(2, allFree.displayGroups(true).single().rooms.size)

        val allOccupied = sampleResult(
            rooms = listOf(
                room("B201", "B楼", occupied = true),
                room("B202", "B楼", occupied = true),
            ),
        )
        val occupiedGroups = allOccupied.displayGroups(false).single()
        assertEquals(0, occupiedGroups.freeCount)
        assertEquals(2, occupiedGroups.rooms.size)
        assertTrue(allOccupied.displayGroups(true).isEmpty())
    }

    @Test
    fun `empty room directory is not an all free result`() {
        val empty = sampleResult(rooms = emptyList())
        assertTrue(empty.rooms.isEmpty())
        assertEquals(0, empty.rooms.count { !it.occupied })
        assertTrue(empty.displayGroups(onlyAvailable = false).isEmpty())
    }

    @Test
    fun `result only matches the exact current query`() {
        val result = sampleResult(rooms = emptyList())
        assertTrue(result.matches(query))
        assertFalse(result.matches(query.copy(date = query.date.plusDays(1))))
        assertFalse(result.matches(query.copy(periods = setOf(1, 6))))
        assertFalse(result.matches(query.copy(building = "A楼")))
        assertFalse(result.matches(null))
    }

    private fun room(code: String, building: String, occupied: Boolean) = FreeClassroomRoom(
        code = code,
        name = code,
        building = building,
        campus = "唐岛湾校区",
        capacity = 60,
        occupied = occupied,
    )

    private fun sampleResult(rooms: List<FreeClassroomRoom>) = FreeClassroomResult(
        query = query,
        buildings = listOf("全部", "A楼", "B楼"),
        rooms = rooms,
        fetchedAt = Instant.parse("2026-10-29T05:00:00Z"),
    )
}
