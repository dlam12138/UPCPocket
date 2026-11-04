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
}
