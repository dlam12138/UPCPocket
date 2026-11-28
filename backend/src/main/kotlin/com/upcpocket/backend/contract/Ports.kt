package com.upcpocket.backend.contract

import java.util.UUID

interface MasterKeyProvider {
    fun configuredVersions(): Set<String>

    /** Wraps a DEK without retaining the supplied key bytes. */
    fun wrap(keyVersion: String, dek: ByteArray): ByteArray

    /** Returns a newly allocated DEK; the caller is responsible for clearing it. */
    fun unwrap(keyVersion: String, wrappedDek: ByteArray): ByteArray

    fun rewrap(fromVersion: String, toVersion: String, wrappedDek: ByteArray): ByteArray

    fun healthCheck(): Boolean
}

interface BlindIndexHasher {
    fun loginTag(loginName: CharArray): LoginTag

    /** Returns a stable 32-byte keyed reference and must not retain or log [rawSubject]. */
    fun auditRef(rawSubject: String): ByteArray
}

interface CredentialVault {
    /** Seals a credential envelope; implementations must not retain either input array. */
    fun seal(
        accountId: UUID,
        loginTag: LoginTag,
        credentialGeneration: Long,
        keyVersion: String,
        loginName: CharArray,
        password: CharArray,
    ): EncryptedEnvelope

    /** The lease is closed and its arrays zeroed immediately after [block] returns or throws. */
    fun <T> withCredential(
        account: AccountRecord,
        purpose: CredentialUsePurpose,
        block: (CredentialLease) -> T,
    ): T

    fun matchesStoredCredential(account: AccountRecord, loginName: CharArray, password: CharArray): Boolean

    fun rewrapDek(envelope: EncryptedEnvelope, targetKeyVersion: String): EncryptedEnvelope
}

interface UpcCasVerifier {
    /** Uses only character arrays and must not retain the supplied credential arrays. */
    fun verify(loginName: CharArray, password: CharArray): CasVerificationResult

    fun isConfigured(): Boolean
}

/**
 * Conditional account persistence port. Each compare-and-update is atomic. Callers that mutate
 * account state must append the corresponding AuditEventRecord through AuditStore in the same
 * database transaction; AuditStore.append is specified to join an active transaction.
 */
interface AccountStore {
    fun findByLoginTag(loginTag: LoginTag): AccountRecord?

    fun findByAccountId(accountId: UUID): AccountRecord?

    /** Inserts the first binding only; duplicate account IDs or login tags return false. */
    fun insertFirstBinding(account: AccountRecord): Boolean

    /**
     * Registers an attempt only for the captured active account generation/revision with no
     * pending attempt already present.
     */
    fun registerPendingAttempt(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedGateRevision: Long,
        attemptId: UUID,
    ): Boolean

    /** Clears only the attempt whose ID and credential generation still match. */
    fun clearPendingAttempt(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        attemptId: UUID,
    ): Boolean

    /** Marks reauthentication required only for the matching pending attempt and generation. */
    fun tripReauthRequired(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        attemptId: UUID,
    ): Boolean

    /**
     * Updates credentials only when every expected value, including nullable pendingAttemptId,
     * still matches. On success increments credentialGeneration and gateRevision, clears the
     * pending attempt and reauthRequired flag, and preserves disabled.
     */
    fun updateCredential(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedGateRevision: Long,
        expectedPendingAttemptId: UUID?,
        loginTag: LoginTag,
        envelope: EncryptedEnvelope,
    ): AccountRecord?

    /** Changes the flag only when expectedGateRevision matches; successful changes advance it. */
    fun disable(accountId: UUID, expectedGateRevision: Long): AccountRecord?

    /** Restores disabled=false only at the expected revision and preserves reauthRequired. */
    fun restore(accountId: UUID, expectedGateRevision: Long): AccountRecord?

    /** Advances the gate revision to invalidate all previously captured access decisions. */
    fun revokeAll(accountId: UUID, expectedGateRevision: Long): AccountRecord?

    /**
     * Converts any orphaned pending attempts left by a previously terminated single-active JVM
     * into reauth_required=TRUE so auto-login stays blocked while manual recovery remains possible.
     */
    fun convertOrphanedPendingAttemptsToReauthRequired(): Int

    /** Hard-deletes the account only at the expected gate revision. */
    fun unbind(accountId: UUID, expectedGateRevision: Long): Boolean

    fun findVaultPolicy(): VaultPolicyRecord?

    /** Acquires a row-level FOR UPDATE lock on the singleton vault_policy row inside an active transaction. */
    fun lockVaultPolicyForWrite(): VaultPolicyRecord?

    fun insertVaultPolicyIfAbsent(policy: VaultPolicyRecord): Boolean

    fun updateVaultWriteKeyVersion(expectedWriteKeyVersion: String, newWriteKeyVersion: String): Boolean

    /** Returns a bounded, accountId-ordered page of envelopes whose keyVersion differs from [targetKeyVersion]. */
    fun findAccountsNeedingRewrap(targetKeyVersion: String, afterAccountId: UUID?, limit: Int): List<AccountRecord>

    fun countAccountsNotUsingKeyVersion(targetKeyVersion: String): Long

    /** Updates only if row generation, key version, and old wrapped DEK all still match. */
    fun updateWrappedDekIfUnchanged(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedKeyVersion: String,
        expectedWrappedDek: ByteArray,
        newKeyVersion: String,
        newWrappedDek: ByteArray,
    ): Boolean
}

interface AuditStore {
    /** Must participate in the caller's current transaction when one exists. */
    fun append(event: AuditEventRecord)

    /** The page size is restricted to 1..100; cursor values are opaque. */
    fun list(cursor: String?, limit: Int): AuditPage
}

interface SessionRevocationPort {
    fun revokeAccountAll(accountId: UUID)

    fun revokeBusinessSessions(accountId: UUID)

    fun currentBusinessEpoch(accountId: UUID): Long
}
