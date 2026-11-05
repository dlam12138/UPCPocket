package com.upcpocket.app.data.chaoxing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingProtocolTest {

    @Test
    fun `identity is parsed from the server rendered userLoginInfo object`() {
        val html = """
            <script>var userLoginInfo = {"userInfo":{"sno":"20260001","uname":"合成"}}||{}</script>
        """.trimIndent()
        assertEquals(ChaoxingUser("20260001"), ChaoxingProtocol.parseIdentity(html))
    }

    @Test
    fun `seat rooms use the selected weekday hours and stable fields`() {
        val body = """
            {"success":true,"data":{"totalRow":1,"totalPage":1,"seatRoomList":[
              {"id":8548,"firstLevelName":"东区图书馆","secondLevelName":"三楼","thirdLevelName":"A区",
               "capacity":48,"curUsedCount":3,
               "seatSpecialTime":{"wedStartTime":"08:00","wedEndTime":"21:30"}}
            ]}}
        """.trimIndent()
        val page = ChaoxingProtocol.parseSeatRooms(body, ChaoxingSeatKind.SEAT, "2026-11-04")
        assertEquals(1, page.rooms.size)
        assertEquals("东区图书馆-三楼-A区", page.rooms.single().name)
        assertEquals(48, page.rooms.single().capacity)
        assertEquals(3, page.rooms.single().usedCount)
        assertEquals(8 * 60, page.rooms.single().startMinute)
        assertEquals(21 * 60 + 30, page.rooms.single().endMinute)
    }

    @Test
    fun `seatengine rooms use seatEngineSpecialTime`() {
        val body = """
            {"success":true,"data":{"totalRow":1,"totalPage":1,"seatRoomList":[
              {"id":12814,"firstLevelName":"求是图书馆","secondLevelName":"二楼","thirdLevelName":"阶梯学习区",
               "capacity":30,"curUsedCount":5,
               "seatEngineSpecialTime":{"monStartTime":"08:00","monEndTime":"22:00"}}
            ]}}
        """.trimIndent()
        val page = ChaoxingProtocol.parseSeatRooms(
            body,
            ChaoxingSeatKind.SEATENGINE,
            "2026-11-02",
        )
        assertEquals(8 * 60, page.rooms.single().startMinute)
        assertEquals(22 * 60, page.rooms.single().endMinute)
    }

    @Test
    fun `room detail reads timeUnit and common hours`() {
        val body = """
            {"success":true,"data":{
              "seatConfig":{"timeUnit":30,"commonTimeConfig":{"wedStartTime":"08:00","wedEndTime":"21:30"}},
              "seatRoom":{"id":8548,"firstLevelName":"东区图书馆","secondLevelName":"三楼",
                          "thirdLevelName":"A区","capacity":48}
            }}
        """.trimIndent()
        val detail = ChaoxingProtocol.parseSeatRoomDetail(body, "2026-11-04", 8548)
        assertEquals(30, detail.timeUnit)
        assertEquals(8 * 60, detail.startMinute)
        assertEquals(21 * 60 + 30, detail.endMinute)
        assertEquals(48, detail.capacity)
    }

    @Test
    fun `seat map and used seat numbers are typed and ignore uid`() {
        val map = """
            {"success":true,"data":[
              {"seatNum":"001","xleft":166,"ytop":131,"width":75,"height":75,"rotate":270}
            ]}
        """.trimIndent()
        val places = ChaoxingProtocol.parseSeatPlaces(map)
        assertEquals("001", places.single().seatNum)
        assertEquals(166f, places.single().x)

        val used = """
            {"success":true,"data":{"seatReserves":[
              {"seatNum":"001","uid":123},{"seatNum":"002","uid":456}
            ]}}
        """.trimIndent()
        assertEquals(setOf("001", "002"), ChaoxingProtocol.parseUsedSeatNums(used))
    }

    @Test
    fun `real seat map floats keep their canvas geometry and normalize rotation`() {
        // 响应结构来自真实 getdrawseat（坐标/尺寸/旋转为浮点，rotate 会返回 630）；
        // 座位号与 id 已替换为合成值，不含任何真实学生或预约数据。
        val body = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("chaoxing/getdrawseat-synthetic.json"),
        ).bufferedReader().use { it.readText() }

        val places = ChaoxingProtocol.parseSeatPlaces(body)

        assertEquals(6, places.size)
        val first = places.first()
        assertEquals("A01", first.seatNum)
        assertEquals(166f, first.x)
        assertEquals(131f, first.y)
        assertEquals(75f, first.width)
        assertEquals(75f, first.height)
        assertEquals(270f, first.rotate)
        assertEquals(270f, places.first { it.seatNum == "A04" }.rotate)
        assertEquals(2736f, places.last().x)
        assertTrue(places.all { it.width > 0f && it.height > 0f })
    }

    @Test
    fun `seat map with only invalid geometry fails instead of rendering a blank map`() {
        val body = """
            {"success":true,"data":[
              {"seatNum":"001","xleft":166.0,"ytop":131.0,"width":0.0,"height":75.0,"rotate":0}
            ]}
        """.trimIndent()
        val failure = runCatching { ChaoxingProtocol.parseSeatPlaces(body) }.exceptionOrNull()
        assertTrue(failure is ChaoxingException)
        assertEquals("chaoxing_protocol", (failure as ChaoxingException).code)
    }

    @Test
    fun `empty seat map array stays an explicit empty map`() {
        val places = ChaoxingProtocol.parseSeatPlaces("""{"success":true,"data":[]}""")
        assertTrue(places.isEmpty())
    }

    @Test
    fun `used times are converted to Beijing minutes`() {
        val start = Instant.parse("2026-11-04T02:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-04T03:00:00Z").toEpochMilli()
        val body = """{"success":true,"data":[[$start,$end]]}"""
        val intervals = ChaoxingProtocol.parseUsedTimes(body)
        assertEquals(ChaoxingTimeInterval(10 * 60, 11 * 60), intervals.single())
    }

    @Test
    fun `business failure is not treated as an empty list`() {
        val failure = runCatching {
            ChaoxingProtocol.parseSeatRooms(
                """{"success":false,"msg":"未查询到应用"}""",
                ChaoxingSeatKind.SEAT,
                "2026-11-04",
            )
        }.exceptionOrNull()
        assertTrue(failure is ChaoxingException)
    }
}
