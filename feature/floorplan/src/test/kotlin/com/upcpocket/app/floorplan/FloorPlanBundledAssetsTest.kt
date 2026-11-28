package com.upcpocket.app.floorplan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包资产验收：原图图注与有来源依据的建筑楼层分别保留，查找只按图上房号。
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
        assertEquals("二层", a233.room.sheet.buildingLevel)
        assertEquals("二层", a233.room.sheet.displayLevel)

        val b328 = catalog.search("B328")
        assertTrue("B328 应有唯一命中", b328 is FloorPlanSearchResult.Match)
        b328 as FloorPlanSearchResult.Match
        assertEquals(FloorPlanBuilding.B, b328.room.building)
        assertEquals("一层", b328.room.sheet.level)
        assertEquals("三层", b328.room.sheet.buildingLevel)
        assertEquals("三层", b328.room.sheet.displayLevel)

        val b245 = catalog.search("B245")
        assertTrue("B245 应有唯一命中", b245 is FloorPlanSearchResult.Match)
        b245 as FloorPlanSearchResult.Match
        assertEquals("地下二层", b245.room.sheet.level)
        assertEquals(null, b245.room.sheet.buildingLevel)
        assertEquals("图注地下二层", b245.room.sheet.displayLevel)

        val a3 = catalog.sheets.single { it.id == "a-3" }
        val b3 = catalog.sheets.single { it.id == "b-3" }
        val shared = requireNotNull(catalog.sharedLinework)
        assertEquals(5, shared.sourcePage)
        assertEquals(listOf("a-3", "b-3"), shared.sheetIds)
        assertEquals(a3.width, shared.width)
        assertEquals(a3.height, shared.height)
        assertEquals(b3.width, shared.width)
        assertEquals(b3.height, shared.height)
        assertEquals("三层", a3.buildingLevel)
        assertEquals("五层", b3.buildingLevel)
        assertEquals(listOf("a-3", "b-3"), catalog.sheetsForCanvas(a3).map { it.id })
        assertEquals(listOf("a-3", "b-3"), catalog.sheetsForCanvas(b3).map { it.id })
        assertEquals(catalog.viewportKey(a3), catalog.viewportKey(b3))
        assertEquals("b-3", catalog.sheetForBuildingSwitch(a3, FloorPlanBuilding.B).id)
        assertEquals("a-3", catalog.sheetForBuildingSwitch(b3, FloorPlanBuilding.A).id)

        // 五层两块图块都印 A514–A516，必须保留为两处独立候选而不是按 label 去重。
        val a514 = catalog.search("A514")
        assertTrue("A514 应有两处图上候选", a514 is FloorPlanSearchResult.Ambiguous)
        a514 as FloorPlanSearchResult.Ambiguous
        assertEquals(2, a514.matches.size)
        assertEquals(2, a514.matches.map { it.key }.toSet().size)
        assertTrue(a514.matches.all {
            it.sheet.id == "a-5" && it.sheet.level == "五层" && it.sheet.buildingLevel == "五层"
        })
    }

    private fun loadBundled(): FloorPlanCatalog {
        val directory = File("src/main/assets/zhuoyue-floor-plans")
        val a = File(directory, "a.json")
        val b = File(directory, "b.json")
        val page5 = File(directory, "page-5.json")
        assertTrue("缺少矢量资产 ${a.path}", a.isFile)
        assertTrue("缺少矢量资产 ${b.path}", b.isFile)
        assertTrue("缺少第 5 页共用线稿 ${page5.path}", page5.isFile)
        return FloorPlanAssets.catalog(a.readText(), b.readText(), page5.readText())
    }
}
