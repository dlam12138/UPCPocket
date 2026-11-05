package com.upcpocket.app.data.classroom

import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClassroomProtocolTest {
    private val query = FreeClassroomQuery(
        campus = FreeClassroomCampus.TANGDAOWAN,
        date = LocalDate.of(2026, 10, 27),
        periods = setOf(13, 1, 6),
    )
    private val now = Instant.parse("2026-10-27T06:00:00Z")

    @Test
    fun `form uses observed school array parameters and all thirteen periods`() {
        val form = FreeClassroomProtocol.formBody(query)
        val fields = form.split('&').map { field ->
            field.split('=', limit = 2).map { URLDecoder.decode(it, "UTF-8") }
        }
        assertEquals(listOf("xq", "唐岛湾校区"), fields[0])
        assertEquals(listOf("date", "2026-10-27"), fields[1])
        assertEquals(listOf("lh", "全部"), fields[2])
        assertEquals(listOf("1", "6", "13"), fields.filter { it[0] == "jc[]" }.map { it[1] })
        assertFalse(form.contains("cookie", ignoreCase = true))
    }

    @Test
    fun `only a room explicitly present in the free list is available`() {
        val result = FreeClassroomProtocol.parse(sample, query, now)
        assertEquals(listOf("全部", "东廊"), result.buildings)
        assertEquals(2, result.rooms.size)
        assertTrue(result.rooms.single { it.code == "DL101" }.available)
        assertFalse(result.rooms.single { it.code == "DL102" }.available)
        assertEquals(listOf("DL101"), result.availableRooms.map { it.code })
        assertEquals(184, result.availableRooms.single().capacity)
        assertEquals(query, result.query)
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `empty free list means no room is available`() {
        val result = FreeClassroomProtocol.parse(sample.replace(free, "[]"), query, now)
        assertTrue(result.rooms.isNotEmpty())
        assertTrue(result.rooms.none { it.available })
        assertTrue(result.availableRooms.isEmpty())
    }

    @Test
    fun `missing free list never turns every room free`() {
        assertThrowsIo {
            FreeClassroomProtocol.parse(sample.replace("\"js\":$free,", ""), query, now)
        }
    }

    @Test
    fun `valid empty room directory is distinct from parse failure`() {
        val body = """{"e":0,"d":{"all":[],"js":[],"listLh":[]}}"""
        val result = FreeClassroomProtocol.parse(body, query, now)
        assertTrue(result.rooms.isEmpty())
        assertTrue(result.availableRooms.isEmpty())
        assertEquals(listOf("全部"), result.buildings)
    }

    @Test
    fun `school business error never becomes an empty success`() {
        assertThrowsIo { FreeClassroomProtocol.parse("""{"e":1,"m":"服务暂不可用","d":{}}""", query, now) }
    }

    @Test
    fun `login HTML or changed response format is a failure`() {
        assertThrowsIo { FreeClassroomProtocol.parse("<html><title>登录</title></html>", query, now) }
    }

    @Test
    fun `different campus response is rejected`() {
        assertThrowsIo {
            FreeClassroomProtocol.parse(sample.replace("唐岛湾校区", "古镇口校区"), query, now)
        }
    }

    @Test
    fun `free room outside the directory rejects inconsistent response`() {
        assertThrowsIo {
            FreeClassroomProtocol.parse(
                sample.replace("\"code\":\"DL101\",\"name\":\"东廊101\"", "\"code\":\"DL999\",\"name\":\"东廊999\""),
                query,
                now,
            )
        }
    }

    @Test
    fun `free group outside the directory rejects inconsistent response`() {
        assertThrowsIo {
            FreeClassroomProtocol.parse(
                sample.replace("\"js\":$free,", "\"js\":{\"西廊\":[]},"),
                query,
                now,
            )
        }
    }

    /**
     * 2026-11-05 真实事故回归：卓越 B328 第 1–2 节有课、第 3–5 节空闲。
     * 学校对 `jc=1..5` 返回的 `js` 是“每一节都空闲”的交集，B328 不会命中；
     * 未命中必须显示占用，不能显示绿色空闲。
     */
    @Test
    fun `multi-period room missing from free intersection is occupied`() {
        val multi = query.copy(
            campus = FreeClassroomCampus.GUZHENKOU,
            date = LocalDate.of(2026, 11, 5),
            building = "卓越教学楼",
            periods = setOf(1, 2, 3, 4, 5),
        )
        val body = """{
            "e":0,"m":"操作成功","d":{
                "listLh":[{"name":"全部","id":"全部"},{"name":"卓越教学楼","id":"卓越教学楼"}],
                "js":{"卓越教学楼":[
                    {"name":"卓越A233","code":"ZY-A233","num":"60","jxlm":"卓越教学楼","xqm":"古镇口校区"}
                ]},
                "all":{"卓越教学楼":[
                    {"name":"卓越A233","code":"ZY-A233","num":"60","jxlm":"卓越教学楼","xqm":"古镇口校区"},
                    {"name":"卓越B328","code":"ZY-B328","num":"80","jxlm":"卓越教学楼","xqm":"古镇口校区"}
                ]}
            }
        }"""
        val result = FreeClassroomProtocol.parse(body, multi, now)
        assertTrue(result.rooms.single { it.code == "ZY-A233" }.available)
        assertFalse(result.rooms.single { it.code == "ZY-B328" }.available)
        assertEquals(listOf("ZY-A233"), result.availableRooms.map { it.code })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty selected periods cannot trigger query`() {
        FreeClassroomProtocol.formBody(query.copy(periods = emptySet()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid period fourteen cannot trigger query`() {
        FreeClassroomProtocol.formBody(query.copy(periods = setOf(14)))
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        error("应抛出 IOException")
    }

    companion object {
        // Public classroom directory fields only; no course, student or credential data.
        private const val free = """{"东廊":[{"code":"DL101","name":"东廊101"}]}"""
        private val sample = """{
            "e":0,"m":"操作成功","d":{
                "listLh":[{"name":"全部","id":"全部"},{"name":"东廊","id":"东廊"}],
                "js":$free,
                "all":{"东廊":[
                    {"name":"东廊101","code":"DL101","num":"184","jxlm":"东廊","xqm":"唐岛湾校区"},
                    {"name":"东廊102","code":"DL102","num":"","jxlm":"东廊","xqm":"唐岛湾校区"}
                ]}
            }
        }"""
    }
}
