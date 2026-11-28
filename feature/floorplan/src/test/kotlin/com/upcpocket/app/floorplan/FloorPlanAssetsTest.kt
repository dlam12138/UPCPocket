package com.upcpocket.app.floorplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FloorPlanAssetsTest {

    @Test
    fun `parses a sheet with printed floor caption and polygons`() {
        val sheets = FloorPlanAssets.parse(FloorPlanBuilding.A, sheetJson())

        assertEquals(1, sheets.size)
        val sheet = sheets.single()
        assertEquals("a-1", sheet.id)
        assertEquals(FloorPlanBuilding.A, sheet.building)
        assertEquals("一层", sheet.level)
        assertNull(sheet.buildingLevel)
        assertEquals("图注一层", sheet.displayLevel)
        assertEquals(3, sheet.sourcePage)
        assertEquals(650, sheet.width)
        assertEquals(420, sheet.height)
        assertEquals(FloorPlanAreaKind.FLOOR, sheet.areas.single().kind)
        assertEquals("A101", sheet.rooms.single().label)
    }

    @Test
    fun `rejects out of bounds geometry and mismatched ids`() {
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.parse(
                FloorPlanBuilding.A,
                sheetJson().replace("[650,420]", "[651,420]"),
            )
        }
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.parse(FloorPlanBuilding.B, sheetJson())
        }
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.parse(
                FloorPlanBuilding.A,
                sheetJson().replace("\"floor\"", "\"corridor\""),
            )
        }
    }

    @Test
    fun `rejects duplicate sheet ids across both buildings`() {
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.parse(
                FloorPlanBuilding.A,
                "[${sheetObject()},${sheetObject()}]",
            )
        }
    }

    @Test
    fun `keeps legacy a and b fixtures compatible and parses shared page five`() {
        val legacy = FloorPlanAssets.catalog(
            rawA = sheetJson(),
            rawB = sheetJson().replace("\"a-1\"", "\"b-1\""),
        )
        assertNull(legacy.sharedLinework)
        assertEquals(listOf("a-1"), legacy.sheetsForCanvas(legacy.sheets.first()).map { it.id })

        val catalog = FloorPlanAssets.catalog(
            rawA = sharedSheetJson("a-3", caption = "三层", buildingLevel = "三层"),
            rawB = sharedSheetJson("b-3", caption = "三层", buildingLevel = "五层"),
            rawPage5 = sharedPageJson(),
        )
        val a3 = catalog.sheets.single { it.id == "a-3" }
        val b3 = catalog.sheets.single { it.id == "b-3" }
        val page5 = requireNotNull(catalog.sharedLinework)
        assertEquals(5, page5.sourcePage)
        assertEquals(listOf("a-3", "b-3"), page5.sheetIds)
        assertEquals(a3.width, page5.width)
        assertEquals(a3.height, page5.height)
        assertEquals("三层", a3.level)
        assertEquals("三层", a3.buildingLevel)
        assertEquals("三层", b3.level)
        assertEquals("五层", b3.buildingLevel)
        assertEquals(listOf("a-3", "b-3"), catalog.sheetsForCanvas(a3).map { it.id })
        assertEquals(listOf("a-3", "b-3"), catalog.sheetsForCanvas(b3).map { it.id })
        assertEquals(catalog.viewportKey(a3), catalog.viewportKey(b3))
    }

    @Test
    fun `rejects shared page with missing member mismatched dimensions or out of bounds contour`() {
        val a3 = sharedSheetJson("a-3", caption = "三层", buildingLevel = "三层")
        val b3 = sharedSheetJson("b-3", caption = "三层", buildingLevel = "五层")
        val b2 = sharedSheetJson("b-2", caption = "二层", buildingLevel = "二层")

        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.catalog(a3, b2, sharedPageJson())
        }
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.catalog(
                a3,
                sharedSheetJson("b-3", caption = "三层", buildingLevel = "五层", width = 651),
                sharedPageJson(),
            )
        }
        assertThrows(FloorPlanDataException::class.java) {
            FloorPlanAssets.catalog(
                a3,
                b3,
                sharedPageJson(contourPoints = "[[0,0],[651,0],[0,420]]"),
            )
        }
    }

    private fun sheetJson(): String = "[${sheetObject()}]"

    private fun sharedSheetJson(
        id: String,
        caption: String,
        buildingLevel: String,
        width: Int = 650,
        height: Int = 420,
    ): String = """
        [{
          "id":"$id",
          "level":"$caption",
          "buildingLevel":"$buildingLevel",
          "sourcePage":5,
          "width":$width,
          "height":$height,
          "areas":[{"kind":"floor","points":[[0,0],[$width,0],[$width,$height],[0,$height]]}],
          "rooms":[{"label":"${id.take(1).uppercase()}300","points":[[10,10],[60,10],[60,60],[10,60]]}]
        }]
    """.trimIndent()

    private fun sharedPageJson(
        sourcePage: Int = 5,
        sheetIds: List<String> = listOf("a-3", "b-3"),
        width: Int = 650,
        height: Int = 420,
        contourPoints: String = "[[0,0],[$width,0],[$width,$height],[0,$height]]",
    ): String = """
        {
          "sourcePage":$sourcePage,
          "sheetIds":[${sheetIds.joinToString { "\"$it\"" }}],
          "width":$width,
          "height":$height,
          "contours":[$contourPoints]
        }
    """.trimIndent()

    private fun sheetObject(): String = """
        {
          "id":"a-1",
          "level":"一层",
          "sourcePage":3,
          "width":650,
          "height":420,
          "areas":[{"kind":"floor","points":[[0,0],[650,0],[650,420],[0,420]]}],
          "rooms":[{"label":"A101","points":[[10,10],[60,10],[60,60],[10,60]]}]
        }
    """.trimIndent()
}
