package com.upcpocket.backend.service

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.AccountState
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditResultCode
import com.upcpocket.backend.contract.AuditStore
import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.BusinessSessionKey
import com.upcpocket.backend.contract.CasVerificationResult
import com.upcpocket.backend.contract.CredentialLease
import com.upcpocket.backend.contract.CredentialUsePurpose
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.DeviceDescriptor
import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.contract.SessionRevocationPort
import com.upcpocket.backend.contract.UpcCasVerifier
import com.upcpocket.backend.contract.VaultPolicyRecord
import com.upcpocket.backend.security.InMemoryAuthRateLimiter
import com.upcpocket.backend.session.BusinessLoginResult
import com.upcpocket.backend.session.BusinessSessionValue
import com.upcpocket.backend.session.DeviceSessionView
import com.upcpocket.backend.session.InMemorySessionRegistry
import com.upcpocket.backend.session.IssuedPocketSession
import com.upcpocket.backend.session.PocketSessionIdentity
import com.upcpocket.backend.session.SessionCapacityException
import com.upcpocket.backend.session.SessionIssueFence
import com.upcpocket.backend.session.SessionBusinessFence
import com.upcpocket.backend.session.SessionRevokedDuringIssuanceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StudentAccountSummary(
    val accountId: UUID,
    val state: AccountState,
    val credentialGeneration: Long,
    val gateRevision: Long,
)

private data class CredentialAttemptContext(
    val operationId: UUID,
    val expectedPendingAttemptId: UUID?,
    val tripAttemptId: UUID,
    val locallyReserved: Boolean,
    val adoptedPending: Boolean,
)

