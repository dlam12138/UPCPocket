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
    fun `occupied room code is excluded even if array order differs`() {
        val result = FreeClassroomProtocol.parse(sample, query, now)
        assertEquals(listOf("全部", "东廊"), result.buildings)
        assertEquals(2, result.rooms.size)
        assertTrue(result.rooms.single { it.code == "DL102" }.occupied)
        assertFalse(result.rooms.single { it.code == "DL101" }.occupied)
        assertEquals(listOf("DL101"), result.availableRooms.map { it.code })
        assertEquals(184, result.availableRooms.single().capacity)
        assertEquals(query, result.query)
        assertEquals(now, result.fetchedAt)
    }

    @Test
    fun `valid empty occupancy array means no occupied rooms`() {
        val result = FreeClassroomProtocol.parse(sample.replace(occupied, "[]"), query, now)
        assertEquals(2, result.availableRooms.size)
    }

    @Test
    fun `valid empty room directory is distinct from parse failure`() {
        val body = """{"e":0,"d":{"all":[],"js":[],"listLh":[]}}"""
        val result = FreeClassroomProtocol.parse(body, query, now)
        assertTrue(result.rooms.isEmpty())
        assertEquals(listOf("全部"), result.buildings)
    }

    @Test(expected = IOException::class)
    fun `missing occupancy never turns every room free`() {
        FreeClassroomProtocol.parse(sample.replace("\"js\":$occupied,", ""), query, now)
    }

    @Test(expected = IOException::class)
    fun `school business error never becomes an empty success`() {
        FreeClassroomProtocol.parse("""{"e":1,"m":"服务暂不可用","d":{}}""", query, now)
    }

    @Test(expected = IOException::class)
    fun `login HTML or changed response format is a failure`() {
        FreeClassroomProtocol.parse("<html><title>登录</title></html>", query, now)
    }

    @Test(expected = IOException::class)
    fun `different campus response is rejected`() {
        FreeClassroomProtocol.parse(sample.replace("唐岛湾校区", "古镇口校区"), query, now)
    }

    @Test(expected = IOException::class)
    fun `unknown occupied room rejects inconsistent response`() {
        FreeClassroomProtocol.parse(sample.replace("\"code\":\"DL102\",\"name\":\"东廊102\"", "\"code\":\"DL999\",\"name\":\"东廊999\""), query, now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty selected periods cannot trigger query`() {
        FreeClassroomProtocol.formBody(query.copy(periods = emptySet()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid period fourteen cannot trigger query`() {
        FreeClassroomProtocol.formBody(query.copy(periods = setOf(14)))
    }

    companion object {
        // Public classroom directory fields only; no course, student or credential data.
        private const val occupied = """{"东廊":[{"code":"DL102","name":"东廊102"}]}"""
        private val sample = """{
            "e":0,"m":"操作成功","d":{
                "listLh":[{"name":"全部","id":"全部"},{"name":"东廊","id":"东廊"}],
                "js":$occupied,
                "all":{"东廊":[
                    {"name":"东廊101","code":"DL101","num":"184","jxlm":"东廊","xqm":"唐岛湾校区"},
                    {"name":"东廊102","code":"DL102","num":"","jxlm":"东廊","xqm":"唐岛湾校区"}
                ]}
            }
        }"""
    }
}
