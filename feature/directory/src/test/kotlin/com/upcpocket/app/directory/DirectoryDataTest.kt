package com.upcpocket.app.directory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryDataTest {
    private val directory = DirectoryData.parse(File("src/main/assets/campus_directory.json").readText())

    @Test
    fun collegeAliasReturnsTheUnitBeforeRelatedOffices() {
        val results = directory.search("机电", DirectoryCampus.GUZHENKOU)

        assertEquals("机电工程学院", results.first().unit.name)
        assertNull(results.first().office)
        assertTrue(results.any { it.office?.address == "惟真楼403" })
        assertTrue(directory.search("机电", DirectoryCampus.TANGDAOWAN).isEmpty())
        assertEquals("computing", directory.search("青岛软件学院", null).first().unit.id)
        assertEquals("computing", directory.search("计算机科学与技术学院", null).first().unit.id)
    }

    @Test
    fun searchCombinesOfficeFieldsAndNormalizesPhoneFormatting() {
        val studentRecords = directory.search("学籍", DirectoryCampus.TANGDAOWAN).first().office!!
        val finance = directory.search("０５３２ ８６９８１６００", null).single().office!!
        val chemistry = directory.search("化工 644", DirectoryCampus.GUZHENKOU).single().office!!

        assertEquals("academic-affairs", studentRecords.unitId)
        assertEquals("行政办公楼206", finance.address)
        assertEquals("admin", finance.mapTarget?.placeId)
        assertEquals("惟实楼644", chemistry.address)
        assertTrue(chemistry.phones.isEmpty())
        assertNull(chemistry.mapTarget)
    }

    @Test
    fun campusesKeepTheirOwnWindowsAndNeverTreatMissingDetailsAsPlaceholders() {
        val westAid = directory.offices("student-affairs", DirectoryCampus.GUZHENKOU).single()
        val employment = directory.offices("employment", DirectoryCampus.GUZHENKOU).single()

        assertEquals("西区", westAid.region)
        assertEquals("osm-w1510504265", westAid.mapTarget?.placeId)
        assertEquals("", employment.address)
        assertNull(employment.mapTarget)
        assertEquals(setOf("0532-86981075", "0532-86981076"), employment.phones.map { it.number }.toSet())
        assertTrue(directory.search("86981060", null).isEmpty())
        assertFalse(directory.units(null, colleges = true).any { it.id == "physical-education" })
        assertTrue(directory.units(null, colleges = false).any { it.id == "physical-education" })
    }
}