@Service
class StudentAccountService(
    private val accounts: AccountStore,
    private val audits: AuditStore,
    private val hasher: BlindIndexHasher,
    private val vault: CredentialVault,
    private val cas: UpcCasVerifier,
    private val revocations: SessionRevocationPort,
    private val sessions: InMemorySessionRegistry,
    private val rateLimiter: InMemoryAuthRateLimiter,
    private val transactions: TransactionTemplate,
    private val masterKeys: com.upcpocket.backend.contract.MasterKeyProvider,
    private val clock: Clock,
    @Value("\${pocket.vault.initial-write-key-version}") private val initialWriteKeyVersion: String,
) {
    // Reauth and crash-orphan recovery are locally serialized because the persistence port only
    // registers fresh attempts against active rows.
    private val recoveryAttempts = ConcurrentHashMap<UUID, UUID>()
    private val recoveryAttemptLock = Any()
    private val activePendingAttempts = ConcurrentHashMap<UUID, UUID>()
    private val activePendingAttemptLock = Any()
    private val inFlightAuthTags = HashSet<LoginTag>()
    private val inFlightAuthTagsLock = Any()

    fun createSession(
        loginName: CharArray,
        password: CharArray,
        acceptCustody: Boolean,
        device: DeviceDescriptor,
        clientIp: String,
        requestId: UUID,
    ): IssuedPocketSession {
        if (!acceptCustody) throw ApiException(ApiErrorCode.CUSTODY_ACCEPTANCE_REQUIRED)
        validateCredentialInput(loginName, password)
        val submittedTag = hasher.loginTag(loginName)
        return withInFlightAuthTag(submittedTag) {
            rateLimiter.check(submittedTag, clientIp)
            requireCasConfigured()
            val existing = accounts.findByLoginTag(submittedTag)
            if (existing == null) return@withInFlightAuthTag bindFirstAccount(loginName, password, submittedTag, device)
            if (existing.disabled) {
                when (val verification = verifyCas(loginName, password)) {
                    CasVerificationResult.InvalidCredentials -> throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    CasVerificationResult.InteractiveChallenge -> throw ApiException(ApiErrorCode.INTERACTIVE_CHALLENGE)
                    CasVerificationResult.UpstreamUnavailable -> throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
                    is CasVerificationResult.Verified -> verification.use {
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                }
            }
            if (existing.pendingAttemptId != null) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)

            val attemptId = registerAttempt(
                existing,
                onConflict = ApiErrorCode.SIGN_IN_FAILED,
                onPendingConflict = ApiErrorCode.SIGN_IN_FAILED,
            )
            val issueFence = if (existing.reauthRequired) null else try {
                sessions.captureIssueFence(existing.accountId, device.installationId)
            } catch (error: SessionCapacityException) {
                clearAttempt(existing, attemptId)
                throw error
            }
            var tokenIssued = false
            var preservePendingAttempt = false
            val issued = try {
            val verification = verifyCas(loginName, password)
            val issued = when (verification) {
                CasVerificationResult.InvalidCredentials -> {
                    preservePendingAttempt = true
                    runCatching { tripReauthRequired(existing, attemptId, requestId) }
                    throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                }
                CasVerificationResult.InteractiveChallenge -> {
                    runCatching { clearAttempt(existing, attemptId) }
                    throw ApiException(ApiErrorCode.INTERACTIVE_CHALLENGE)
                }
                CasVerificationResult.UpstreamUnavailable -> {
                    runCatching { clearAttempt(existing, attemptId) }
                    throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
                }
                is CasVerificationResult.Verified -> verification.use { verified ->
                    if (!sameIdentity(submittedTag, verified.normalizedLoginName)) {
                        clearAttempt(existing, attemptId)
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                    if (existing.reauthRequired) {
                        clearAttempt(existing, attemptId)
                        throw ApiException(ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED)
                    }
                    val credentialsMatch = try {
                        vault.matchesStoredCredential(existing, loginName, password)
                    } catch (_: Exception) {
                        clearAttempt(existing, attemptId)
                        throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                    }
                    if (!credentialsMatch) {
                        clearAttempt(existing, attemptId)
                        revocations.revokeBusinessSessions(existing.accountId)
                        throw ApiException(ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED)
                    }
                    val clearedAccount = clearAndReload(existing, attemptId)
                    if (clearedAccount.disabled || clearedAccount.reauthRequired ||
                        clearedAccount.credentialGeneration != existing.credentialGeneration ||
                        clearedAccount.gateRevision != existing.gateRevision
                    ) {
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                    try {
                        val studentId = supportedNumericStudentId(verified.normalizedLoginName)
                        try {
                            sessions.issue(
                                clearedAccount,
                                device,
                                issueFence ?: throw ApiException(ApiErrorCode.SIGN_IN_FAILED),
                                studentId,
                            )
                        } finally {
                            studentId?.fill('\u0000')
                        }
                    } catch (_: SessionRevokedDuringIssuanceException) {
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                }
            }
            tokenIssued = true
            issued
            } finally {
                activePendingAttempts.remove(existing.accountId, attemptId)
                if (!tokenIssued) {
                    issueFence?.let(sessions::discardUnusedIssueFence)
                    if (!preservePendingAttempt) runCatching { clearAttempt(existing, attemptId) }
                }
            }
            issued
        }
    }

    fun updateCredential(
        loginName: CharArray,
        password: CharArray,
        clientIp: String,
        requestId: UUID,
    ) {
        validateCredentialInput(loginName, password)
        val submittedTag = hasher.loginTag(loginName)
        withInFlightAuthTag(submittedTag) {
            rateLimiter.check(submittedTag, clientIp)
            requireCasConfigured()
            val captured = accounts.findByLoginTag(submittedTag)?.takeUnless { it.disabled }
            val attempt = captured?.let(::registerCredentialUpdateAttempt)
            try {
            when (val verification = verifyCas(loginName, password)) {
                CasVerificationResult.InvalidCredentials -> {
                    if (captured != null && attempt != null) {
                        runCatching { tripReauthRequired(captured, attempt.tripAttemptId, requestId) }
                    }
                    throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                }
                CasVerificationResult.InteractiveChallenge -> {
                    if (captured != null && attempt != null && !attempt.adoptedPending) {
                        runCatching { clearCredentialAttempt(captured, attempt) }
                    }
                    throw ApiException(ApiErrorCode.INTERACTIVE_CHALLENGE)
                }
                CasVerificationResult.UpstreamUnavailable -> {
                    if (captured != null && attempt != null && !attempt.adoptedPending) {
                        runCatching { clearCredentialAttempt(captured, attempt) }
                    }
                    throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
                }
                is CasVerificationResult.Verified -> verification.use { verified ->
                    if (captured == null || attempt == null) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    if (!sameIdentity(submittedTag, verified.normalizedLoginName) || captured.loginTag != submittedTag) {
                        if (!attempt.locallyReserved) {
                            runCatching { clearCredentialAttempt(captured, attempt) }
                        }
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                    val updated = try {
                        transaction {
                            val writeVersion = lockWriteVersionForWrite()
                            val current = accounts.findByAccountId(captured.accountId)
                            val expectedPendingAttempt = attempt.expectedPendingAttemptId
                            if (current == null || current.disabled ||
                                current.credentialGeneration != captured.credentialGeneration ||
                                current.gateRevision != captured.gateRevision || current.pendingAttemptId != expectedPendingAttempt
                            ) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                            val envelope = vault.seal(
                                current.accountId,
                                submittedTag,
                                current.credentialGeneration + 1,
                                writeVersion,
                                verified.normalizedLoginName,
                                password,
                            )
                            val changed = accounts.updateCredential(
                                current.accountId,
                                current.credentialGeneration,
                                current.gateRevision,
                                expectedPendingAttempt,
                                submittedTag,
                                envelope,
                            ) ?: throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                            appendAudit(AuditActionCode.UPDATE_CREDENTIAL, AuditResultCode.OK, requestId, changed.accountId)
                            changed
                        }
                    } catch (error: ApiException) {
                        throw error
                    } catch (_: Exception) {
                        throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                    }
                    revocations.revokeAccountAll(updated.accountId)
                }
            }
            } finally {
                if (captured != null && attempt != null) releaseCredentialAttempt(captured, attempt)
            }
        }
    }

    fun me(accountId: UUID): StudentAccountSummary =
        accounts.findByAccountId(accountId)?.toSummary() ?: throw ApiException(ApiErrorCode.NOT_FOUND)

    fun devices(accountId: UUID): List<DeviceSessionView> = sessions.devices(accountId)

    fun captureBusinessFence(identity: PocketSessionIdentity): SessionBusinessFence {
        val account = findBusinessAccount(identity.accountId)
        if (account == null || account.disabled) throw ApiException(ApiErrorCode.TOKEN_INVALID)
        if (account.reauthRequired || account.credentialGeneration != identity.credentialGeneration) {
            throw ApiException(ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED)
        }
        if (account.gateRevision != identity.gateRevision) throw ApiException(ApiErrorCode.TOKEN_INVALID)
        return try {
            sessions.captureBusinessFence(identity) ?: throw ApiException(ApiErrorCode.TOKEN_INVALID)
        } catch (error: ApiException) {
            throw error
        } catch (_: Exception) {
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    fun assertBusinessFenceCurrent(fence: SessionBusinessFence) {
        val account = findBusinessAccount(fence.accountId)
        if (account == null || account.disabled || account.gateRevision != fence.gateRevision) {
            throw ApiException(ApiErrorCode.TOKEN_INVALID)
        }
        if (account.reauthRequired || account.credentialGeneration != fence.credentialGeneration) {
            throw ApiException(ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED)
        }
        if (!sessions.isBusinessFenceCurrent(fence)) throw ApiException(ApiErrorCode.TOKEN_INVALID)
    }

    private fun findBusinessAccount(accountId: UUID): AccountRecord? = try {
        accounts.findByAccountId(accountId)
    } catch (_: Exception) {
        throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
    }

    fun signOut(identity: PocketSessionIdentity) {
        sessions.revokeCurrent(identity)
    }

    fun revokeDevice(accountId: UUID, deviceId: UUID) {
        if (!sessions.revokeDevice(accountId, deviceId)) throw ApiException(ApiErrorCode.NOT_FOUND)
    }

    fun unbind(accountId: UUID, requestId: UUID) {
        transaction {
            val current = accounts.findByAccountId(accountId) ?: throw ApiException(ApiErrorCode.NOT_FOUND)
            if (!accounts.unbind(accountId, current.gateRevision)) throw ApiException(ApiErrorCode.NOT_FOUND)
            appendAudit(AuditActionCode.UNBIND, AuditResultCode.OK, requestId, accountId)
            true
        }
        revocations.revokeAccountAll(accountId)
    }

    /**
     * Controlled stage-two guard used by the future business proxy. The callback runs while its
     * credential lease is open; a response is stored only after state, device, generation and
     * business epoch are rechecked.
     */
    fun executeAutoBusinessLogin(
        accountId: UUID,
        deviceId: UUID,
        serviceKey: String,
        requestId: UUID,
        beforeCredentialUse: () -> Unit = {},
        loginOperation: (CredentialLease) -> BusinessLoginResult,
    ): BusinessSessionValue {
        val captured = accounts.findByAccountId(accountId) ?: throw ApiException(ApiErrorCode.NOT_FOUND)
        if (captured.disabled || captured.reauthRequired || captured.pendingAttemptId != null ||
            !sessions.isDeviceActive(accountId, deviceId, captured.credentialGeneration, captured.gateRevision)
        ) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
        val businessKey = BusinessSessionKey(accountId, captured.credentialGeneration, serviceKey)
        sessions.getBusinessSession(businessKey)?.let { return it }
        val writeFence = try {
            sessions.beginBusinessSessionWrite(accountId)
        } catch (_: Exception) {
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
        val attemptId = try {
            registerAttempt(captured, ApiErrorCode.VERIFICATION_BUSY)
        } catch (error: Exception) {
            sessions.releaseBusinessSessionWrite(writeFence)
            throw error
        }
        var preservePendingAttempt = false
        try {
            val accountWithAttempt = accounts.findByAccountId(accountId)
                ?.takeIf {
                    !it.disabled && !it.reauthRequired &&
                        it.pendingAttemptId == attemptId &&
                        it.credentialGeneration == captured.credentialGeneration &&
                        it.gateRevision == captured.gateRevision
                }
                ?: throw ApiException(ApiErrorCode.VERIFICATION_BUSY)
            beforeCredentialUse()
            return vault.withCredential(accountWithAttempt, CredentialUsePurpose.AUTO_BUSINESS_LOGIN) { lease ->
                beforeCredentialUse()
                val verification = verifyCas(lease.loginName, lease.password)
                when (verification) {
                    CasVerificationResult.InvalidCredentials -> {
                        preservePendingAttempt = true
                        runCatching { tripReauthRequired(captured, attemptId, requestId) }
                        throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    }
                    CasVerificationResult.InteractiveChallenge -> {
                        runCatching { clearAttempt(captured, attemptId) }
                        throw ApiException(ApiErrorCode.INTERACTIVE_CHALLENGE)
                    }
                    CasVerificationResult.UpstreamUnavailable -> {
                        runCatching { clearAttempt(captured, attemptId) }
                        throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
                    }
                    is CasVerificationResult.Verified -> verification.use { verified ->
                        val storedTag = hasher.loginTag(lease.loginName)
                        if (!sameIdentity(storedTag, verified.normalizedLoginName) || storedTag != captured.loginTag) {
                            clearAttempt(captured, attemptId)
                            throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                        }
                        val result = try {
                            beforeCredentialUse()
                            loginOperation(lease)
                        } catch (error: ApiException) {
                            clearAttempt(captured, attemptId)
                            throw error
                        } catch (error: com.upcpocket.backend.contract.CampusAdapterException) {
                            clearAttempt(captured, attemptId)
                            throw ApiException(error.errorCode)
                        } catch (_: Exception) {
                            clearAttempt(captured, attemptId)
                            throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
                        }
                        result.use { loginResult ->
                            val stillValid = transaction {
                                val current = accounts.findByAccountId(accountId)
                                val wasCleared = accounts.clearPendingAttempt(
                                    accountId,
                                    captured.credentialGeneration,
                                    attemptId,
                                )
                                current != null && wasCleared && !current.disabled && !current.reauthRequired &&
                                    current.credentialGeneration == captured.credentialGeneration &&
                                    current.gateRevision == captured.gateRevision
                            }
                            val responseBytes = loginResult.copyBytes()
                            try {
                                if (!stillValid || !sessions.isDeviceActive(
                                        accountId,
                                        deviceId,
                                        captured.credentialGeneration,
                                        captured.gateRevision,
                                    )
                                ) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                                val stored = sessions.putBusinessSession(
                                    businessKey,
                                    responseBytes,
                                    loginResult.upstreamExpiresAt,
                                    writeFence,
                                )
                                if (!stored) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                                sessions.getBusinessSession(businessKey) ?: throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                            } finally {
                                responseBytes.fill(0)
                            }
                        }
                    }
                }
            }
        } catch (error: ApiException) {
            if (!preservePendingAttempt && error.code !in setOf(
                    ApiErrorCode.SIGN_IN_FAILED,
                    ApiErrorCode.INTERACTIVE_CHALLENGE,
                    ApiErrorCode.UPSTREAM_UNAVAILABLE,
                )
            ) {
                clearAttempt(captured, attemptId)
            }
            throw error
        } catch (_: Exception) {
            if (!preservePendingAttempt) clearAttempt(captured, attemptId)
            throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        } finally {
            sessions.releaseBusinessSessionWrite(writeFence)
            activePendingAttempts.remove(accountId, attemptId)
        }
    }

    private fun bindFirstAccount(
        loginName: CharArray,
        password: CharArray,
        submittedTag: com.upcpocket.backend.contract.LoginTag,
        device: DeviceDescriptor,
    ): IssuedPocketSession {
        val verification = verifyCas(loginName, password)
        return when (verification) {
            CasVerificationResult.InvalidCredentials -> throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
            CasVerificationResult.InteractiveChallenge -> throw ApiException(ApiErrorCode.INTERACTIVE_CHALLENGE)
            CasVerificationResult.UpstreamUnavailable -> throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
            is CasVerificationResult.Verified -> verification.use { verified ->
            if (!sameIdentity(submittedTag, verified.normalizedLoginName)) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
            val accountId = UUID.randomUUID()
            val issueFence = sessions.captureIssueFence(accountId, device.installationId)
            val account = try {
                transaction {
                    val writeVersion = lockWriteVersionForWrite()
                    if (accounts.findByLoginTag(submittedTag) != null) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    val envelope = vault.seal(
                        accountId,
                        submittedTag,
                        1,
                        writeVersion,
                        verified.normalizedLoginName,
                        password,
                    )
                    val created = AccountRecord(
                        accountId = accountId,
                        loginTag = submittedTag,
                        disabled = false,
                        reauthRequired = false,
                        credentialGeneration = 1,
                        gateRevision = 1,
                        pendingAttemptId = null,
                        envelope = envelope,
                    )
                    if (!accounts.insertFirstBinding(created)) throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
                    created
                }
            } catch (error: ApiException) {
                sessions.discardUnusedIssueFence(issueFence)
                throw error
            } catch (_: Exception) {
                sessions.discardUnusedIssueFence(issueFence)
                throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
            }
            try {
                val studentId = supportedNumericStudentId(verified.normalizedLoginName)
                try {
                    sessions.issue(account, device, issueFence, studentId)
                } finally {
                    studentId?.fill('\u0000')
                }
            } catch (_: SessionRevokedDuringIssuanceException) {
                sessions.discardUnusedIssueFence(issueFence)
                throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
            } catch (error: SessionCapacityException) {
                sessions.discardUnusedIssueFence(issueFence)
                throw error
            }
        }
        }
    }

    private fun verifyCas(loginName: CharArray, password: CharArray): CasVerificationResult =
        try {
            cas.verify(loginName, password)
        } catch (_: Exception) {
            CasVerificationResult.UpstreamUnavailable
        }

    private fun supportedNumericStudentId(loginName: CharArray): CharArray? =
        loginName.takeIf { value -> value.size in 8..16 && value.all { it in '0'..'9' } }?.copyOf()

    private fun registerAttempt(
        account: AccountRecord,
        onConflict: ApiErrorCode,
        onPendingConflict: ApiErrorCode = ApiErrorCode.VERIFICATION_BUSY,
    ): UUID {
        val attemptId = UUID.randomUUID()
        if (account.reauthRequired) {
            reserveRecoveryAttempt(account.accountId, attemptId, onPendingConflict)
            return attemptId
        }
        reserveActivePendingAttempt(account.accountId, attemptId, onPendingConflict)
        try {
            val registered = transaction {
                accounts.registerPendingAttempt(
                    account.accountId,
                    account.credentialGeneration,
                    account.gateRevision,
                    attemptId,
                )
            }
            if (!registered) {
                activePendingAttempts.remove(account.accountId, attemptId)
                val current = accounts.findByAccountId(account.accountId)
                if (current?.pendingAttemptId != null) throw ApiException(onPendingConflict)
                throw ApiException(onConflict)
            }
            return attemptId
        } catch (error: Exception) {
            activePendingAttempts.remove(account.accountId, attemptId)
            throw error
        }
    }

    private fun registerCredentialUpdateAttempt(account: AccountRecord): CredentialAttemptContext {
        val operationId = UUID.randomUUID()
        val pendingAttemptId = account.pendingAttemptId
        if (account.reauthRequired || pendingAttemptId != null) {
            if (pendingAttemptId != null && activePendingAttempts.containsKey(account.accountId)) {
                throw ApiException(ApiErrorCode.VERIFICATION_BUSY)
            }
            reserveRecoveryAttempt(account.accountId, operationId)
            return CredentialAttemptContext(
                operationId = operationId,
                expectedPendingAttemptId = pendingAttemptId,
                tripAttemptId = pendingAttemptId ?: operationId,
                locallyReserved = true,
                adoptedPending = pendingAttemptId != null,
            )
        }
        val attemptId = registerAttempt(account, ApiErrorCode.SIGN_IN_FAILED)
        return CredentialAttemptContext(
            attemptId,
            attemptId,
            attemptId,
            locallyReserved = false,
            adoptedPending = false,
        )
    }

    private fun reserveRecoveryAttempt(
        accountId: UUID,
        attemptId: UUID,
        onConflict: ApiErrorCode = ApiErrorCode.VERIFICATION_BUSY,
    ) {
        synchronized(recoveryAttemptLock) {
            if (recoveryAttempts.size >= MAX_LOCAL_RECOVERY_ATTEMPTS ||
                recoveryAttempts.putIfAbsent(accountId, attemptId) != null
            ) throw ApiException(onConflict)
        }
    }

    private fun reserveActivePendingAttempt(
        accountId: UUID,
        attemptId: UUID,
        onConflict: ApiErrorCode = ApiErrorCode.VERIFICATION_BUSY,
    ) {
        synchronized(activePendingAttemptLock) {
            if (activePendingAttempts.size >= MAX_LOCAL_RECOVERY_ATTEMPTS ||
                activePendingAttempts.putIfAbsent(accountId, attemptId) != null
            ) throw ApiException(onConflict)
        }
    }

    private fun tripReauthRequired(account: AccountRecord, attemptId: UUID, requestId: UUID) {
        try {
            transaction {
                val tripped = accounts.tripReauthRequired(account.accountId, account.credentialGeneration, attemptId)
                accounts.clearPendingAttempt(account.accountId, account.credentialGeneration, attemptId)
                appendAudit(
                    AuditActionCode.TRIP_REAUTH,
                    if (tripped || account.reauthRequired) AuditResultCode.OK else AuditResultCode.CONFLICT,
                    requestId,
                    account.accountId,
                )
                tripped
            }
        } finally {
            recoveryAttempts.remove(account.accountId, attemptId)
            activePendingAttempts.remove(account.accountId, attemptId)
            // Revoke volatile business sessions even if the audit transaction rolls back so cached
            // upstream sessions cannot be reused, while keeping the device token recognizable to
            // return 409 CREDENTIAL_UPDATE_REQUIRED until manual password update revokes all tokens.
            revocations.revokeBusinessSessions(account.accountId)
        }
    }

    private fun clearAttempt(account: AccountRecord, attemptId: UUID) {
        if (recoveryAttempts.remove(account.accountId, attemptId)) return
        try {
            transaction { accounts.clearPendingAttempt(account.accountId, account.credentialGeneration, attemptId) }
        } finally {
            activePendingAttempts.remove(account.accountId, attemptId)
        }
    }

    private fun clearCredentialAttempt(account: AccountRecord, attempt: CredentialAttemptContext) {
        if (!attempt.locallyReserved) {
            clearAttempt(account, attempt.operationId)
            return
        }
        try {
            attempt.expectedPendingAttemptId?.let { pendingAttemptId ->
                transaction {
                    accounts.clearPendingAttempt(account.accountId, account.credentialGeneration, pendingAttemptId)
                }
            }
        } finally {
            recoveryAttempts.remove(account.accountId, attempt.operationId)
        }
    }

    private fun releaseCredentialAttempt(account: AccountRecord, attempt: CredentialAttemptContext) {
        if (attempt.locallyReserved) recoveryAttempts.remove(account.accountId, attempt.operationId)
        else activePendingAttempts.remove(account.accountId, attempt.operationId)
    }

    private fun clearAndReload(account: AccountRecord, attemptId: UUID): AccountRecord = transaction {
        if (!accounts.clearPendingAttempt(account.accountId, account.credentialGeneration, attemptId)) {
            throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
        }
        accounts.findByAccountId(account.accountId) ?: throw ApiException(ApiErrorCode.SIGN_IN_FAILED)
    }

    private fun lockWriteVersionForWrite(): String {
        var policy = accounts.lockVaultPolicyForWrite()
        if (policy == null) {
            val configured = configuredKeyVersions()
            if (initialWriteKeyVersion !in configured) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
            accounts.insertVaultPolicyIfAbsent(VaultPolicyRecord(initialWriteKeyVersion))
            policy = accounts.lockVaultPolicyForWrite()
        }
        val current = policy?.writeKeyVersion ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        val configured = configuredKeyVersions()
        if (current !in configured) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        return current
    }

    private fun configuredKeyVersions(): Set<String> = try {
        masterKeys.configuredVersions()
    } catch (_: Exception) {
        throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
    }

    private fun requireCasConfigured() {
        val configured = try {
            cas.isConfigured()
        } catch (_: Exception) {
            false
        }
        if (!configured) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
    }

    private fun <T> withInFlightAuthTag(loginTag: LoginTag, block: () -> T): T {
        synchronized(inFlightAuthTagsLock) {
            if (loginTag in inFlightAuthTags || inFlightAuthTags.size >= MAX_IN_FLIGHT_AUTH_TAGS) {
                throw ApiException(ApiErrorCode.VERIFICATION_BUSY)
            }
            inFlightAuthTags.add(loginTag)
        }
        try {
            return block()
        } finally {
            synchronized(inFlightAuthTagsLock) {
                inFlightAuthTags.remove(loginTag)
            }
        }
    }

    private fun sameIdentity(
        submitted: com.upcpocket.backend.contract.LoginTag,
        normalizedVerifiedLogin: CharArray,
    ): Boolean = submitted == hasher.loginTag(normalizedVerifiedLogin)

    private fun validateCredentialInput(loginName: CharArray, password: CharArray) {
        if (loginName.isEmpty() || loginName.size > MAX_LOGIN_NAME_CHARS ||
            password.isEmpty() || password.size > MAX_PASSWORD_CHARS
        ) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
    }

    private fun appendAudit(
        action: AuditActionCode,
        result: AuditResultCode,
        requestId: UUID,
        accountId: UUID,
    ) {
        audits.append(
            AuditEventRecord(
                eventId = UUID.randomUUID(),
                occurredAt = clock.instant(),
                requestId = requestId,
                actionCode = action,
                resultCode = result,
                actorRef = null,
                targetRef = hasher.auditRef(accountId.toString()),
            ),
        )
    }

    private fun AccountRecord.toSummary() =
        StudentAccountSummary(accountId, effectiveState(), credentialGeneration, gateRevision)

    private fun <T : Any> transaction(block: () -> T): T =
        transactions.execute { block() } ?: throw IllegalStateException("Transaction returned no result")

    companion object {
        private const val MAX_LOCAL_RECOVERY_ATTEMPTS = 10_000
        private const val MAX_IN_FLIGHT_AUTH_TAGS = 10_000
        private const val MAX_LOGIN_NAME_CHARS = 256
        private const val MAX_PASSWORD_CHARS = 1_024
    }
}

private inline fun <T> CasVerificationResult.Verified.use(block: (CasVerificationResult.Verified) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
