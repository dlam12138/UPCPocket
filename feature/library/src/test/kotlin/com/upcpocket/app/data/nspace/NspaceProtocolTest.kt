package com.upcpocket.app.data.nspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NspaceProtocolTest {

    @Test
    fun `room page keeps server pagination instead of flattening`() {
        val page = NspaceProtocol.parseRoomPage(
            """
            {"status":true,"code":200,"data":{
              "currentPage":2,"pageSize":6,"totalCount":11,"totalPage":2,
              "pageList":[
                {"id":"r1","name":"A301","floor":"3","minUser":2,"maxUser":6,
                 "status":0,"seatOpen":"1","isSign":1,
                 "roomTimeSliceDto":{"openTime":"08:00","closeTime":"22:00"}}
              ]}}
            """.trimIndent(),
        )
        assertEquals(2, page.currentPage)
        assertEquals(2, page.totalPage)
        assertEquals(11, page.totalCount)
        assertEquals(1, page.rooms.size)
        assertEquals("A301", page.rooms.single().name)
        assertEquals("08:00", page.rooms.single().openTime)
    }

    @Test
    fun `empty page is not an authentication failure`() {
        val page = NspaceProtocol.parseRoomPage(
            """{"status":true,"code":200,"data":{"currentPage":1,"totalPage":0,
               "totalCount":0,"pageList":[]}}""",
        )
        assertTrue(page.rooms.isEmpty())
        assertEquals(0, page.totalCount)
    }

    @Test(expected = NspaceSessionExpiredException::class)
    fun `body code 401 is a session failure`() {
        NspaceProtocol.parseVenues("""{"status":false,"code":401,"message":"expired"}""")
    }

    @Test(expected = NspaceException::class)
    fun `missing pageList is a protocol failure not an empty result`() {
        NspaceProtocol.parseRoomPage(
            """{"status":true,"code":200,"data":{"currentPage":1,"totalPage":1}}""",
        )
    }

    @Test
    fun `venue room type and dates parse known array shapes`() {
        assertEquals(
            listOf(NspaceVenue("v1", "唐岛湾")),
            NspaceProtocol.parseVenues(
                """{"status":true,"code":200,"data":[{"id":"v1","name":"唐岛湾"}]}""",
            ),
        )
        assertEquals(
            listOf(NspaceRoomType("t1", "研讨间", true)),
            NspaceProtocol.parseRoomTypes(
                """{"status":true,"code":200,"data":[{"id":"t1","name":"研讨间","status":1}]}""",
            ),
        )
        assertEquals(
            listOf("2026-11-04", "2026-11-05"),
            NspaceProtocol.parseDates(
                """{"status":true,"code":200,"data":["2026-11-04","2026-11-05"]}""",
            ),
        )
    }

    @Test
    fun `my reservations parse page fields without door password`() {
        val page = NspaceProtocol.parseReservations(
            """
            {"status":true,"code":200,"data":{
              "currentPage":1,"totalPage":1,"totalCount":1,
              "pageList":[{"id":"b1","roomName":"A301","onDate":"2026-11-05 00:00:00",
                "begin":480,"end":540,"status":0,"statusDes":"待使用","master":0,
                "password":"do-not-parse"}]}}
            """.trimIndent(),
        )
        assertEquals(1, page.totalCount)
        assertEquals("A301", page.reservations.single().roomName)
        assertEquals(480, page.reservations.single().begin)
        assertEquals("待使用", page.reservations.single().statusDescription)
    }

    @Test
    fun `empty reservation page is valid`() {
        val page = NspaceProtocol.parseReservations(
            """{"status":true,"code":200,"data":{"currentPage":1,"totalPage":0,
               "totalCount":0,"pageList":[]}}""",
        )
        assertTrue(page.reservations.isEmpty())
    }

    @Test(expected = NspaceSessionExpiredException::class)
    fun `reservation list 401 is a session failure`() {
        NspaceProtocol.parseReservations("""{"status":false,"code":401}""")
    }

    @Test
    fun `room detail parses policy slots and disabled intervals`() {
        val detail = NspaceProtocol.parseRoomDetail(
            """
            {"status":true,"code":200,"data":{
              "id":"room-1","name":"A301","minUser":1,"maxUser":4,"seatOpen":"","annex":0,
              "roomTimeSliceDto":{"openTime":480,"closeTime":720,"timeSlice":30,
                "disableTime":[[600,660]]},
              "bookPolicy":{"minTime":60,"maxTime":180}}}
            """.trimIndent(),
        )
        assertEquals(480, detail.openTime)
        assertEquals(30, detail.timeSlice)
        assertEquals(1, detail.disableTimes.size)
        assertEquals(600, detail.disableTimes.single().start)
        assertEquals(180, detail.maxTime)
    }

    @Test
    fun `use types and required form fields parse public frontend shapes`() {
        val types = NspaceProtocol.parseUseTypes(
            """{"status":true,"code":200,"data":[{"id":"1","name":"个人"}]}""",
        )
        assertEquals("个人", types.single().name)
        val fields = NspaceProtocol.parseFormFields(
            """{"status":true,"code":200,"data":[
              {"field":"theme","label":"主题","type":"INPUT","required":0,"value":""},
              {"field":"kind","label":"类型","type":"SELECT","required":1,
               "optionList":[{"name":"讨论","value":"1"}]}]}""",
        )
        assertEquals(2, fields.size)
        assertEquals("theme", fields[0].field)
        assertEquals(true, fields[0].required)
        assertEquals("1", fields[1].options.single().value)
    }

    @Test
    fun `cancel action requires a success envelope`() {
        NspaceProtocol.ensureActionSuccess("""{"status":true,"code":200,"data":null}""")
        try {
            NspaceProtocol.ensureActionSuccess("""{"status":true,"code":500}""")
            throw AssertionError("业务失败不应通过")
        } catch (_: NspaceException) {
            // expected
        }
    }
}
