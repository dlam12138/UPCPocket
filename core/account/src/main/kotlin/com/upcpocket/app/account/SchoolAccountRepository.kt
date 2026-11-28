package com.upcpocket.app.account

import android.content.Context
import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationReason
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.ManagedDevice
import com.upcpocket.app.account.api.ManagedDeviceListResult
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.campusbackend.BackendErrorCode
import com.upcpocket.app.campusbackend.BackendFailure
import com.upcpocket.app.campusbackend.BackendResult
import com.upcpocket.app.campusbackend.BackendOriginPolicy
import com.upcpocket.app.campusbackend.CampusBackendClient
import com.upcpocket.app.campusbackend.CampusAccountState
import com.upcpocket.app.campusbackend.CampusDeviceKind
import com.upcpocket.app.campusbackend.CampusPlatform
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A short-lived request lease. The token is only passed to app-owned backend adapters. */
class ManagedSessionLease internal constructor(
    val accessToken: String,
    val accountId: SchoolAccountId,
    val deviceId: String,
    val credentialGeneration: AccountGeneration,
    val transactionGeneration: Long,
) {
    override fun toString(): String =
        "ManagedSessionLease(accountId=$accountId, deviceId=$deviceId, token=[REDACTED])"
}

/**
 * School-account facade backed only by a Pocket-managed session.
 * Passwords are accepted for foreground bind/update calls, sent once, then cleared.
 */
