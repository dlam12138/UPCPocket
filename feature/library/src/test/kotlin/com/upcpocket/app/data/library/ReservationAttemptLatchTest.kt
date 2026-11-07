package com.upcpocket.app.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话范围预约防重闩：同一个 [LibraryReservationRequest] 在当前会话只能登记一次；
 * 换号/重新登录（会话代次变化）后清空。Repository 与可控 fake 共用此类语义。
 */
class ReservationAttemptLatchTest {

    private fun key(seatId: String = "s1", date: String = "2026-11-07") =
        LibraryReservationRequest(
            system = LibraryReservationRequest.SYSTEM_LIBSEAT,
            userId = "user-1", date = date, seatId = seatId,
            startId = "840", endId = "960",
        )

    @Test
    fun `同一会话同一 key 首次放行第二次拒绝`() {
        val latch = ReservationAttemptLatch()
        assertTrue(latch.registerOrReject(key(), 1L))
        assertFalse(latch.registerOrReject(key(), 1L))
        assertTrue(latch.hasAttempted(key(), 1L))
    }

    @Test
    fun `不同座位日期或时间 id 是不同请求`() {
        val latch = ReservationAttemptLatch()
        assertTrue(latch.registerOrReject(key("s1"), 1L))
        assertTrue(latch.registerOrReject(key("s2"), 1L))
        assertTrue(latch.registerOrReject(key("s1", "2026-11-08"), 1L))
        assertTrue(latch.registerOrReject(key().copy(endId = "1020"), 1L))
    }

    @Test
    fun `会话代次变化后同一 key 可重新登记`() {
        val latch = ReservationAttemptLatch()
        assertTrue(latch.registerOrReject(key(), 1L))
        assertFalse(latch.registerOrReject(key(), 1L))
        // 换号 / 退出 / 重新登录：代次递增，旧登记整体失效。
        assertTrue(latch.registerOrReject(key(), 2L))
        assertFalse(latch.hasAttempted(key(), 1L))
    }

    @Test
    fun `NOT_SENT 不登记不影响后续正常提交`() {
        // 模拟 POST 前验证码：不调用 register，后续真实提交仍可登记一次。
        val latch = ReservationAttemptLatch()
        // (NOT_SENT 由调用方在 register 之前返回，这里验证 latch 未被占用)
        assertFalse(latch.hasAttempted(key(), 1L))
        assertTrue(latch.registerOrReject(key(), 1L))
    }
}
