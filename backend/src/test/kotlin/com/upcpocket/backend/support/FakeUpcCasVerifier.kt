package com.upcpocket.backend.support

import com.upcpocket.backend.contract.CasVerificationResult
import com.upcpocket.backend.contract.UpcCasVerifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

enum class FakeCasOutcome {
    VERIFIED,
    INVALID_CREDENTIALS,
    INTERACTIVE_CHALLENGE,
    UPSTREAM_UNAVAILABLE,
}

class FakeUpcCasVerifier : UpcCasVerifier {
    private val outcomes = ConcurrentLinkedQueue<FakeCasOutcome>()
    val calls = AtomicInteger()
    @Volatile
    var configured: Boolean = true

    fun enqueue(vararg next: FakeCasOutcome) {
        next.forEach(outcomes::add)
    }

    fun reset() {
        outcomes.clear()
        calls.set(0)
        configured = true
    }

    override fun verify(loginName: CharArray, password: CharArray): CasVerificationResult {
        calls.incrementAndGet()
        return when (outcomes.poll() ?: FakeCasOutcome.VERIFIED) {
            FakeCasOutcome.VERIFIED -> CasVerificationResult.Verified(loginName.copyOf())
            FakeCasOutcome.INVALID_CREDENTIALS -> CasVerificationResult.InvalidCredentials
            FakeCasOutcome.INTERACTIVE_CHALLENGE -> CasVerificationResult.InteractiveChallenge
            FakeCasOutcome.UPSTREAM_UNAVAILABLE -> CasVerificationResult.UpstreamUnavailable
        }
    }

    override fun isConfigured(): Boolean = configured
}

@TestConfiguration(proxyBeanMethods = false)
class FakeUpcCasConfiguration {
    @Bean
    @Primary
    fun fakeUpcCasVerifier(): FakeUpcCasVerifier = FakeUpcCasVerifier()
}
