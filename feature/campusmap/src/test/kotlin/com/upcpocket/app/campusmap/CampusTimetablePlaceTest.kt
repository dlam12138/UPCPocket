package com.upcpocket.app.campusmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampusTimetablePlaceTest {
    private val south = place("south-building", "南教楼", "南教")
    private val lab = place("foundation-lab-d", "基础实验楼D区", "微", "基础实验楼D", "基础实验楼D座")
    private val tangdaowan = campus(CampusId.TDW, south, lab, place("foundation-lab", "基础实验楼"))
    private val guzhenkou = campus(CampusId.GZK, place("teaching", "综合教学楼", "综教"))

    @Test
    fun recognizesNormalAndSingleCharacterBuildingAbbreviationsInClassrooms() {
        val campuses = listOf(guzhenkou, tangdaowan)
        assertEquals(tangdaowan to south, resolveTimetablePlace("南教110", campuses))
        assertEquals(tangdaowan to lab, resolveTimetablePlace("微601", campuses))
        assertEquals(tangdaowan to lab, resolveTimetablePlace("微（６０１）教室", campuses))
        assertEquals(tangdaowan to lab, resolveTimetablePlace("基础实验楼D601", campuses))
    }

    @Test
    fun doesNotGuessForUnknownRoomsOrSharedAliases() {
        val other = campus(CampusId.GZK, place("other-lab", "另一实验楼", "微"))
        assertNull(resolveTimetablePlace("微601", listOf(tangdaowan, other)))
        assertNull(resolveTimetablePlace("微报告厅", listOf(tangdaowan, guzhenkou)))
        assertNull(resolveTimetablePlace("601", listOf(tangdaowan, guzhenkou)))
    }

    @Test
    fun acceptsTrailingNotesOnlyForKnownNumericRooms() {
        val campuses = listOf(tangdaowan, guzhenkou)
        assertEquals(tangdaowan to lab, resolveTimetablePlace("微601（示例备注）", campuses))
        assertEquals(tangdaowan to lab, resolveTimetablePlace("微（６０１）教室（示例备注）", campuses))
        assertEquals(tangdaowan to lab, resolveTimetablePlace("基础实验楼D座601机房", campuses))
        assertNull(resolveTimetablePlace("微报告厅（示例备注）", campuses))
        assertNull(resolveTimetablePlace("南教（示例备注）", campuses))
        assertNull(resolveTimetablePlace("未名楼601（示例备注）", campuses))
    }

    @Test
    fun retainsConflictingCampusAndVenueNotesAsUnresolved() {
        val campuses = listOf(tangdaowan, guzhenkou)
        assertEquals(tangdaowan to lab, resolveTimetablePlace("微601（唐岛湾校区）", campuses))
        assertNull(resolveTimetablePlace("微601（古镇口校区）", campuses))
        assertNull(resolveTimetablePlace("微601（东营科教园区）", campuses))
        assertNull(resolveTimetablePlace("微601（南教110）", campuses))
        assertNull(resolveTimetablePlace("微601（唐岛湾校区）（古镇口校区）", campuses))
        val other = campus(CampusId.GZK, place("other-lab", "另一实验楼", "微"))
        assertNull(resolveTimetablePlace("微601（示例备注）", listOf(tangdaowan, other)))
    }

    private fun place(id: String, name: String, vararg aliases: String) = CampusPlace(
        id, name, "teaching", aliases.toList(), GeoPoint(120.17, 35.94), "osm", null, null,
    )

    private fun campus(id: CampusId, vararg places: CampusPlace) = CampusMapData(
        id = id,
        name = id.title,
        bounds = MapBounds(120.16, 35.93, 120.18, 35.95),
        geometry = emptyList(),
        places = places.toList(),
        areas = emptyList(),
        networkPoints = emptyList(),
        networkRoads = emptyList(),
    )
}
