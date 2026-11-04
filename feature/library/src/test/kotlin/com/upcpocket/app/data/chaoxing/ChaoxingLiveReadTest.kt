package com.upcpocket.app.data.chaoxing

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 一次性真实只读契约回放。
 *
 * 默认跳过；只有显式设置 `CHAOXING_COOKIE_FILE`（本机私有临时文件，不提交）
 * 时才用已登录会话请求真实接口。只断言结构/数量，不输出 Cookie、学号或页面正文。
 */
class ChaoxingLiveReadTest {

    @Test
    fun `real read-only seat and reserve responses match the parser contract`() = runBlocking {
        val path = System.getenv("CHAOXING_COOKIE_FILE")
        val file = path?.let(::File)
        assumeTrue("manual live test requires CHAOXING_COOKIE_FILE", file?.isFile == true)
        val cookie = file!!.readText()
        assumeTrue("cookie file is empty", cookie.isNotBlank())
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val repository = ChaoxingRepository()

        val user = repository.identity(cookie)
        assertTrue(user.sno.isNotBlank())

        val seatPage = repository.seatRooms(ChaoxingSeatKind.SEAT, cookie, date)
        assertTrue(seatPage.rooms.isNotEmpty())
        val seatDetail = repository.seatRoomDetail(
            ChaoxingSeatKind.SEAT,
            cookie,
            seatPage.rooms.first().id,
            date,
        )
        assertTrue(seatDetail.timeUnit > 0)
        assertTrue(seatDetail.places.isNotEmpty())
        assertEquals(
            seatDetail.capacity,
            seatDetail.places.map { it.seatNum }.toSet().size,
        )

        val enginePage = repository.seatRooms(ChaoxingSeatKind.SEATENGINE, cookie, date)
        assertTrue(enginePage.rooms.isNotEmpty())

        // reservepc 的稳定 URL 当前会 302 到“未查询到应用”；这不是会话失效，而是
        // 缺少学校业务目录生成的租户上下文。seat/engine 的只读契约仍必须通过。
        val guzhenkou = runCatching {
            repository.reserveRooms(ChaoxingTenant.GUZHENKOU, cookie)
        }
        val qiushi = runCatching {
            repository.reserveRooms(ChaoxingTenant.QIUSHI, cookie)
        }
        listOf(guzhenkou, qiushi).forEach { result ->
            val failure = result.exceptionOrNull()
            if (failure != null) {
                assertTrue(failure is ChaoxingException)
                assertEquals("chaoxing_tenant_context", (failure as ChaoxingException).code)
            } else {
                assertTrue(result.getOrThrow().rooms.isNotEmpty())
            }
        }
    }
}
