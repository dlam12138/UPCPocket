package com.upcpocket.backend.security

import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class InMemoryAuthRateLimiterTest {
    @Test
    fun `limit is independently applied to a blind login tag and client IP`() {
        val clock = MutableClock(Instant.parse("2026-11-28T00:00:00Z"))
        val limiter = InMemoryAuthRateLimiter(clock)
        val tag = LoginTag(ByteArray(32) { 9 })

        repeat(10) { limiter.check(tag, "192.0.2.1") }
        val rejected = assertThrows(ApiException::class.java) { limiter.check(tag, "192.0.2.1") }
        assertEquals(ApiErrorCode.RATE_LIMITED, rejected.code)
        assertEquals(60L, rejected.retryAfterSeconds)

        clock.advance(Duration.ofSeconds(60))
        limiter.check(tag, "192.0.2.1")
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
    }
}