class SchoolAccountRepository internal constructor(
    private val storage: ManagedSessionStorage,
    private val legacyPurger: LegacySchoolCredentialPurger,
    private val backendClient: CampusBackendClient,
    private val backendOrigin: String?,
    private val invalidators: List<SchoolAccountInvalidator>,
    private val onSessionClosed: () -> Unit,
    private val deviceKindProvider: () -> CampusDeviceKind,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
) : SchoolAccountController,
    SchoolAccountStudentIdentityReader,
    SchoolAccountReferenceReader,
    SchoolAccountIdentityVerifier,
    SchoolCredentialAccess {

    constructor(
        context: Context,
        backendClient: CampusBackendClient = CampusBackendClient.create(null, BackendOriginPolicy.Debug),
        backendOrigin: String? = null,
        invalidators: List<SchoolAccountInvalidator> = emptyList(),
        onSessionClosed: () -> Unit = {},
        deviceKindProvider: () -> CampusDeviceKind = { CampusDeviceKind.PHONE },
    ) : this(
        storage = ManagedSessionStorage(context.applicationContext.noBackupFilesDir),
        legacyPurger = LegacySchoolCredentialPurger(
            File(context.applicationContext.noBackupFilesDir, "school-account"),
        ),
        backendClient = backendClient,
        backendOrigin = backendOrigin,
        invalidators = invalidators,
        onSessionClosed = onSessionClosed,
        deviceKindProvider = deviceKindProvider,
        clock = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO,
    )

    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(SchoolAccountState())
    private val _studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(null)
    private val _accountReference = MutableStateFlow<SchoolAccountReference?>(null)
    private val configLock = Any()
    @Volatile private var config: ManagedSessionConfig? = null
    @Volatile private var activeRecord: ManagedSessionRecord? = null
    @Volatile private var initialized = false
    private var remoteRevocationInFlight = 0

    override val state: StateFlow<SchoolAccountState> = _state.asStateFlow()
    override val studentIdentity: StateFlow<SchoolStudentIdentity?> = _studentIdentity.asStateFlow()
    override val accountReference: StateFlow<SchoolAccountReference?> = _accountReference.asStateFlow()

    /**
     * Synchronous, offline first half of the one-way legacy-password upgrade.
     * Call before any old account restore and before starting plugin restoration.
     */
    fun beginLegacyCredentialUpgrade(): Boolean = synchronized(configLock) {
        val existing = runCatching { storage.readMigrationMarker() }.getOrNull()
        if (existing?.pending == false) return@synchronized false
        val current = ensureConfigLocked()
        if (existing == null) {
            val next = storage.advanceTransaction(current)
            config = next
            storage.writeMigrationMarker(
                LegacyPasswordCleanupMarker(
                    pending = true,
                    transactionGeneration = next.transactionGeneration,
                ),
            )
        }
        activeRecord = null
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.REMOVING,
            cleanupRequired = true,
            errorCode = SchoolAccountErrorCode.CLEANUP_REQUIRED,
            transactionGeneration = config?.transactionGeneration ?: 0,
        )
        true
    }

    /** Alias and legacy files are purged only after the host has blanked personal widgets. */
    fun purgeLegacyPasswordMaterial(): Boolean = legacyPurger.purge()

    /** Finishes the migration after plugins are registered, so the host invalidator is available. */
    suspend fun finishLegacyCredentialUpgrade(): SchoolAccountWriteResult = withContext(ioDispatcher) {
        operationMutex.withLock {
            val marker = try {
                storage.readMigrationMarker()
            } catch (_: Exception) {
                return@withLock cleanupFailure()
            } ?: return@withLock cleanupFailure()
            if (!marker.pending) return@withLock SchoolAccountWriteResult.Success
            if (!legacyPurger.purge()) return@withLock cleanupFailure()
            runCatching { storage.clearSession() }.getOrElse { return@withLock cleanupFailure() }
            activeRecord = null
            val currentConfig = ensureConfig()
            val result = invalidateAll(
                AccountInvalidationRequest(
                    reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                    scope = AccountDataScope.PERSONAL_DATA,
                    previousGeneration = AccountGeneration.None,
                    nextGeneration = AccountGeneration(currentConfig.transactionGeneration.coerceAtLeast(1)),
                ),
            )
            if (!result.ok) return@withLock cleanupFailure()
            runCatching {
                config = storage.updateBackendOrigin(currentConfig, backendOrigin)
                storage.writeMigrationMarker(
                    marker.copy(pending = false, transactionGeneration = currentConfig.transactionGeneration),
                )
                storage.deleteCleanupMarker()
            }.getOrElse { return@withLock cleanupFailure() }
            initialized = true
            publishNotConfigured()
            SchoolAccountWriteResult.Success
        }
    }

    override suspend fun restore() = withContext(ioDispatcher) {
        operationMutex.withLock {
            val marker = runCatching { storage.readMigrationMarker() }.getOrNull()
            if (marker == null) {
                beginLegacyCredentialUpgrade()
                return@withLock
            }
            if (marker.pending) {
                setCleanupState(SchoolAccountStatus.REMOVING, SchoolAccountErrorCode.CLEANUP_REQUIRED)
                return@withLock
            }
            val pending = runCatching { storage.readCleanupMarker() }.getOrNull()
            if (pending != null) {
                val stored = runCatching { storage.loadSession() }.getOrNull()
                val revocation = stored?.takeIf(::isRemoteRevocationPending)
                if (revocation == null) {
                    setCleanupState(SchoolAccountStatus.REMOVING, SchoolAccountErrorCode.CLEANUP_REQUIRED)
                } else {
                    activeRecord = revocation
                    publishPendingRevocation(
                        revocation,
                        cleanupRequired = true,
                        error = SchoolAccountErrorCode.CLEANUP_REQUIRED,
                    )
                }
                finishManagedCleanupLocked(pending)
                return@withLock
            }
            restoreSessionLocked()
        }
    }

    private suspend fun restoreSessionLocked() {
        val storedOrigin = runCatching { storage.existingBackendOrigin() }.getOrNull()
        val hadSessionArtifacts = runCatching { storage.hasSessionArtifacts() }.getOrDefault(true)
        val currentConfig = try {
            ensureConfig()
        } catch (_: Exception) {
            val recovered = runCatching { storage.loadSession() }.getOrNull()
            if (recovered != null && isRemoteRevocationPending(recovered)) {
                activeRecord = recovered
                initialized = true
                publishPendingRevocation(
                    recovered,
                    cleanupRequired = !recovered.personalDataCleared,
                    error = SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE,
                )
                onSessionClosedSafely()
                return
            }
            startManagedCleanupLocked(
                scope = AccountDataScope.PERSONAL_DATA,
                reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                previousGeneration = AccountGeneration.None,
            )
            initialized = true
            return
        }
        val expectedOrigin = backendOrigin?.trim()?.trimEnd('/').orEmpty()
        val originChanged = currentConfig.backendOrigin != expectedOrigin ||
            (storedOrigin == null && hadSessionArtifacts)
        val loaded = try {
            storage.loadSession()
        } catch (_: Exception) {
            startManagedCleanupLocked(
                scope = AccountDataScope.PERSONAL_DATA,
                reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                previousGeneration = AccountGeneration.None,
            )
            initialized = true
            return
        }
        if (originChanged) {
            if (loaded != null && isRemoteRevocationPending(loaded)) {
                initialized = true
                activeRecord = loaded
                resumePendingRevocationCleanupLocked(loaded, SchoolAccountErrorCode.BACKEND_NOT_READY)
                return
            }
            // The local token is bound to its configured origin. Fence it and clear all
            // owner-scoped data before allowing a token to be used with a new endpoint.
            startManagedCleanupLocked(
                scope = AccountDataScope.PERSONAL_DATA,
                reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                previousGeneration = activeRecord?.let { AccountGeneration(it.credentialGeneration) }
                    ?: AccountGeneration.None,
            )
            initialized = true
            return
        }
        initialized = true
        activeRecord = loaded
        if (loaded == null) {
            publishNotConfigured()
        } else if (isRemoteRevocationPending(loaded)) {
            resumePendingRevocationCleanupLocked(loaded, error = null)
        } else {
            publishActive(loaded, expiresAtEpochMillis = null)
        }
    }

    override suspend fun bind(
        loginName: String,
        password: CharArray,
        acceptCustody: Boolean,
    ): SchoolAccountWriteResult = withContext(ioDispatcher) {
        try {
            if (!acceptCustody) return@withContext SchoolAccountWriteResult.Failure(
                SchoolAccountErrorCode.CUSTODY_REQUIRED,
            )
            if (loginName.isBlank() || loginName.length > MAX_LOGIN_NAME_CHARS) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT)
            }
            if (password.isEmpty() || password.size > MAX_PASSWORD_CHARS) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_PASSWORD)
            }
            if (backendOrigin.isNullOrBlank()) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BACKEND_NOT_READY)
            }
            val operation = when (val start = beginBinding()) {
                is BeginBindingResult.Ready -> start.operation
                is BeginBindingResult.Rejected -> return@withContext SchoolAccountWriteResult.Failure(start.error)
            }
            onSessionClosedSafely()
            val requestConfig = operation.config
            var issuedToken: String? = null
            try {
                val sessionResult = backendClient.createSession(
                    loginName = loginName.trim(),
                    password = password,
                    acceptCustody = true,
                    installationId = requestConfig.installationId,
                    platform = CampusPlatform.ANDROID,
                    kind = deviceKindProvider(),
                )
                val session = when (sessionResult) {
                    is BackendResult.Success -> sessionResult.value
                    BackendResult.NoContent -> return@withContext rollbackBinding(
                        operation,
                        SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    )
                    is BackendResult.Failure -> return@withContext rollbackBinding(
                        operation,
                        sessionResult.error.toAccountError(),
                    )
                }
                issuedToken = session.token
                if (!isUuid(session.deviceId)) {
                    revokeIssuedSession(session.token)
                    return@withContext rollbackBinding(operation, SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR)
                }
                val meResult = backendClient.getMe(session.token)
                val me = when (meResult) {
                    is BackendResult.Success -> meResult.value
                    BackendResult.NoContent -> {
                        revokeIssuedSession(session.token)
                        return@withContext rollbackBinding(operation, SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR)
                    }
                    is BackendResult.Failure -> {
                        revokeIssuedSession(session.token)
                        return@withContext rollbackBinding(operation, meResult.error.toAccountError())
                    }
                }
                if (me.state != CampusAccountState.ACTIVE || me.credentialGeneration <= 0 || !isUuid(me.accountId)) {
                    revokeIssuedSession(session.token)
                    val code = if (me.state == CampusAccountState.DISABLED) {
                        SchoolAccountErrorCode.TOKEN_INVALID
                    } else {
                        SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR
                    }
                    return@withContext rollbackBinding(operation, code)
                }
                if (!isBindingOperationCurrent(operation)) {
                    revokeIssuedSession(session.token)
                    return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
                }
                val newRecord = ManagedSessionRecord(
                    accessToken = session.token,
                    deviceId = session.deviceId,
                    accountId = me.accountId,
                    credentialGeneration = me.credentialGeneration,
                    state = me.state.name,
                )
                val old = operation.previousRecord
                val oldWasActive = old?.state == ManagedSessionState.ACTIVE.name
                val scope = if (oldWasActive && old?.accountId == newRecord.accountId) {
                    AccountDataScope.SESSIONS_ONLY
                } else {
                    AccountDataScope.PERSONAL_DATA
                }
                val reason = when {
                    old == null -> AccountInvalidationReason.FIRST_SAVE
                    oldWasActive && old.accountId == newRecord.accountId ->
                        AccountInvalidationReason.PASSWORD_UPDATED
                    else -> AccountInvalidationReason.ACCOUNT_SWITCHED
                }
                val commitError = operationMutex.withLock {
                    if (!isBindingOperationCurrent(operation)) return@withLock SchoolAccountErrorCode.BUSY
                    val invalidated = invalidateAll(
                        AccountInvalidationRequest(
                            reason = reason,
                            scope = scope,
                            previousGeneration = AccountGeneration(old?.credentialGeneration ?: 0),
                            nextGeneration = AccountGeneration(newRecord.credentialGeneration),
                        ),
                    )
                    if (!isBindingOperationCurrent(operation)) return@withLock SchoolAccountErrorCode.BUSY
                    if (!invalidated.ok) {
                        return@withLock if (persistFailedCleanupLocked(operation, scope, reason)) {
                            SchoolAccountErrorCode.CLEANUP_FAILED
                        } else {
                            SchoolAccountErrorCode.BUSY
                        }
                    }
                    try {
                        // Serialize file commit with transaction fencing. A late A commit must
                        // never delete or overwrite a newer B session.
                        storage.saveSession(newRecord)
                    } catch (failure: ManagedStorageException) {
                        return@withLock failure.code
                    } catch (_: Exception) {
                        return@withLock SchoolAccountErrorCode.STORAGE_FAILED
                    }
                    activeRecord = newRecord
                    initialized = true
                    publishActive(newRecord, parseEpochMillis(session.expiresAt))
                    null
                }
                if (commitError != null) {
                    revokeIssuedSession(session.token)
                    if (commitError != SchoolAccountErrorCode.CLEANUP_FAILED &&
                        commitError != SchoolAccountErrorCode.BUSY
                    ) {
                        rollbackBinding(operation, commitError)
                    }
                    return@withContext SchoolAccountWriteResult.Failure(commitError)
                }
                SchoolAccountWriteResult.Success
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    issuedToken?.let { revokeIssuedSession(it) }
                    rollbackBinding(operation, SchoolAccountErrorCode.BUSY)
                }
                throw cancelled
            } catch (_: Exception) {
                issuedToken?.let { revokeIssuedSession(it) }
                rollbackBinding(operation, SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun updateManagedCredential(
        loginName: String,
        password: CharArray,
    ): SchoolAccountWriteResult = withContext(ioDispatcher) {
        try {
            if (loginName.isBlank() || loginName.length > MAX_LOGIN_NAME_CHARS) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT)
            }
            if (password.isEmpty() || password.size > MAX_PASSWORD_CHARS) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_PASSWORD)
            }
            if (backendOrigin.isNullOrBlank()) {
                return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BACKEND_NOT_READY)
            }
            val start = operationMutex.withLock {
                if (!initialized) restoreSessionLocked()
                if (hasPendingOrInFlightRevocationLocked()) return@withLock null
                captureSessionLease() to config?.transactionGeneration
            } ?: return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
            val (leaseAtStart, transactionAtStart) = start
            val result = backendClient.updateCredential(loginName.trim(), password)
            when (result) {
                BackendResult.NoContent, is BackendResult.Success -> {
                    if (leaseAtStart != null) {
                        // A late update for account A must not clear a session bound as B
                        // or overwrite a pending remote revocation.
                        val cleared = closeSessionAndClear(leaseAtStart, AccountInvalidationReason.PASSWORD_UPDATED)
                        if (_state.value.cleanupRequired) {
                            return@withContext SchoolAccountWriteResult.Failure(
                                SchoolAccountErrorCode.CLEANUP_FAILED,
                            )
                        }
                        if (!cleared) {
                            return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
                        }
                    } else {
                        val marker = operationMutex.withLock {
                            if (config?.transactionGeneration != transactionAtStart ||
                                hasPendingOrInFlightRevocationLocked()
                            ) {
                                return@withLock null
                            }
                            beginLocalCleanupLocked(
                                AccountDataScope.PERSONAL_DATA,
                                AccountInvalidationReason.PASSWORD_UPDATED,
                                AccountGeneration(_state.value.generation.value),
                            )
                        } ?: return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
                        if (!completeLocalCleanup(marker)) return@withContext cleanupFailure()
                    }
                    SchoolAccountWriteResult.Success
                }
                is BackendResult.Failure -> {
                    if (result.error.code == BackendErrorCode.NETWORK_TIMEOUT) {
                        leaseAtStart?.let {
                            closeSessionAndClear(it, AccountInvalidationReason.PASSWORD_UPDATED)
                        }
                    }
                    SchoolAccountWriteResult.Failure(result.error.toAccountError())
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun listDevices(): ManagedDeviceListResult = withContext(ioDispatcher) {
        val lease = captureSessionLease()
            ?: return@withContext ManagedDeviceListResult.Failure(SchoolAccountErrorCode.NOT_CONFIGURED)
        when (val result = backendClient.getDevices(lease.accessToken)) {
            is BackendResult.Success -> {
                if (!isSessionLeaseCurrent(lease)) {
                    ManagedDeviceListResult.Failure(SchoolAccountErrorCode.BUSY)
                } else {
                    ManagedDeviceListResult.Success(result.value.map { device ->
                        ManagedDevice(
                            deviceId = device.deviceId,
                            installationId = device.installationId,
                            platform = device.platform,
                            kind = device.kind,
                            expiresAtEpochMillis = parseEpochMillis(device.expiresAt) ?: 0L,
                        )
                    })
                }
            }
            BackendResult.NoContent -> ManagedDeviceListResult.Failure(SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR)
            is BackendResult.Failure -> {
                invalidateOnUnauthorized(lease, result.error)
                ManagedDeviceListResult.Failure(result.error.toAccountError())
            }
        }
    }

    override suspend fun revokeDevice(deviceId: String): SchoolAccountWriteResult = withContext(ioDispatcher) {
        if (!isUuid(deviceId)) return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT)
        val lease = captureSessionLease()
        val pendingOwnDevice = activeRecord?.let {
            it.state == ManagedSessionState.DEVICE_REVOKE_PENDING.name && it.deviceId == deviceId
        } == true
        if (lease?.deviceId == deviceId || pendingOwnDevice) {
            return@withContext revokeCurrentSession(RemoteRevocationAction.DEVICE, deviceId)
        }
        if (lease == null) return@withContext SchoolAccountWriteResult.Failure(
            if (_state.value.status == SchoolAccountStatus.UNBIND_PENDING ||
                _state.value.status == SchoolAccountStatus.SIGN_OUT_PENDING
            ) SchoolAccountErrorCode.BUSY else SchoolAccountErrorCode.NOT_CONFIGURED,
        )
        when (val result = backendClient.revokeDevice(lease.accessToken, deviceId)) {
            BackendResult.NoContent, is BackendResult.Success -> SchoolAccountWriteResult.Success
            is BackendResult.Failure -> {
                invalidateOnUnauthorized(lease, result.error)
                SchoolAccountWriteResult.Failure(result.error.toAccountError())
            }
        }
    }

    override suspend fun signOut(): SchoolAccountWriteResult = withContext(ioDispatcher) {
        revokeCurrentSession(RemoteRevocationAction.SIGN_OUT)
    }

    override suspend fun unbind(confirmed: Boolean): SchoolAccountWriteResult = withContext(ioDispatcher) {
        if (!confirmed) return@withContext SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CUSTODY_REQUIRED)
        revokeCurrentSession(RemoteRevocationAction.UNBIND)
    }

    override suspend fun retryCleanup(): SchoolAccountWriteResult = withContext(ioDispatcher) {
        operationMutex.withLock {
            val legacy = runCatching { storage.readMigrationMarker() }.getOrNull()
            if (legacy?.pending == true) return@withLock finishLegacyCredentialUpgradeLocked(legacy)
            val stored = activeRecord ?: runCatching { storage.loadSession() }.getOrNull()
            if (stored != null && isRemoteRevocationPending(stored)) {
                activeRecord = stored
                val existing = runCatching { storage.readCleanupMarker() }.getOrNull()
                if (existing == null && stored.personalDataCleared) {
                    publishPendingRevocation(stored, cleanupRequired = false, error = null)
                    return@withLock SchoolAccountWriteResult.Success
                }
                publishPendingRevocation(
                    stored,
                    cleanupRequired = true,
                    error = SchoolAccountErrorCode.CLEANUP_REQUIRED,
                )
                onSessionClosedSafely()
                val marker = existing ?: createPendingCleanupMarkerLocked(stored)
                    ?: return@withLock SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
                return@withLock finishManagedCleanupLocked(marker)
            }
            val marker = runCatching { storage.readCleanupMarker() }.getOrNull()
                ?: return@withLock SchoolAccountWriteResult.Success
            finishManagedCleanupLocked(marker)
        }
    }

    private suspend fun revokeCurrentSession(
        requestedAction: RemoteRevocationAction,
        requestedDeviceId: String? = null,
    ): SchoolAccountWriteResult {
        val preparation = operationMutex.withLock {
            if (remoteRevocationInFlight > 0) {
                return@withLock RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                )
            }
            prepareRemoteRevocationLocked(requestedAction, requestedDeviceId).also {
                if (it is RevocationPreparation.Ready) remoteRevocationInFlight++
            }
        }
        if (preparation is RevocationPreparation.Failure) return preparation.result
        val record = (preparation as RevocationPreparation.Ready).record
        return try {
            if (backendOrigin.isNullOrBlank() || config?.backendOrigin != normalizedBackendOrigin()) {
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BACKEND_NOT_READY)
            }
            val result = try {
                when (record.state) {
                    ManagedSessionState.UNBIND_PENDING.name -> backendClient.unbindAccount(record.accessToken)
                    ManagedSessionState.SIGN_OUT_PENDING.name -> backendClient.deleteSession(record.accessToken)
                    ManagedSessionState.DEVICE_REVOKE_PENDING.name ->
                        backendClient.revokeDevice(record.accessToken, record.deviceId)
                    else -> return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BackendResult.Failure(BackendFailure(BackendErrorCode.NETWORK_UNAVAILABLE))
            }
            when (result) {
                BackendResult.NoContent, is BackendResult.Success -> completeRemoteRevocation(record)
                is BackendResult.Failure -> {
                    if (isExplicitRevocationConfirmation(result.error)) {
                        completeRemoteRevocation(record)
                    } else {
                        val error = if (record.state == ManagedSessionState.UNBIND_PENDING.name &&
                            result.error.code in setOf(BackendErrorCode.NETWORK_TIMEOUT, BackendErrorCode.UPSTREAM_TIMEOUT)
                        ) {
                            SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN
                        } else {
                            result.error.toAccountError()
                        }
                        val stillCurrent = operationMutex.withLock {
                            if (!isPendingRecordCurrentLocked(record)) return@withLock false
                            publishPendingRevocation(record, cleanupRequired = false, error = error)
                            true
                        }
                        if (stillCurrent) SchoolAccountWriteResult.Failure(error)
                        else SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                operationMutex.withLock {
                    remoteRevocationInFlight = (remoteRevocationInFlight - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private suspend fun prepareRemoteRevocationLocked(
        requestedAction: RemoteRevocationAction,
        requestedDeviceId: String?,
    ): RevocationPreparation {
        if (!initialized) restoreSessionLocked()
        val record = activeRecord ?: runCatching { storage.loadSession() }.getOrNull()
            ?: return RevocationPreparation.Failure(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.NOT_CONFIGURED),
            )
        val alreadyPending = isRemoteRevocationPending(record)
        val targetState = if (alreadyPending) {
            if (!requestedAction.canRetry(record.state, requestedDeviceId, record.deviceId)) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                )
            }
            record.state
        } else {
            if (record.state != ManagedSessionState.ACTIVE.name || captureSessionLease() == null) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(
                        if (_state.value.cleanupRequired) SchoolAccountErrorCode.CLEANUP_REQUIRED
                        else SchoolAccountErrorCode.NOT_CONFIGURED,
                    ),
                )
            }
            if (requestedAction == RemoteRevocationAction.DEVICE && requestedDeviceId != record.deviceId) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT),
                )
            }
            requestedAction.recordState
        }

        var pending = if (alreadyPending) record else record.copy(
            state = targetState,
            personalDataCleared = false,
        )
        if (!alreadyPending) {
            if (backendOrigin.isNullOrBlank() || config?.backendOrigin != normalizedBackendOrigin()) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BACKEND_NOT_READY),
                )
            }
            try {
                storage.saveSession(pending)
            } catch (failure: ManagedStorageException) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(failure.code),
                )
            } catch (_: Exception) {
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
                )
            }
            activeRecord = pending
            publishPendingRevocation(
                pending,
                cleanupRequired = true,
                error = SchoolAccountErrorCode.CLEANUP_REQUIRED,
            )
            onSessionClosedSafely()
            try {
                advanceConfig()
            } catch (_: Exception) {
                publishPendingRevocation(
                    pending,
                    cleanupRequired = true,
                    error = SchoolAccountErrorCode.STORAGE_FAILED,
                )
                return RevocationPreparation.Failure(
                    SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
                )
            }
        } else {
            activeRecord = pending
            val existingMarker = runCatching { storage.readCleanupMarker() }.getOrNull()
            publishPendingRevocation(
                pending,
                cleanupRequired = !pending.personalDataCleared || existingMarker != null,
                error = if (!pending.personalDataCleared || existingMarker != null) {
                    SchoolAccountErrorCode.CLEANUP_REQUIRED
                } else {
                    null
                },
            )
            if (!pending.personalDataCleared || existingMarker != null) onSessionClosedSafely()
        }

        val existingMarker = runCatching { storage.readCleanupMarker() }.getOrNull()
        val marker = existingMarker ?: if (!pending.personalDataCleared) {
            createPendingCleanupMarkerLocked(pending)
        } else {
            null
        }
        if (marker != null) {
            val cleaned = finishManagedCleanupLocked(marker)
            if (cleaned != SchoolAccountWriteResult.Success) {
                return RevocationPreparation.Failure(cleaned)
            }
            pending = activeRecord ?: return RevocationPreparation.Failure(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
            )
        } else if (!pending.personalDataCleared) {
            publishPendingRevocation(
                pending,
                cleanupRequired = true,
                error = SchoolAccountErrorCode.CLEANUP_FAILED,
            )
            return RevocationPreparation.Failure(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED),
            )
        } else {
            publishPendingRevocation(pending, cleanupRequired = false, error = null)
        }
        return RevocationPreparation.Ready(pending)
    }

    private suspend fun completeRemoteRevocation(record: ManagedSessionRecord): SchoolAccountWriteResult =
        operationMutex.withLock {
            if (!isPendingRecordCurrentLocked(record)) {
                return@withLock SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
            }
            try {
                storage.clearSession()
            } catch (failure: ManagedStorageException) {
                publishPendingRevocation(record, cleanupRequired = false, error = failure.code)
                return@withLock SchoolAccountWriteResult.Failure(failure.code)
            } catch (_: Exception) {
                publishPendingRevocation(
                    record,
                    cleanupRequired = false,
                    error = SchoolAccountErrorCode.STORAGE_FAILED,
                )
                return@withLock SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
            }
            activeRecord = null
            try {
                storage.deleteCleanupMarker()
            } catch (_: Exception) {
                setCleanupState(SchoolAccountStatus.REMOVING, SchoolAccountErrorCode.CLEANUP_FAILED)
                return@withLock SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
            }
            publishNotConfigured()
            SchoolAccountWriteResult.Success
        }

    private fun createPendingCleanupMarkerLocked(record: ManagedSessionRecord): ManagedCleanupMarker? {
        if (record.personalDataCleared) return null
        val transactionGeneration = try {
            ensureConfig().transactionGeneration
        } catch (_: Exception) {
            return null
        }
        val marker = ManagedCleanupMarker(
            scope = AccountDataScope.PERSONAL_DATA.name,
            reason = AccountInvalidationReason.ACCOUNT_REMOVED.name,
            previousGeneration = record.credentialGeneration,
            nextGeneration = record.credentialGeneration + 1,
            transactionGeneration = transactionGeneration,
        )
        return runCatching { storage.writeCleanupMarker(marker) }.fold(
            onSuccess = { marker },
            onFailure = { null },
        )
    }

    private suspend fun resumePendingRevocationCleanupLocked(
        record: ManagedSessionRecord,
        error: SchoolAccountErrorCode?,
    ) {
        activeRecord = record
        val existing = runCatching { storage.readCleanupMarker() }.getOrNull()
        if (record.personalDataCleared && existing == null) {
            publishPendingRevocation(record, cleanupRequired = false, error = error)
            return
        }
        publishPendingRevocation(
            record,
            cleanupRequired = true,
            error = SchoolAccountErrorCode.CLEANUP_REQUIRED,
        )
        onSessionClosedSafely()
        val marker = existing ?: createPendingCleanupMarkerLocked(record)
        if (marker == null) {
            publishPendingRevocation(record, cleanupRequired = true, error = SchoolAccountErrorCode.CLEANUP_FAILED)
            return
        }
        finishManagedCleanupLocked(marker)
        if (error == SchoolAccountErrorCode.BACKEND_NOT_READY && activeRecord != null &&
            !_state.value.cleanupRequired
        ) {
            publishPendingRevocation(
                requireNotNull(activeRecord),
                cleanupRequired = _state.value.cleanupRequired,
                error = SchoolAccountErrorCode.BACKEND_NOT_READY,
            )
        }
    }

    private suspend fun isPendingRecordCurrentLocked(record: ManagedSessionRecord): Boolean {
        if (activeRecord != record || !isRemoteRevocationPending(record)) return false
        val persisted = try {
            storage.loadSession()
        } catch (_: Exception) {
            return false
        }
        return persisted == record
    }

    private fun isExplicitRevocationConfirmation(failure: BackendFailure): Boolean = when (failure.code) {
        BackendErrorCode.TOKEN_INVALID, BackendErrorCode.UNAUTHENTICATED -> failure.httpStatus == 401
        BackendErrorCode.DISABLED -> failure.httpStatus == 403
        else -> false
    }

    private fun publishPendingRevocation(
        record: ManagedSessionRecord,
        cleanupRequired: Boolean,
        error: SchoolAccountErrorCode?,
    ) {
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = revocationStatus(record),
            generation = AccountGeneration(record.credentialGeneration),
            cleanupRequired = cleanupRequired,
            errorCode = error,
            deviceId = record.deviceId,
            transactionGeneration = config?.transactionGeneration ?: 0,
        )
    }

    private fun revocationStatus(record: ManagedSessionRecord): SchoolAccountStatus =
        if (record.state == ManagedSessionState.UNBIND_PENDING.name) {
            SchoolAccountStatus.UNBIND_PENDING
        } else {
            SchoolAccountStatus.SIGN_OUT_PENDING
        }

    private fun isRemoteRevocationPending(record: ManagedSessionRecord): Boolean =
        record.state == ManagedSessionState.UNBIND_PENDING.name ||
            record.state == ManagedSessionState.SIGN_OUT_PENDING.name ||
            record.state == ManagedSessionState.DEVICE_REVOKE_PENDING.name

    /** No password is ever available from persistent state to the legacy auth adapters. */
    @Suppress("UNCHECKED_CAST")
    @OptIn(RestrictedSchoolCredentialUse::class)
    @RestrictedSchoolCredentialUse
    override suspend fun <T> withLoginCredential(
        block: suspend (SchoolLoginCredential) -> T,
    ): SchoolCredentialUseResult<T> =
        SchoolCredentialUseResult.Failure(SchoolCredentialFailure.NOT_CONFIGURED)

    /** Server-side student identity is never returned by the managed account endpoints. */
    override suspend fun verifyLoginName(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: AccountGeneration,
        candidateLoginName: String,
    ): SchoolAccountIdentityCheck = SchoolAccountIdentityCheck.UNAVAILABLE

    fun captureSessionLease(): ManagedSessionLease? {
        val record = activeRecord ?: return null
        val currentConfig = config ?: return null
        val currentState = _state.value
        if (currentState.status != SchoolAccountStatus.MANAGED_ACTIVE ||
            currentState.blocksPersonalSessions || backendOrigin.isNullOrBlank() ||
            currentConfig.backendOrigin != normalizedBackendOrigin() ||
            record.state != ACTIVE_STATE ||
            currentState.generation.value != record.credentialGeneration
        ) return null
        return ManagedSessionLease(
            accessToken = record.accessToken,
            accountId = SchoolAccountId(record.accountId),
            deviceId = record.deviceId,
            credentialGeneration = AccountGeneration(record.credentialGeneration),
            transactionGeneration = currentConfig.transactionGeneration,
        )
    }

    /** Non-secret stable identifier used only to spread this installation's widget cadence. */
    fun installationId(): String? = runCatching { ensureConfig().installationId }.getOrNull()

    fun hasUsableManagedSession(): Boolean = captureSessionLease() != null

    fun isSessionLeaseCurrent(lease: ManagedSessionLease): Boolean {
        val record = activeRecord ?: return false
        val currentConfig = config ?: return false
        return currentConfig.transactionGeneration == lease.transactionGeneration &&
            currentConfig.backendOrigin == normalizedBackendOrigin() &&
            record.accountId == lease.accountId.value &&
            record.deviceId == lease.deviceId &&
            record.credentialGeneration == lease.credentialGeneration.value &&
            record.accessToken == lease.accessToken &&
            _state.value.status == SchoolAccountStatus.MANAGED_ACTIVE &&
            !_state.value.blocksPersonalSessions
    }

    /** A delayed 401 may close only the exact session lease which received it. */
    suspend fun invalidateSessionIfCurrent(lease: ManagedSessionLease): Boolean = withContext(ioDispatcher) {
        closeSessionAndClear(lease, AccountInvalidationReason.CREDENTIAL_UNREADABLE)
    }

    private suspend fun invalidateOnUnauthorized(lease: ManagedSessionLease, failure: BackendFailure) {
        if (failure.code in setOf(
                BackendErrorCode.TOKEN_INVALID,
                BackendErrorCode.UNAUTHENTICATED,
                BackendErrorCode.DISABLED,
            )
        ) {
            closeSessionAndClear(lease, AccountInvalidationReason.CREDENTIAL_UNREADABLE)
        }
    }

    private suspend fun closeSessionAndClear(
        lease: ManagedSessionLease,
        reason: AccountInvalidationReason,
    ): Boolean {
        val marker = operationMutex.withLock {
            if (!isSessionLeaseCurrent(lease)) return@withLock null
            beginLocalCleanupLocked(
                scope = AccountDataScope.PERSONAL_DATA,
                reason = reason,
                previousGeneration = lease.credentialGeneration,
            )
        } ?: return false
        return completeLocalCleanup(marker)
    }

    private suspend fun beginBinding(): BeginBindingResult = operationMutex.withLock {
        if (!initialized) restoreSessionLocked()
        if (state.value.cleanupRequired ||
            !runCatching { storage.readMigrationMarker()?.pending == false }.getOrDefault(false) ||
            runCatching { storage.readCleanupMarker() != null }.getOrDefault(true)
        ) return@withLock BeginBindingResult.Rejected(SchoolAccountErrorCode.CLEANUP_REQUIRED)
        if (hasPendingOrInFlightRevocationLocked()) {
            return@withLock BeginBindingResult.Rejected(SchoolAccountErrorCode.BUSY)
        }
        val previous = activeRecord
        val nextConfig = advanceConfig()
        val operation = BindingOperation(nextConfig.transactionGeneration, nextConfig, previous)
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.SWITCHING,
            generation = AccountGeneration(previous?.credentialGeneration ?: 0),
            cleanupRequired = false,
            transactionGeneration = nextConfig.transactionGeneration,
        )
        BeginBindingResult.Ready(operation)
    }

    private fun hasPendingOrInFlightRevocationLocked(): Boolean {
        val persistedRecord = runCatching { storage.loadSession() }.getOrNull()
        return remoteRevocationInFlight > 0 ||
            _state.value.status == SchoolAccountStatus.UNBIND_PENDING ||
            _state.value.status == SchoolAccountStatus.SIGN_OUT_PENDING ||
            activeRecord?.let(::isRemoteRevocationPending) == true ||
            persistedRecord?.let(::isRemoteRevocationPending) == true
    }

    private suspend fun rollbackBinding(
        operation: BindingOperation,
        error: SchoolAccountErrorCode,
    ): SchoolAccountWriteResult {
        operationMutex.withLock {
            if (!isBindingOperationCurrent(operation)) return@withLock
            activeRecord = operation.previousRecord
            when {
                operation.previousRecord == null -> publishNotConfigured(error)
                isRemoteRevocationPending(operation.previousRecord) -> publishPendingRevocation(
                    operation.previousRecord,
                    cleanupRequired = !operation.previousRecord.personalDataCleared,
                    error = error,
                )
                else -> publishActive(operation.previousRecord, null, error)
            }
        }
        return SchoolAccountWriteResult.Failure(error)
    }

    private fun isBindingOperationCurrent(operation: BindingOperation): Boolean =
        config?.transactionGeneration == operation.transactionGeneration &&
            _state.value.status == SchoolAccountStatus.SWITCHING

    private suspend fun persistFailedCleanupLocked(
        operation: BindingOperation,
        scope: AccountDataScope,
        reason: AccountInvalidationReason,
    ): Boolean {
        if (!isBindingOperationCurrent(operation)) return false
        val pending = operation.previousRecord?.takeIf(::isRemoteRevocationPending)?.let {
            it.copy(personalDataCleared = false)
        }
        if (pending != null) {
            runCatching { storage.saveSession(pending) }
                .onFailure { return false }
        }
        if (!isBindingOperationCurrent(operation)) return false
        try {
            storage.writeCleanupMarker(
                ManagedCleanupMarker(
                    scope = scope.name,
                    reason = reason.name,
                    previousGeneration = operation.previousRecord?.credentialGeneration ?: 0,
                    nextGeneration = operation.previousRecord?.credentialGeneration?.plus(1) ?: 1,
                    transactionGeneration = operation.transactionGeneration,
                ),
            )
        } catch (_: Exception) {
            if (!isBindingOperationCurrent(operation)) return false
            activeRecord = pending
            _accountReference.value = null
            _studentIdentity.value = null
            _state.value = SchoolAccountState(
                status = pending?.let(::revocationStatus) ?: SchoolAccountStatus.REMOVING,
                generation = AccountGeneration(pending?.credentialGeneration ?: 0),
                cleanupRequired = true,
                errorCode = SchoolAccountErrorCode.STORAGE_FAILED,
                deviceId = pending?.deviceId,
                transactionGeneration = operation.transactionGeneration,
            )
            return true
        }
        if (!isBindingOperationCurrent(operation)) return false
        activeRecord = pending
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = pending?.let(::revocationStatus) ?: SchoolAccountStatus.REMOVING,
            generation = AccountGeneration(pending?.credentialGeneration ?: 0),
            cleanupRequired = true,
            errorCode = SchoolAccountErrorCode.CLEANUP_FAILED,
            deviceId = pending?.deviceId,
            transactionGeneration = operation.transactionGeneration,
        )
        return true
    }

    private fun beginLocalCleanupLocked(
        scope: AccountDataScope,
        reason: AccountInvalidationReason,
        previousGeneration: AccountGeneration,
    ): ManagedCleanupMarker {
        val nextConfig = advanceConfig()
        val nextGeneration = AccountGeneration(
            (activeRecord?.credentialGeneration ?: previousGeneration.value).coerceAtLeast(0) + 1,
        )
        val marker = ManagedCleanupMarker(
            scope = scope.name,
            reason = reason.name,
            previousGeneration = previousGeneration.value,
            nextGeneration = nextGeneration.value,
            transactionGeneration = nextConfig.transactionGeneration,
        )
        storage.writeCleanupMarker(marker)
        activeRecord = null
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.REMOVING,
            generation = previousGeneration,
            cleanupRequired = true,
            errorCode = SchoolAccountErrorCode.CLEANUP_REQUIRED,
            transactionGeneration = nextConfig.transactionGeneration,
        )
        onSessionClosedSafely()
        runCatching { storage.clearSession() }
        return marker
    }

    private suspend fun completeLocalCleanup(marker: ManagedCleanupMarker): Boolean = operationMutex.withLock {
        finishManagedCleanupLocked(marker) == SchoolAccountWriteResult.Success
    }

    private suspend fun finishManagedCleanupLocked(
        marker: ManagedCleanupMarker,
    ): SchoolAccountWriteResult {
        if (marker.transactionGeneration != (config?.transactionGeneration ?: marker.transactionGeneration)) {
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY)
        }
        val pendingRecord = activeRecord?.takeIf(::isRemoteRevocationPending)
        if (pendingRecord == null) {
            activeRecord = null
            runCatching { storage.clearSession() }.getOrElse { return cleanupFailure() }
        } else {
            activeRecord = pendingRecord
        }
        val request = AccountInvalidationRequest(
            reason = AccountInvalidationReason.entries.firstOrNull { it.name == marker.reason }
                ?: AccountInvalidationReason.CREDENTIAL_UNREADABLE,
            scope = AccountDataScope.entries.firstOrNull { it.name == marker.scope }
                ?: AccountDataScope.PERSONAL_DATA,
            previousGeneration = AccountGeneration(marker.previousGeneration),
            nextGeneration = AccountGeneration(marker.nextGeneration),
        )
        if (pendingRecord?.personalDataCleared != true) {
            val result = invalidateAll(request)
            if (!result.ok) {
                if (pendingRecord == null) {
                    setCleanupState(SchoolAccountStatus.REMOVING, SchoolAccountErrorCode.CLEANUP_FAILED)
                } else {
                    publishPendingRevocation(
                        pendingRecord,
                        cleanupRequired = true,
                        error = SchoolAccountErrorCode.CLEANUP_FAILED,
                    )
                }
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
            }
            if (pendingRecord != null) {
                val clearedRecord = pendingRecord.copy(personalDataCleared = true)
                try {
                    storage.saveSession(clearedRecord)
                } catch (failure: ManagedStorageException) {
                    publishPendingRevocation(
                        pendingRecord,
                        cleanupRequired = true,
                        error = failure.code,
                    )
                    return SchoolAccountWriteResult.Failure(failure.code)
                } catch (_: Exception) {
                    publishPendingRevocation(
                        pendingRecord,
                        cleanupRequired = true,
                        error = SchoolAccountErrorCode.STORAGE_FAILED,
                    )
                    return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
                }
                activeRecord = clearedRecord
            }
        }
        if (pendingRecord == null && config?.backendOrigin != normalizedBackendOrigin()) {
            val currentConfig = config ?: return cleanupFailure()
            config = runCatching {
                storage.updateBackendOrigin(currentConfig, backendOrigin)
            }.getOrElse { return cleanupFailure() }
        }
        runCatching { storage.deleteCleanupMarker() }.getOrElse { return cleanupFailure() }
        if (pendingRecord != null) {
            val current = activeRecord ?: pendingRecord.copy(personalDataCleared = true)
            publishPendingRevocation(
                current,
                cleanupRequired = false,
                error = if (config?.backendOrigin != normalizedBackendOrigin()) {
                    SchoolAccountErrorCode.BACKEND_NOT_READY
                } else {
                    null
                },
            )
        } else {
            publishNotConfigured()
        }
        return SchoolAccountWriteResult.Success
    }

    private suspend fun finishLegacyCredentialUpgradeLocked(
        marker: LegacyPasswordCleanupMarker,
    ): SchoolAccountWriteResult {
        if (!legacyPurger.purge()) return cleanupFailure()
        runCatching { storage.clearSession() }.getOrElse { return cleanupFailure() }
        activeRecord = null
        val current = ensureConfig()
        val result = invalidateAll(
            AccountInvalidationRequest(
                reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                scope = AccountDataScope.PERSONAL_DATA,
                previousGeneration = AccountGeneration.None,
                nextGeneration = AccountGeneration(current.transactionGeneration.coerceAtLeast(1)),
            ),
        )
        if (!result.ok) return cleanupFailure()
        config = runCatching { storage.updateBackendOrigin(current, backendOrigin) }
            .getOrElse { return cleanupFailure() }
        runCatching {
            storage.writeMigrationMarker(
                marker.copy(pending = false, transactionGeneration = current.transactionGeneration),
            )
            storage.deleteCleanupMarker()
        }.getOrElse { return cleanupFailure() }
        initialized = true
        publishNotConfigured()
        return SchoolAccountWriteResult.Success
    }

    private suspend fun startManagedCleanupLocked(
        scope: AccountDataScope,
        reason: AccountInvalidationReason,
        previousGeneration: AccountGeneration,
    ) {
        val marker = beginLocalCleanupLocked(scope, reason, previousGeneration)
        finishManagedCleanupLocked(marker)
    }

    private suspend fun invalidateAll(request: AccountInvalidationRequest): AccountInvalidationResult {
        for (invalidator in invalidators) {
            val result = try {
                invalidator.invalidate(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AccountInvalidationResult.failure(AccountInvalidationFailure.UNAVAILABLE)
            }
            if (!result.ok) return result
        }
        return AccountInvalidationResult.Success
    }

    private suspend fun revokeIssuedSession(token: String) {
        // A failed /me or local commit revokes only the just-issued device session.
        runCatching { backendClient.deleteSession(token) }
    }

    private fun publishActive(
        record: ManagedSessionRecord,
        expiresAtEpochMillis: Long?,
        error: SchoolAccountErrorCode? = if (backendOrigin.isNullOrBlank()) {
            SchoolAccountErrorCode.BACKEND_NOT_READY
        } else {
            null
        },
    ) {
        val generation = AccountGeneration(record.credentialGeneration)
        val reference = SchoolAccountReference(SchoolAccountId(record.accountId), generation)
        _studentIdentity.value = null
        _accountReference.value = reference
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.MANAGED_ACTIVE,
            generation = generation,
            cleanupRequired = false,
            errorCode = error,
            deviceId = record.deviceId,
            sessionExpiresAtEpochMillis = expiresAtEpochMillis,
            transactionGeneration = config?.transactionGeneration ?: 0,
        )
    }

    private fun publishNotConfigured(error: SchoolAccountErrorCode? = null) {
        val configValue = runCatching { ensureConfig() }.getOrNull()
        val readyError = error ?: if (backendOrigin.isNullOrBlank()) {
            SchoolAccountErrorCode.BACKEND_NOT_READY
        } else {
            null
        }
        activeRecord = null
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = if (backendOrigin.isNullOrBlank()) {
                SchoolAccountStatus.BACKEND_NOT_READY
            } else {
                SchoolAccountStatus.NOT_CONFIGURED
            },
            errorCode = readyError,
            transactionGeneration = configValue?.transactionGeneration ?: 0,
        )
    }

    private fun setCleanupState(status: SchoolAccountStatus, error: SchoolAccountErrorCode) {
        activeRecord = null
        _accountReference.value = null
        _studentIdentity.value = null
        _state.value = SchoolAccountState(
            status = status,
            cleanupRequired = true,
            errorCode = error,
            transactionGeneration = config?.transactionGeneration ?: 0,
        )
    }

    private fun advanceConfig(): ManagedSessionConfig = synchronized(configLock) {
        val current = ensureConfigLocked()
        storage.advanceTransaction(current).also { config = it }
    }

    private fun ensureConfig(): ManagedSessionConfig = synchronized(configLock) { ensureConfigLocked() }

    private fun ensureConfigLocked(): ManagedSessionConfig {
        config?.let { return it }
        return storage.loadOrCreateConfig(backendOrigin).also { config = it }
    }

    private fun normalizedBackendOrigin(): String = backendOrigin?.trim()?.trimEnd('/').orEmpty()

    private fun cleanupFailure(): SchoolAccountWriteResult.Failure {
        setCleanupState(SchoolAccountStatus.REMOVING, SchoolAccountErrorCode.CLEANUP_FAILED)
        return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
    }

    private fun onSessionClosedSafely() {
        runCatching(onSessionClosed)
    }

    private fun parseEpochMillis(raw: String?): Long? =
        raw?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private data class BindingOperation(
        val transactionGeneration: Long,
        val config: ManagedSessionConfig,
        val previousRecord: ManagedSessionRecord?,
    )

    private enum class RemoteRevocationAction(val recordState: String) {
        UNBIND(ManagedSessionState.UNBIND_PENDING.name),
        SIGN_OUT(ManagedSessionState.SIGN_OUT_PENDING.name),
        DEVICE(ManagedSessionState.DEVICE_REVOKE_PENDING.name);

        fun canRetry(recordState: String, requestedDeviceId: String?, ownDeviceId: String): Boolean = when (this) {
            UNBIND -> recordState == ManagedSessionState.UNBIND_PENDING.name
            SIGN_OUT -> recordState == ManagedSessionState.SIGN_OUT_PENDING.name ||
                recordState == ManagedSessionState.DEVICE_REVOKE_PENDING.name
            DEVICE -> recordState == ManagedSessionState.DEVICE_REVOKE_PENDING.name &&
                requestedDeviceId == ownDeviceId
        }
    }

    private sealed interface BeginBindingResult {
        data class Ready(val operation: BindingOperation) : BeginBindingResult

        data class Rejected(val error: SchoolAccountErrorCode) : BeginBindingResult
    }

    private sealed interface RevocationPreparation {
        data class Ready(val record: ManagedSessionRecord) : RevocationPreparation

        data class Failure(val result: SchoolAccountWriteResult) : RevocationPreparation
    }

    private fun BackendFailure.toAccountError(): SchoolAccountErrorCode = when (code) {
        BackendErrorCode.NOT_CONFIGURED, BackendErrorCode.INVALID_ORIGIN -> SchoolAccountErrorCode.BACKEND_NOT_READY
        BackendErrorCode.CUSTODY_ACCEPTANCE_REQUIRED -> SchoolAccountErrorCode.CUSTODY_REQUIRED
        BackendErrorCode.DISABLED -> SchoolAccountErrorCode.TOKEN_INVALID
        BackendErrorCode.NOT_FOUND, BackendErrorCode.INTERNAL_ERROR, BackendErrorCode.UNKNOWN ->
            SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE
        BackendErrorCode.NETWORK_TIMEOUT -> SchoolAccountErrorCode.UPSTREAM_TIMEOUT
        BackendErrorCode.NETWORK_UNAVAILABLE, BackendErrorCode.TLS_ERROR ->
            SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE
        else -> SchoolAccountErrorCode.entries.firstOrNull { it.name == code.name }
            ?: SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE
    }

    private companion object {
        const val ACTIVE_STATE = "ACTIVE"
        const val MAX_LOGIN_NAME_CHARS = 128
        const val MAX_PASSWORD_CHARS = 512
    }
}
