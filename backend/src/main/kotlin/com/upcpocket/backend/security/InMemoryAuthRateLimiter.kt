package com.upcpocket.backend.security

import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Bounded fixed-window limiter keyed independently by blind login tag and trusted socket peer IP. */
@Component
class InMemoryAuthRateLimiter(
    private val clock: Clock,
) {
    private data class Counter(var startedAt: Instant, var count: Int)

    private val lock = Any()
    private val window = Duration.ofMinutes(1)
    private val maxPerLoginTag = 10
    private val maxPerClientIp = 60
    private val maxEntries = 100_000
    private val loginCounters = HashMap<String, Counter>()
    private val ipCounters = HashMap<String, Counter>()

    fun check(loginTag: LoginTag, trustedClientIp: String) = synchronized(lock) {
        val now = clock.instant()
        cleanup(loginCounters, now)
        cleanup(ipCounters, now)
        val tagBytes = loginTag.copyBytes()
        val tagKey = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(tagBytes)
            try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            } finally {
                digest.fill(0)
            }
        } finally {
            tagBytes.fill(0)
        }
        val safeIpKey = trustedClientIp.take(64).ifBlank { "unknown" }
        val tagCounter = consume(loginCounters, tagKey, maxPerLoginTag, now)
        val ipCounter = consume(ipCounters, safeIpKey, maxPerClientIp, now)
        if (!tagCounter.allowed || !ipCounter.allowed) {
            val remaining = maxOf(tagCounter.retryAfterSeconds, ipCounter.retryAfterSeconds, 1L)
            throw ApiException(ApiErrorCode.RATE_LIMITED, remaining)
        }
    }

    private data class ConsumeResult(val allowed: Boolean, val retryAfterSeconds: Long)

    private fun consume(
        counters: HashMap<String, Counter>,
        key: String,
        limit: Int,
        now: Instant,
    ): ConsumeResult {
        var counter = counters[key]
        if (counter == null) {
            if (counters.size >= maxEntries) {
                return ConsumeResult(false, window.seconds.coerceAtLeast(1))
            }
            counter = Counter(now, 0)
            counters[key] = counter
        }
        if (!counter.startedAt.plus(window).isAfter(now)) {
            counter.startedAt = now
            counter.count = 0
        }
        counter.count += 1
        val remaining = Duration.between(now, counter.startedAt.plus(window)).seconds.coerceAtLeast(1)
        return ConsumeResult(counter.count <= limit, remaining)
    }

    private fun cleanup(counters: HashMap<String, Counter>, now: Instant) {
        counters.entries.removeIf { !it.value.startedAt.plus(window).isAfter(now) }
    }
}
