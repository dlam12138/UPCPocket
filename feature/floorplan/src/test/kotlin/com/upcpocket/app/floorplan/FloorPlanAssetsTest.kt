package com.upcpocket.app.floorplan

import org.junit.Assert.assertEquals
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

    private fun sheetJson(): String = "[${sheetObject()}]"

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
