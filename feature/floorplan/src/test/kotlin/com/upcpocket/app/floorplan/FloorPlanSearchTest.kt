package com.upcpocket.app.floorplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanSearchTest {

    @Test
    fun `normalizes fullwidth lowercase internal whitespace and a printed prefix`() {
        // 图面标签保留源文全文；搜索索引使用规范化后的完整房号。
        val fixtureLabel = "Ａ２３３ – Ａ２３５"
        val catalog = catalog(
            aRooms = listOf(room(fixtureLabel, x = 10)),
            bRooms = listOf(room("B328", x = 200)),
        )

        for (query in listOf("A233", "a233", "ａ２３３", "A 2 3 3", "卓越 A233", "卓越ａ２３３")) {
            val result = catalog.search(query)
            assertTrue("$query should locate A233", result is FloorPlanSearchResult.Match)
            result as FloorPlanSearchResult.Match
            assertEquals(FloorPlanBuilding.A, result.room.building)
            assertEquals(fixtureLabel, result.room.room.label)
        }
        assertEquals(listOf("A233", "A235"), FloorPlanSearch.printedRoomNumbers(fixtureLabel))
    }

    @Test
    fun `does not expand merged ranges or shorten printed room numbers`() {
        val catalog = catalog(
            aRooms = listOf(room("A233–A235")),
            bRooms = listOf(room("B328", x = 200)),
        )

        assertTrue(catalog.search("A235") is FloorPlanSearchResult.Match)
        for (query in listOf("A234", "A23", "A2333", "B32")) {
            assertTrue("$query must not locate a room", catalog.search(query) is FloorPlanSearchResult.NotFound)
        }
    }

    @Test
    fun `searches both buildings across all sheets and keeps the printed floor caption`() {
        val catalog = FloorPlanCatalog(
            listOf(
                sheet("a-1", FloorPlanBuilding.A, "一层", 3, listOf(room("A101", x = 10))),
                sheet("a-2", FloorPlanBuilding.A, "二层", 4, listOf(room("A233–A235", x = 100))),
                sheet("b-3", FloorPlanBuilding.B, "一层", 3, listOf(room("B328", x = 300))),
            ),
        )

        val a233 = catalog.search("卓越 A233") as FloorPlanSearchResult.Match
        assertEquals(FloorPlanBuilding.A, a233.room.building)
        assertEquals("二层", a233.room.sheet.level)
        assertEquals(4, a233.room.sheet.sourcePage)

        val b328 = catalog.search("b３２８") as FloorPlanSearchResult.Match
        assertEquals(FloorPlanBuilding.B, b328.room.building)
        assertEquals("一层", b328.room.sheet.level)
        assertEquals(3, b328.room.sheet.sourcePage)
    }

    @Test
    fun `duplicate printed labels return distinct room locations instead of one identity`() {
        val catalog = FloorPlanCatalog(
            listOf(
                sheet(
                    "a-5",
                    FloorPlanBuilding.A,
                    "五层",
                    7,
                    listOf(
                        room("A514–A516", x = 300),
                        room("A514–A516", x = 40),
                    ),
                ),
            ),
        )

        val result = catalog.search("A514")
        assertTrue(result is FloorPlanSearchResult.Ambiguous)
        result as FloorPlanSearchResult.Ambiguous
        assertEquals(2, result.matches.size)
        assertEquals(2, result.matches.map { it.key }.toSet().size)
        assertEquals(setOf(0, 1), result.matches.map { it.roomIndex }.toSet())
        assertTrue(result.matches.all { it.sheet.id == "a-5" })
    }

    @Test
    fun `a query containing an unknown printed number does not silently use the other one`() {
        val catalog = catalog(
            aRooms = listOf(room("A233–A235")),
            bRooms = listOf(room("B328", x = 200)),
        )

        assertTrue(catalog.search("A233、B999") is FloorPlanSearchResult.NotFound)
        assertTrue(catalog.search("") is FloorPlanSearchResult.Empty)
        assertTrue(catalog.search("卓越楼") is FloorPlanSearchResult.NotFound)
    }

    private fun catalog(
        aRooms: List<FloorPlanRoom>,
        bRooms: List<FloorPlanRoom>,
    ): FloorPlanCatalog = FloorPlanCatalog(
        listOf(
            sheet("a-2", FloorPlanBuilding.A, "二层", 4, aRooms),
            sheet("b-3", FloorPlanBuilding.B, "一层", 3, bRooms),
        ),
    )

    private fun sheet(
        id: String,
        building: FloorPlanBuilding,
        level: String,
        page: Int,
        rooms: List<FloorPlanRoom>,
    ): FloorPlanSheet = FloorPlanSheet(
        id = id,
        building = building,
        level = level,
        sourcePage = page,
        width = 650,
        height = 420,
        areas = listOf(FloorPlanArea(FloorPlanAreaKind.FLOOR, rect(0, 0, 650, 420))),
        rooms = rooms,
    )

    private fun room(
        label: String,
        x: Int = 10,
        y: Int = 10,
    ): FloorPlanRoom = FloorPlanRoom(label, rect(x, y, 50, 40))

    private fun rect(x: Int, y: Int, width: Int, height: Int): List<FloorPlanPoint> = listOf(
        FloorPlanPoint(x, y),
        FloorPlanPoint(x + width, y),
        FloorPlanPoint(x + width, y + height),
        FloorPlanPoint(x, y + height),
    )
}
