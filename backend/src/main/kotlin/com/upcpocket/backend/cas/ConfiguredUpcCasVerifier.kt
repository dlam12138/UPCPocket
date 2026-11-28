package com.upcpocket.backend.cas

import com.upcpocket.backend.contract.CasVerificationResult
import com.upcpocket.backend.contract.UpcCasVerifier

/**
 * Fail-closed placeholder. It performs no HTTP requests and is never ready, even when endpoint
 * fields are populated. Replace it with an approved transport adapter before enabling CAS.
 */
class ConfiguredUpcCasVerifier(
    @Suppress("UNUSED_PARAMETER") properties: CasProperties,
) : UpcCasVerifier {
    override fun verify(loginName: CharArray, password: CharArray): CasVerificationResult =
        CasVerificationResult.UpstreamUnavailable

    /** Configuration fields alone do not mean that a CAS transport is implemented and ready. */
    override fun isConfigured(): Boolean = false
}
