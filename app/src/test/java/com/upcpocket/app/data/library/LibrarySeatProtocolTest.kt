package com.upcpocket.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySeatProtocolTest {
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"
    private val timestamp = 1_700_000_000_000L

    @Test
    fun `公开配置密钥可解出且长度正确`() {
        assertTrue(LibrarySeatProtocol.configKey().isNotEmpty())
    }

    @Test
    fun `HMAC 与页面算法一致`() {
        // 固定向量由页面同一算法计算；不把密钥本身写进测试。
        assertEquals(
            "9c02f7a76fcc185d6e5395a4a4e822c4b8a82b926b88a620d948ddabde804710",
            LibrarySeatProtocol.sign("GET", uuid, timestamp),
        )
        assertTrue(
            LibrarySeatProtocol.signedHeaders("token", "POST", uuid, timestamp)
                .getValue("X-hmac-request-key")
                .matches(Regex("[0-9a-f]{64}")),
        )
    }

    @Test
    fun `登录响应取 seat token`() {
        assertEquals("seat-token", LibrarySeatProtocol.parseToken("""{"status":true,"data":{"token":"seat-token"}}"""))
    }

    @Test
    fun `token 过期被单独识别`() {
        val e = runCatching {
            LibrarySeatProtocol.parseToken("""{"code":12,"message":"token已过期"}""")
        }.exceptionOrNull()
        assertTrue(e is LibrarySessionExpiredException)
    }

    @Test
    fun `字符串状态和字符串 code 也按真实响应处理`() {
        val e = runCatching {
            LibrarySeatProtocol.parseFilters("""{"status":"fail","code":"12","message":"登录失败"}""")
        }.exceptionOrNull()
        assertTrue(e is LibrarySessionExpiredException)
        val fail = runCatching {
            LibrarySeatProtocol.parseFilters("""{"status":"fail","message":"未登录"}""")
        }.exceptionOrNull()
        assertTrue(fail is LibrarySeatException)
    }

    @Test
    fun `业务失败不会被当成空数据`() {
        val e = runCatching {
            LibrarySeatProtocol.parseFilters("""{"status":false,"message":"未登录"}""")
        }.exceptionOrNull()
        assertTrue(e is LibrarySeatException)
        assertTrue(e!!.message!!.contains("未登录"))
    }

    @Test
    fun `筛选条件解析日期和馆区数组`() {
        val filters = LibrarySeatProtocol.parseFilters(
            """{"status":true,"data":{"dates":["2026-10-27","2026-10-28"],
               "buildings":[[1,"总馆"],[3,"古镇口"]]}}""",
        )
        assertEquals(listOf("2026-10-27", "2026-10-28"), filters.dates)
        assertEquals(listOf(LibraryBuilding("1", "总馆"), LibraryBuilding("3", "古镇口")), filters.buildings)
    }

    @Test
    fun `房间按 floor 和 roomId 解析并去重`() {
        val rooms = LibrarySeatProtocol.parseRooms(
            """{"status":true,"data":[
              {"roomId":"r1","room":"A101","floor":1},
              {"roomId":"r2","room":"A102","floor":1},
              {"roomId":"r1","room":"A101","floor":1}
            ]}""",
            LibraryBuilding("1", "总馆"),
        )
        assertEquals(2, rooms.size)
        assertEquals(1, rooms.first().floor)
    }

    @Test
    fun `座位布局用页面数值 key 还原行列`() {
        val layout = LibrarySeatProtocol.parseLayout(
            """{"status":true,"data":{"rows":2,"cols":3,"layout":{
              "1001":{"id":"s1","name":"A1","type":"seat","enabled":true,"status":"FREE"},
              "1002":{"id":"s2","name":"A2","type":"seat","enabled":false,"status":"IN_USE"},
              "1003":{"id":"wall","name":"墙","type":"wall","enabled":true,"status":"FREE"}
            }}}""",
        )
        assertEquals(2, layout.rows)
        assertEquals(3, layout.cols)
        assertEquals(1, layout.seats.first { it.id == "s1" }.row)
        assertEquals(1, layout.seats.first { it.id == "s1" }.col)
        assertEquals(2, layout.seats.first { it.id == "s2" }.col)
        assertTrue(layout.seats.first { it.id == "s1" }.selectable)
        assertFalse(layout.seats.first { it.id == "s2" }.selectable)
        assertFalse(layout.seats.first { it.id == "wall" }.selectable)
    }

    @Test
    fun `开始结束时间和预约记录字段`() {
        val starts = LibrarySeatProtocol.parseStartTimes(
            """{"status":true,"data":{"startTimes":[{"id":480,"value":"08:00"},{"id":-1,"value":"现在"}]}}""",
        )
        assertEquals(listOf("480", "-1"), starts.map { it.id })
        val ends = LibrarySeatProtocol.parseEndTimes(
            """{"status":true,"data":{"endTimes":[{"id":600,"value":"10:00"}]}}""",
        )
        assertEquals("10:00", ends.single().value)
        val reservations = LibrarySeatProtocol.parseReservations(
            """{"status":true,"data":[{"id":"r1","date":"2026-10-27","begin":"08:00","end":"10:00",
               "location":"A101-1","stat":"RESERVE"}]}""",
        )
        assertEquals("已预约", reservations.single().statusLabel)
        assertTrue(reservations.single().cancellable)
    }

    @Test
    fun `历史记录外层 reservations 也兼容`() {
        val list = LibrarySeatProtocol.parseReservations(
            """{"status":true,"data":{"count":1,"reservations":[
              {"id":"r2","date":"2026-10-27","begin":"10:00","end":"12:00","loc":"B201","stat":"CANCEL"}
            ]}}""",
        )
        assertEquals("已取消", list.single().statusLabel)
        assertFalse(list.single().cancellable)
    }

    @Test
    fun `当前没有预约时 data 为 null 不是失败`() {
        val list = LibrarySeatProtocol.parseReservations("""{"code":0,"data":null}""")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `非法布局尺寸不会被解释成空座位`() {
        val e = runCatching {
            LibrarySeatProtocol.parseLayout("""{"status":true,"data":{"rows":0,"cols":1,"layout":{}}}""")
        }.exceptionOrNull()
        assertTrue(e is LibrarySeatException)
    }
}
