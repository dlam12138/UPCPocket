package com.upcpocket.app.data.nspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NspaceBookingRulesTest {

    private val detail = NspaceRoomDetail(
        roomId = "room-1",
        name = "A301",
        openTime = 480,
        closeTime = 720,
        timeSlice = 30,
        disableTimes = listOf(NspaceInterval(600, 660)),
        minTime = 60,
        maxTime = 180,
        minUser = 1,
        maxUser = 4,
        seatOpen = "",
        annexRequired = false,
    )

    @Test
    fun `start and end options exclude disabled intervals`() {
        val starts = NspaceBookingRules.startOptions(detail)
        assertTrue(starts.contains(480))
        assertTrue(starts.contains(540))
        assertFalse("包含禁用时段起点", starts.contains(570))
        assertFalse("10:00 起已被占用", starts.contains(600))
        assertEquals(listOf(540, 570, 600), NspaceBookingRules.endOptions(detail, 480))
        assertTrue(NspaceBookingRules.endOptions(detail, 540).contains(600))
        assertFalse(NspaceBookingRules.endOptions(detail, 540).contains(630))
    }

    @Test
    fun `today now is aligned forward and cannot start in the past`() {
        val starts = NspaceBookingRules.startOptions(detail, nowMinutes = 585)
        assertEquals(listOf(660), starts)
    }

    @Test
    fun `time validation enforces policy and disabled intervals`() {
        assertNull(NspaceBookingRules.validate(detail, 480, 540))
        assertNotNull(NspaceBookingRules.validate(detail, 480, 510))
        assertNull(NspaceBookingRules.validate(detail, 480, 570))
        assertNotNull(NspaceBookingRules.validate(detail, 480, 630))
        assertNotNull(NspaceBookingRules.validate(detail, 540, 630))
        assertNotNull(NspaceBookingRules.validate(detail, 450, 540))
    }

    @Test
    fun `unsafe native bookings are explicitly rejected`() {
        assertNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail,
                listOf(field("theme", "INPUT")),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNotNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail.copy(minUser = 2),
                emptyList(),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNotNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail.copy(seatOpen = "OPEN"),
                emptyList(),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNotNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail.copy(annexRequired = true),
                emptyList(),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail,
                listOf(field("team", "CHECKBOX")),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNotNull(
            NspaceBookingRules.nativeEligibilityReason(
                detail,
                listOf(field("team", "DATE")),
                listOf(NspaceUseType("1", "个人")),
            ),
        )
        assertNotNull(
            NspaceBookingRules.nativeEligibilityReason(detail, emptyList(), emptyList()),
        )
    }

    @Test
    fun `theme phone outline stay top level and other fields go to extraField`() {
        val split = NspaceBookingRules.splitValues(
            linkedMapOf(
                "theme" to "讨论",
                "phone" to "synthetic-phone",
                "outline" to "大纲",
                "college" to "计算机",
                "empty" to "",
            ),
        )
        assertEquals("讨论", split.theme)
        assertEquals("synthetic-phone", split.phone)
        assertEquals("大纲", split.outline)
        assertEquals(mapOf("college" to "计算机"), split.extraField)
    }

    @Test
    fun `booking payload uses the public frontend field shape`() {
        val request = NspaceBookingRequest(
            begin = 480,
            end = 540,
            onDate = "2026-11-05",
            roomId = "room-1",
            useType = "1",
            participants = "",
            filePath = "",
            source = "WEB",
            seatNo = "",
            teamId = 0,
            theme = "讨论",
            phone = null,
            outline = "大纲",
            extraField = mapOf("college" to "计算机"),
        )
        val root = Json.parseToJsonElement(NspaceBookingPayload.toJson(request)).jsonObject
        assertEquals(480, root["begin"]!!.jsonPrimitive.content.toInt())
        assertEquals(540, root["end"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-11-05", root["onDate"]!!.jsonPrimitive.content)
        assertEquals("WEB", root["source"]!!.jsonPrimitive.content)
        assertEquals("讨论", root["theme"]!!.jsonPrimitive.content)
        assertEquals("大纲", root["outline"]!!.jsonPrimitive.content)
        assertFalse(root.containsKey("phone"))
        assertEquals(
            "计算机",
            root["extraField"]!!.jsonObject["college"]!!.jsonPrimitive.content,
        )
    }

    private fun field(name: String, type: String) = NspaceFormField(
        field = name,
        label = name,
        type = type,
        required = true,
        value = "",
        options = emptyList(),
    )
}
