package com.upcpocket.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按时段找 `/rest/v2/searchSeats` 的解析契约。
 *
 * 响应形状由 2026-11-07 当前公开前端 + 本人账号最小只读请求核对：
 * `data:{seats:{row*1000+col:seat}, page:n, hasMore:bool}`，seat 与
 * layoutByDate 一致。这里用合成响应钉住「完整区间结果」「静态字段」
 * 「业务/结构失败不伪装成空」等边界。
 */
class LibraryTimeSearchProtocolTest {

    @Test
    fun `完整区间结果按 key 还原行列与静态字段`() {
        val page = LibrarySeatProtocol.parseTimeSearchPage(
            """{"status":true,"code":0,"data":{"seats":{
              "1001":{"id":"s1","name":"A001","type":"seat","enabled":true,"status":"FREE","local":false},
              "1002":{"id":"s2","name":"A002","type":"seat","enabled":true,"status":"IN_USE","local":false}
            },"page":1,"hasMore":true}}""",
            requestedPage = 1,
        )
        assertEquals(2, page.seats.size)
        assertEquals(1, page.seats.first { it.id == "s1" }.row)
        assertEquals(1, page.seats.first { it.id == "s1" }.col)
        assertEquals(2, page.seats.first { it.id == "s2" }.col)
        assertTrue(page.hasMore)
        assertEquals(1, page.page)
        // 当前占用但未来区间可约：静态字段仍可判为真实座位，不能被 status 直接否决。
        assertTrue(page.seats.first { it.id == "s2" }.let { it.isSeat && it.enabled && !it.localOnly })
    }

    @Test
    fun `缺席静态字段不擅自补默认值`() {
        val page = LibrarySeatProtocol.parseTimeSearchPage(
            """{"status":true,"data":{"seats":{
              "2003":{"id":"s9","name":"B009","type":"seat","enabled":true,"status":"FREE"}
            },"page":1,"hasMore":false}}""",
            1,
        )
        val seat = page.seats.single()
        assertEquals("s9", seat.id)
        assertEquals(2, seat.row)
        assertEquals(3, seat.col)
        assertTrue(seat.isSeat)
        assertTrue(seat.enabled)
        assertFalse(seat.localOnly)
        assertFalse(page.hasMore)
    }

    @Test
    fun `seats 数组形状也按来源处理`() {
        val page = LibrarySeatProtocol.parseTimeSearchPage(
            """{"status":true,"data":{"seats":[
              {"id":"a","name":"A","row":3,"col":4,"type":"seat","enabled":true,"status":"FREE"}
            ],"page":2,"hasMore":false}}""",
            2,
        )
        assertEquals(1, page.seats.size)
        assertEquals(3, page.seats.single().row)
        assertEquals(4, page.seats.single().col)
        assertEquals(2, page.page)
    }

    @Test
    fun `业务失败不能当成空结果`() {
        val e = runCatching {
            LibrarySeatProtocol.parseTimeSearchPage(
                """{"status":false,"message":"时间不合法","data":null}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(e is LibrarySeatException)
        assertTrue(e!!.message!!.contains("时间不合法"))
    }

    @Test
    fun `登录失效单独识别`() {
        val e = runCatching {
            LibrarySeatProtocol.parseTimeSearchPage("""{"code":12,"data":null}""", 1)
        }.exceptionOrNull()
        assertTrue(e is LibrarySessionExpiredException)
    }

    @Test
    fun `data 缺少 seats 是协议异常而不是空座位`() {
        val e = runCatching {
            LibrarySeatProtocol.parseTimeSearchPage(
                """{"status":true,"data":{"page":1,"hasMore":false}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(e is LibrarySeatException)
        assertTrue(e!!.message!!.contains("seats"))
    }

    @Test
    fun `hasMore 支持布尔与字符串一`() {
        assertTrue(
            LibrarySeatProtocol.parseTimeSearchPage(
                """{"status":true,"data":{"seats":{},"page":1,"hasMore":"1"}}""", 1,
            ).hasMore,
        )
        assertFalse(
            LibrarySeatProtocol.parseTimeSearchPage(
                """{"status":true,"data":{"seats":{},"page":1,"hasMore":false}}""", 1,
            ).hasMore,
        )
    }
}
