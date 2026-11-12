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
    fun `zhuoyue filters use names and preserve availability and group totals`() {
        val result = sampleResult(listOf(
            room("opaque-1", "卓越教学楼", true).copy(name = "卓越A233"),
            room("opaque-2", "卓越教学楼", false).copy(name = "卓越 A 234"),
            room("opaque-3", "卓越教学楼", false).copy(name = "卓越B328"),
            room("ZY-A999", "卓越教学楼", true).copy(name = "未知教室"),
            room("opaque-4", "其他楼", true).copy(name = "卓越A235"),
            room("opaque-5", "卓越教学楼", true).copy(name = "卓越AB236"),
        )).copy(query = query.copy(campus = FreeClassroomCampus.GUZHENKOU))

        assertEquals(result, result.filterZhuoyueWing(ZhuoyueWingFilter.ALL))
        val a = result.filterZhuoyueWing(ZhuoyueWingFilter.A)
        assertEquals(listOf("opaque-1", "opaque-2"), a.rooms.map { it.code })
        assertEquals(1, a.availableRooms.size)
        val group = a.displayGroups(onlyAvailable = true).single()
        assertEquals(listOf("opaque-1"), group.rooms.map { it.code })
        assertEquals(2, group.totalCount)
        assertEquals(1, group.freeCount)
        val b = result.filterZhuoyueWing(ZhuoyueWingFilter.B)
        assertEquals(listOf("opaque-3"), b.rooms.map { it.code })
        assertTrue(b.availableRooms.isEmpty())
        assertTrue(b.displayGroups(onlyAvailable = true).isEmpty())
        assertEquals(result.query, a.query)
        assertEquals(result.fetchedAt, a.fetchedAt)
        assertEquals(result.buildings, a.buildings)
    }

    @Test
    fun `wing selection does not filter another campus and can have no matches`() {
        val result = sampleResult(listOf(room("ZY-A999", "卓越教学楼", true)))
        assertEquals(result, result.filterZhuoyueWing(ZhuoyueWingFilter.A))
        assertTrue(result.copy(query = query.copy(campus = FreeClassroomCampus.GUZHENKOU))
            .filterZhuoyueWing(ZhuoyueWingFilter.A).rooms.isEmpty())
    }

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
                room("A101", "A楼", available = true),
                room("A102", "A楼", available = false),
                room("B201", "B楼", available = true),
                room("B202", "B楼", available = true),
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
                room("A101", "A楼", available = true),
                room("A102", "A楼", available = false),
                room("B201", "B楼", available = false),
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
                room("A101", "A楼", available = true),
                room("A102", "A楼", available = true),
            ),
        )
        assertEquals(2, allFree.displayGroups(false).single().freeCount)
        assertEquals(2, allFree.displayGroups(true).single().rooms.size)

        val allOccupied = sampleResult(
            rooms = listOf(
                room("B201", "B楼", available = false),
                room("B202", "B楼", available = false),
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
        assertEquals(0, empty.rooms.count { it.available })
        assertTrue(empty.availableRooms.isEmpty())
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

    private fun room(code: String, building: String, available: Boolean) = FreeClassroomRoom(
        code = code,
        name = code,
        building = building,
        campus = "唐岛湾校区",
        capacity = 60,
        available = available,
    )

    private fun sampleResult(rooms: List<FreeClassroomRoom>) = FreeClassroomResult(
        query = query,
        buildings = listOf("全部", "A楼", "B楼"),
        rooms = rooms,
        fetchedAt = Instant.parse("2026-10-29T05:00:00Z"),
    )
}
