package com.upcpocket.backend.cas

import com.upcpocket.backend.contract.CasVerificationResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ConfiguredUpcCasVerifierTest {
    @Test
    fun `default verifier is not configured and fails closed`() {
        val verifier = ConfiguredUpcCasVerifier(CasProperties())
        assertFalse(verifier.isConfigured())
        assertSame(CasVerificationResult.UpstreamUnavailable, verifier.verify("test".toCharArray(), "test".toCharArray()))
    }

    @Test
    fun `complete endpoint properties do not report placeholder transport ready`() {
        val properties = CasProperties().apply {
            enabled = true
            loginUrl = "https://cas.example.invalid/login"
            service = "https://service.example.invalid/"
        }
        val verifier = ConfiguredUpcCasVerifier(properties)

        assertFalse(verifier.isConfigured())
        assertSame(CasVerificationResult.UpstreamUnavailable, verifier.verify("test".toCharArray(), "test".toCharArray()))
    }
}
