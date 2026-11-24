package com.upcpocket.app.floorplan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最终资产到位后的地图验收：A233 必须落在图注「二层」的图层，B328 必须落在
 * 图注「一层」的图层。查找依据是图上完整印出的房号，不按房号推断楼层。
 */
class FloorPlanBundledAssetsTest {

    @Test
    fun `bundled assets locate acceptance rooms and keep duplicate blocks distinct`() {
        val catalog = loadBundled()

        val a233 = catalog.search("卓越 A233")
        assertTrue("A233 应有唯一命中", a233 is FloorPlanSearchResult.Match)
        a233 as FloorPlanSearchResult.Match
        assertEquals(FloorPlanBuilding.A, a233.room.building)
        assertEquals("二层", a233.room.sheet.level)

        val b328 = catalog.search("B328")
        assertTrue("B328 应有唯一命中", b328 is FloorPlanSearchResult.Match)
        b328 as FloorPlanSearchResult.Match
        assertEquals(FloorPlanBuilding.B, b328.room.building)
        assertEquals("一层", b328.room.sheet.level)

        // 五层两块图块都印 A514–A516，必须保留为两处独立候选而不是按 label 去重。
        val a514 = catalog.search("A514")
        assertTrue("A514 应有两处图上候选", a514 is FloorPlanSearchResult.Ambiguous)
        a514 as FloorPlanSearchResult.Ambiguous
        assertEquals(2, a514.matches.size)
        assertEquals(2, a514.matches.map { it.key }.toSet().size)
        assertTrue(a514.matches.all { it.sheet.id == "a-5" && it.sheet.level == "五层" })
    }

    private fun loadBundled(): FloorPlanCatalog {
        val directory = File("src/main/assets/zhuoyue-floor-plans")
        val a = File(directory, "a.json")
        val b = File(directory, "b.json")
        assertTrue("缺少矢量资产 ${a.path}", a.isFile)
        assertTrue("缺少矢量资产 ${b.path}", b.isFile)
        return FloorPlanAssets.catalog(a.readText(), b.readText())
    }
}
