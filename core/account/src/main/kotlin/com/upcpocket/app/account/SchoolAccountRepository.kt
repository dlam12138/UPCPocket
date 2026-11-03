package com.upcpocket.app.account

import android.content.Context
import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationReason
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.platform.AccountMask
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.platform.crypto.KeystoreAesGcmCrypto
import java.io.File
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 统一学校账号的本机实现。
 *
 * 边界：
 *  - 账号设置页传入密码，仓库只在本机加密后写入；不保留长期明文密码，也不把
 *    密码放进 [SchoolAccountState]、结果、日志或异常。
 *  - 保存成功只表示本机密文版本已提交，状态固定为 `SAVED_UNVERIFIED`。
 *  - 换号/删号/凭据不可读时先把代次与清理状态落盘，再调用业务失效器，最后才
 *    发布新状态；进程在任意一步退出都可以在下次 [restore] 时继续。
 *
 * 多文件提交协议：
 *  1. 把新凭据完整加密到 pending 文件；
 *  2. 原子写 transition journal；
 *  3. 原子写状态为 UPDATING/SWITCHING/REMOVING；
 *  4. 串行执行可重复的业务失效；
 *  5. 把 pending 提升为正式凭据（或删除旧凭据），写最终状态，最后删 journal。
 * 每个文件都由 AtomicFile 独立提交；journal 是跨文件恢复的事实来源。
 */
class SchoolAccountRepository internal constructor(
    directory: File,
    private val crypto: ByteCrypto,
    private val fileStore: AccountFileStore,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val invalidators: List<SchoolAccountInvalidator>,
    private val maskAccount: (String) -> String,
) : SchoolAccountController {

    constructor(
        context: Context,
        invalidators: List<SchoolAccountInvalidator> = emptyList(),
    ) : this(
        directory = File(context.applicationContext.noBackupFilesDir, ACCOUNT_DIRECTORY),
        crypto = KeystoreAesGcmCrypto(AccountFormat.KEY_ALIAS),
        fileStore = AtomicAccountFileStore(
            File(context.applicationContext.noBackupFilesDir, ACCOUNT_DIRECTORY),
        ),
        clock = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO,
        invalidators = invalidators,
        maskAccount = AccountMask::mask,
    )

    /** 仅用于隔离目录的设备测试；生产实例使用上面的 Context 构造器。 */
    constructor(
        directory: File,
        invalidators: List<SchoolAccountInvalidator> = emptyList(),
    ) : this(
        directory = directory,
        crypto = KeystoreAesGcmCrypto(AccountFormat.KEY_ALIAS),
        fileStore = AtomicAccountFileStore(directory),
        clock = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO,
        invalidators = invalidators,
        maskAccount = AccountMask::mask,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val cipher = CredentialCipher(crypto, json)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SchoolAccountState())
    private var initialized = false
    private var currentAccount: String? = null
    private var currentGeneration = AccountGeneration.None

    override val state: StateFlow<SchoolAccountState> = _state.asStateFlow()

    override suspend fun restore() {
        withContext(ioDispatcher) {
            mutex.withLock {
                withContext(NonCancellable) {
                    restoreLocked()
                }
            }
        }
    }

    override suspend fun save(account: String?, password: CharArray): SchoolAccountWriteResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                withContext(NonCancellable) {
                    saveLocked(account, password)
                }
            }
        }

    override suspend fun delete(): SchoolAccountWriteResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                withContext(NonCancellable) {
                    deleteLocked()
                }
            }
        }

    override suspend fun retryCleanup(): SchoolAccountWriteResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                withContext(NonCancellable) {
                    retryCleanupLocked()
                }
            }
        }

    // ── restore ───────────────────────────────────────────────────────────

    private suspend fun restoreLocked() {
        if (!initialized) {
            initialized = true
            val persisted = try {
                readPersistedState()
            } catch (_: Exception) {
                beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                return
            }

            if (persisted == null) {
                val journal = try {
                    readJournalOrNull()
                } catch (_: Exception) {
                    beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                    return
                }
                if (journal != null) {
                    resumeJournalLocked(journal)
                } else {
                    deleteQuietly(AccountFormat.PENDING_FILE)
                    if (fileStore.exists(AccountFormat.ACCOUNT_FILE)) {
                        beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                    } else {
                        currentGeneration = AccountGeneration.None
                        currentAccount = null
                        publishNotConfigured(AccountGeneration.None)
                    }
                }
                return
            }

            currentGeneration = AccountGeneration(persisted.generation)
            val status = persisted.persistedStatus()
            if (status == null) {
                beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                return
            }

            val journal = try {
                readJournalOrNull()
            } catch (_: Exception) {
                beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                return
            }
            if (journal != null) {
                resumeJournalLocked(journal)
                return
            }
            // 崩溃可能发生在 pending 写入之后、journal 之前；这份版本从未提交，
            // 旧正式版本仍在，直接丢弃，避免留下不可见的旧/新密文副本。
            deleteQuietly(AccountFormat.PENDING_FILE)

            when (status) {
                PersistedStatus.NOT_CONFIGURED -> {
                    currentAccount = null
                    publishNotConfigured(currentGeneration)
                }

                PersistedStatus.ACTIVE -> loadActiveLocked(persisted)

                PersistedStatus.NEEDS_RECONFIGURATION -> {
                    currentAccount = null
                    val error = persisted.errorCode?.toErrorCodeOrNull()
                        ?: SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE
                    publishNeedsReconfiguration(error, persisted.cleanupRequired)
                    if (persisted.cleanupRequired) {
                        beginRecoveryLocked(error)
                    }
                }

                PersistedStatus.UPDATING,
                PersistedStatus.SWITCHING,
                PersistedStatus.REMOVING,
                -> {
                    // 过渡状态没有 journal 不是正常提交路径；不能把半完成状态
                    // 当成正常账号，直接进入不可读恢复。
                    beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                }
            }
            return
        }

        // 已初始化后仍要检查 journal：保存/删除可能在上一次调用末尾留下未完成的
        // 清理；下次进入应用时继续。
        val journal = try {
            readJournalOrNull()
        } catch (_: Exception) {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
            return
        }
        if (journal != null) resumeJournalLocked(journal)
    }

    private suspend fun loadActiveLocked(
        persisted: PersistedAccountState,
    ): CredentialPayload? {
        val bytes = try {
            fileStore.read(AccountFormat.ACCOUNT_FILE, AccountFormat.MAX_CREDENTIAL_BYTES)
        } catch (_: Exception) {
            null
        }
        if (bytes == null) {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE)
            return null
        }
        val payload = try {
            cipher.open(bytes)
        } catch (failure: AccountReadException) {
            beginRecoveryLocked(failure.code)
            return null
        } catch (_: Exception) {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
            return null
        }
        if (payload.generation != persisted.generation ||
            payload.account.isBlank() ||
            payload.password.isEmpty()
        ) {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
            return null
        }
        currentAccount = payload.account
        currentGeneration = AccountGeneration(payload.generation)
        publishSaved(payload)
        return payload
    }

    private fun publishSaved(payload: CredentialPayload) {
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.SAVED_UNVERIFIED,
            generation = AccountGeneration(payload.generation),
            maskedAccount = maskAccount(payload.account),
            savedAtEpochMillis = payload.savedAt,
            cleanupRequired = false,
            errorCode = null,
        )
    }

    private fun publishNotConfigured(generation: AccountGeneration) {
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.NOT_CONFIGURED,
            generation = generation,
            maskedAccount = null,
            savedAtEpochMillis = null,
            cleanupRequired = false,
            errorCode = null,
        )
    }

    private fun publishNeedsReconfiguration(
        code: SchoolAccountErrorCode,
        cleanupRequired: Boolean,
    ) {
        _state.value = SchoolAccountState(
            status = SchoolAccountStatus.NEEDS_RECONFIGURATION,
            generation = currentGeneration,
            maskedAccount = null,
            savedAtEpochMillis = null,
            cleanupRequired = cleanupRequired,
            errorCode = code,
        )
    }

    // ── save ──────────────────────────────────────────────────────────────

    private suspend fun saveLocked(
        account: String?,
        password: CharArray,
    ): SchoolAccountWriteResult {
        ensureRestoredLocked()
        if (_state.value.cleanupRequired) {
            password.fill('\u0000')
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_REQUIRED)
        }
        val normalizedAccount = normalizeAccount(account ?: currentAccount)
        if (normalizedAccount == null) {
            password.fill('\u0000')
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT)
        }
        if (password.isEmpty() || password.size > MAX_PASSWORD_CHARS) {
            password.fill('\u0000')
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_PASSWORD)
        }
        if (password.any { it == '\u0000' }) {
            password.fill('\u0000')
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_PASSWORD)
        }

        val previous = currentAccount
        val switching = previous != null && previous != normalizedAccount
        val sameAccount = previous != null && previous == normalizedAccount
        val reason = when {
            switching -> AccountInvalidationReason.ACCOUNT_SWITCHED
            sameAccount -> AccountInvalidationReason.PASSWORD_UPDATED
            _state.value.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                AccountInvalidationReason.CREDENTIAL_UNREADABLE
            else -> AccountInvalidationReason.FIRST_SAVE
        }
        val scope = when (reason) {
            AccountInvalidationReason.ACCOUNT_SWITCHED,
            AccountInvalidationReason.CREDENTIAL_UNREADABLE,
            -> AccountDataScope.PERSONAL_DATA
            AccountInvalidationReason.PASSWORD_UPDATED -> AccountDataScope.SESSIONS_ONLY
            AccountInvalidationReason.FIRST_SAVE -> null
            AccountInvalidationReason.ACCOUNT_REMOVED -> AccountDataScope.PERSONAL_DATA
        }
        val sourceGeneration = currentGeneration
        val targetGeneration = sourceGeneration.incremented()
        val savedAt = clock().coerceAtLeast(1L)

        val payload = CredentialPayload(
            generation = targetGeneration.value,
            savedAt = savedAt,
            account = normalizedAccount,
            password = password.concatToString(),
        )
        try {
            val pendingBytes = try {
                cipher.seal(payload)
            } catch (_: AccountStorageException) {
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.ENCRYPTION_FAILED)
            } catch (_: Exception) {
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.ENCRYPTION_FAILED)
            }

            try {
                fileStore.write(AccountFormat.PENDING_FILE, pendingBytes)
            } catch (_: Exception) {
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
            }

            val journal = TransitionJournal(
                operation = TransitionOperation.SAVE.name,
                reason = reason.name,
                sourceGeneration = sourceGeneration.value,
                targetGeneration = targetGeneration.value,
                scope = scope?.name,
                pendingCredential = true,
            )
            try {
                writeJournal(journal)
            } catch (_: Exception) {
                deleteQuietly(AccountFormat.PENDING_FILE)
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
            }

            val transition = SchoolAccountState(
                status = if (switching) {
                    SchoolAccountStatus.SWITCHING
                } else {
                    SchoolAccountStatus.UPDATING
                },
                generation = targetGeneration,
                maskedAccount = null,
                savedAtEpochMillis = null,
                cleanupRequired = true,
                errorCode = null,
            )
            try {
                writePersistedState(
                    PersistedAccountState(
                        status = if (switching) {
                            PersistedStatus.SWITCHING.name
                        } else {
                            PersistedStatus.UPDATING.name
                        },
                        generation = targetGeneration.value,
                        cleanupRequired = true,
                    ),
                )
            } catch (_: Exception) {
                deleteQuietly(AccountFormat.PENDING_FILE)
                deleteQuietly(AccountFormat.JOURNAL_FILE)
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
            }
            currentGeneration = targetGeneration
            _state.value = transition

            if (scope != null) {
                val invalidated = invalidateLocked(
                    journal = journal,
                    reason = reason,
                    scope = scope,
                )
                if (!invalidated) {
                    markCleanupFailed(transition)
                    return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
                }
            }

            // 破坏性失效已经完成，先把它作为已提交 tombstone 原子写回。之后即使
            // 进程退出或收尾删文件失败，重启也只继续收尾，不再重放 PERSONAL_DATA。
            val committedJournal = markJournalCommittedLocked(journal)
                ?: run {
                    _state.value = transition.copy(
                        cleanupRequired = true,
                        errorCode = SchoolAccountErrorCode.STORAGE_FAILED,
                    )
                    return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
                }
            if (promotePendingLocked(committedJournal)) {
                return SchoolAccountWriteResult.Success
            }
            currentGeneration = targetGeneration
            _state.value = transition.copy(
                cleanupRequired = true,
                errorCode = SchoolAccountErrorCode.STORAGE_FAILED,
            )
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun promotePendingLocked(journal: TransitionJournal): Boolean {
        val pending = try {
            fileStore.read(AccountFormat.PENDING_FILE, AccountFormat.MAX_CREDENTIAL_BYTES)
        } catch (_: Exception) {
            null
        }
        val payload = if (pending == null) {
            // 崩溃可能发生在 pending 删除之后。只有正式文件的代次已经匹配时，
            // 才能把它当作已提升，避免把旧账号误认为新账号。
            val existing = try {
                fileStore.read(AccountFormat.ACCOUNT_FILE, AccountFormat.MAX_CREDENTIAL_BYTES)
            } catch (_: Exception) {
                null
            }
            val decoded = existing?.let {
                try {
                    cipher.open(it)
                } catch (_: Exception) {
                    null
                }
            }
            if (decoded?.generation != journal.targetGeneration) return false
            decoded
        } else {
            try {
                cipher.open(pending)
            } catch (_: Exception) {
                return false
            }
        }
        if (payload.generation != journal.targetGeneration ||
            payload.account.isBlank() || payload.password.isEmpty()
        ) {
            return false
        }

        if (pending != null) {
            try {
                fileStore.write(AccountFormat.ACCOUNT_FILE, pending)
            } catch (_: Exception) {
                return false
            }
            // 正式密文已经提升；pending 只是同一新版本的副本。删除失败时保持
            // UPDATING/cleanupRequired，让下次启动只重试收尾而不重放失效。
            try {
                fileStore.delete(AccountFormat.PENDING_FILE)
            } catch (_: Exception) {
                return false
            }
        }

        try {
            writePersistedState(
                PersistedAccountState(
                    status = PersistedStatus.ACTIVE.name,
                    generation = payload.generation,
                    savedAt = payload.savedAt,
                    cleanupRequired = false,
                ),
            )
        } catch (_: Exception) {
            return false
        }

        currentAccount = payload.account
        currentGeneration = AccountGeneration(payload.generation)
        publishSaved(payload)
        // final state 已提交；journal 是已标记的 tombstone，删除失败可以下次启动
        // 继续删，不能把这次已完成的保存误报为失败或重新执行失效。
        deleteJournalTombstoneBestEffort()
        return true
    }

    // ── delete ────────────────────────────────────────────────────────────

    private suspend fun deleteLocked(): SchoolAccountWriteResult {
        ensureRestoredLocked()
        if (_state.value.cleanupRequired) {
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_REQUIRED)
        }
        if (currentAccount == null && _state.value.status == SchoolAccountStatus.NOT_CONFIGURED) {
            return SchoolAccountWriteResult.Success
        }
        val sourceGeneration = currentGeneration
        val targetGeneration = sourceGeneration.incremented()
        val journal = TransitionJournal(
            operation = TransitionOperation.DELETE.name,
            reason = AccountInvalidationReason.ACCOUNT_REMOVED.name,
            sourceGeneration = sourceGeneration.value,
            targetGeneration = targetGeneration.value,
            scope = AccountDataScope.PERSONAL_DATA.name,
            pendingCredential = false,
        )
        try {
            writeJournal(journal)
        } catch (_: Exception) {
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
        }
        val transition = SchoolAccountState(
            status = SchoolAccountStatus.REMOVING,
            generation = targetGeneration,
            maskedAccount = null,
            savedAtEpochMillis = null,
            cleanupRequired = true,
            errorCode = null,
        )
        try {
            writePersistedState(
                PersistedAccountState(
                    status = PersistedStatus.REMOVING.name,
                    generation = targetGeneration.value,
                    cleanupRequired = true,
                ),
            )
        } catch (_: Exception) {
            deleteQuietly(AccountFormat.JOURNAL_FILE)
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
        }
        currentGeneration = targetGeneration
        _state.value = transition
        if (!invalidateLocked(
                journal = journal,
                reason = AccountInvalidationReason.ACCOUNT_REMOVED,
                scope = AccountDataScope.PERSONAL_DATA,
            )
        ) {
            markCleanupFailed(transition)
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
        }
        val committedJournal = markJournalCommittedLocked(journal)
            ?: run {
                _state.value = transition.copy(errorCode = SchoolAccountErrorCode.STORAGE_FAILED)
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
            }
        if (finalizeDeleteLocked(committedJournal)) {
            return SchoolAccountWriteResult.Success
        }
        _state.value = transition.copy(errorCode = SchoolAccountErrorCode.STORAGE_FAILED)
        return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
    }

    private fun finalizeDeleteLocked(journal: TransitionJournal): Boolean {
        return try {
            fileStore.delete(AccountFormat.ACCOUNT_FILE)
            fileStore.delete(AccountFormat.PENDING_FILE)
            writePersistedState(
                PersistedAccountState(
                    status = PersistedStatus.NOT_CONFIGURED.name,
                    generation = journal.targetGeneration,
                    cleanupRequired = false,
                ),
            )
            currentAccount = null
            currentGeneration = AccountGeneration(journal.targetGeneration)
            publishNotConfigured(currentGeneration)
            deleteJournalTombstoneBestEffort()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── recovery ──────────────────────────────────────────────────────────

    private suspend fun beginRecoveryLocked(code: SchoolAccountErrorCode) {
        val sourceGeneration = currentGeneration
        val targetGeneration = sourceGeneration.incremented()
        val journal = TransitionJournal(
            operation = TransitionOperation.RECOVER.name,
            reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE.name,
            sourceGeneration = sourceGeneration.value,
            targetGeneration = targetGeneration.value,
            scope = AccountDataScope.PERSONAL_DATA.name,
            pendingCredential = false,
            errorCode = code.name,
        )
        val transition = SchoolAccountState(
            status = SchoolAccountStatus.NEEDS_RECONFIGURATION,
            generation = targetGeneration,
            maskedAccount = null,
            savedAtEpochMillis = null,
            cleanupRequired = true,
            errorCode = code,
        )
        currentAccount = null
        currentGeneration = targetGeneration
        try {
            writeJournal(journal)
            writePersistedState(
                PersistedAccountState(
                    status = PersistedStatus.NEEDS_RECONFIGURATION.name,
                    generation = targetGeneration.value,
                    cleanupRequired = true,
                    errorCode = code.name,
                ),
            )
        } catch (_: Exception) {
            _state.value = transition
            return
        }
        _state.value = transition
        if (!invalidateLocked(
                journal = journal,
                reason = AccountInvalidationReason.CREDENTIAL_UNREADABLE,
                scope = AccountDataScope.PERSONAL_DATA,
            )
        ) {
            markCleanupFailed(transition)
            return
        }
        val committedJournal = markJournalCommittedLocked(journal) ?: run {
            _state.value = transition.copy(errorCode = SchoolAccountErrorCode.STORAGE_FAILED)
            return
        }
        finalizeRecoveryLocked(committedJournal, code)
    }

    private fun finalizeRecoveryLocked(journal: TransitionJournal, code: SchoolAccountErrorCode): Boolean {
        return try {
            fileStore.delete(AccountFormat.ACCOUNT_FILE)
            fileStore.delete(AccountFormat.PENDING_FILE)
            writePersistedState(
                PersistedAccountState(
                    status = PersistedStatus.NEEDS_RECONFIGURATION.name,
                    generation = journal.targetGeneration,
                    cleanupRequired = false,
                    errorCode = code.name,
                ),
            )
            currentAccount = null
            currentGeneration = AccountGeneration(journal.targetGeneration)
            publishNeedsReconfiguration(code, cleanupRequired = false)
            deleteJournalTombstoneBestEffort()
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun retryCleanupLocked(): SchoolAccountWriteResult {
        ensureRestoredLocked()
        val journal = try {
            readJournalOrNull()
        } catch (_: Exception) {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
            return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
        }
        if (journal == null) {
            if (_state.value.cleanupRequired) {
                beginRecoveryLocked(
                    _state.value.errorCode ?: SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE,
                )
                return SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
            }
            return SchoolAccountWriteResult.Success
        }
        return if (resumeJournalLocked(journal)) {
            SchoolAccountWriteResult.Success
        } else {
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED)
        }
    }

    private suspend fun resumeJournalLocked(journal: TransitionJournal): Boolean {
        val operation = journal.parsedOperation() ?: run {
            beginRecoveryLocked(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
            return false
        }
        val reason = journal.parsedReason() ?: AccountInvalidationReason.CREDENTIAL_UNREADABLE
        val scope = journal.parsedScope()
        val targetGeneration = AccountGeneration(journal.targetGeneration)
        currentGeneration = targetGeneration
        val transition = when (operation) {
            TransitionOperation.SAVE -> SchoolAccountState(
                status = if (reason == AccountInvalidationReason.ACCOUNT_SWITCHED) {
                    SchoolAccountStatus.SWITCHING
                } else {
                    SchoolAccountStatus.UPDATING
                },
                generation = targetGeneration,
                cleanupRequired = true,
            )
            TransitionOperation.DELETE -> SchoolAccountState(
                status = SchoolAccountStatus.REMOVING,
                generation = targetGeneration,
                cleanupRequired = true,
            )
            TransitionOperation.RECOVER -> SchoolAccountState(
                status = SchoolAccountStatus.NEEDS_RECONFIGURATION,
                generation = targetGeneration,
                cleanupRequired = true,
                errorCode = journal.errorCode?.toErrorCodeOrNull()
                    ?: SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE,
            )
        }
        _state.value = transition

        var committedJournal = journal
        if (!journal.invalidationCompleted) {
            if (scope != null) {
                if (!invalidateLocked(journal, reason, scope)) {
                    markCleanupFailed(transition)
                    return false
                }
            }
            committedJournal = markJournalCommittedLocked(journal) ?: run {
                _state.value = transition.copy(errorCode = SchoolAccountErrorCode.STORAGE_FAILED)
                return false
            }
        }

        return when (operation) {
            TransitionOperation.SAVE -> {
                if (!promotePendingLocked(committedJournal)) {
                    _state.value = transition.copy(
                        errorCode = SchoolAccountErrorCode.STORAGE_FAILED,
                    )
                    false
                } else {
                    true
                }
            }
            TransitionOperation.DELETE -> finalizeDeleteLocked(committedJournal)
            TransitionOperation.RECOVER -> finalizeRecoveryLocked(
                committedJournal,
                journal.errorCode?.toErrorCodeOrNull()
                    ?: SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE,
            )
        }
    }

    private suspend fun invalidateLocked(
        journal: TransitionJournal,
        reason: AccountInvalidationReason,
        scope: AccountDataScope,
    ): Boolean {
        val request = AccountInvalidationRequest(
            reason = reason,
            scope = scope,
            previousGeneration = AccountGeneration(journal.sourceGeneration),
            nextGeneration = AccountGeneration(journal.targetGeneration),
        )
        invalidators.forEach { invalidator ->
            val result = try {
                invalidator.invalidate(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                AccountInvalidationResult.failure(AccountInvalidationFailure.UNAVAILABLE)
            }
            if (!result.ok) return false
        }
        return true
    }

    private fun markCleanupFailed(transition: SchoolAccountState) {
        _state.value = transition.copy(
            cleanupRequired = true,
            errorCode = SchoolAccountErrorCode.CLEANUP_FAILED,
        )
        runCatching {
            writePersistedState(
                PersistedAccountState(
                    status = when (transition.status) {
                        SchoolAccountStatus.REMOVING -> PersistedStatus.REMOVING.name
                        SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                            PersistedStatus.NEEDS_RECONFIGURATION.name
                        SchoolAccountStatus.SWITCHING -> PersistedStatus.SWITCHING.name
                        else -> PersistedStatus.UPDATING.name
                    },
                    generation = transition.generation.value,
                    cleanupRequired = true,
                    errorCode = SchoolAccountErrorCode.CLEANUP_FAILED.name,
                ),
            )
        }
    }

    // ── storage ───────────────────────────────────────────────────────────

    private suspend fun ensureRestoredLocked() {
        if (!initialized) {
            restoreLocked()
        }
    }

    private fun readPersistedState(): PersistedAccountState? {
        val bytes = fileStore.read(AccountFormat.STATE_FILE, AccountFormat.MAX_STATE_BYTES)
            ?: return null
        val parsed = try {
            json.decodeFromString(PersistedAccountState.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        if (parsed.format != AccountFormat.STATE_FORMAT ||
            parsed.version != AccountFormat.VERSION ||
            parsed.generation < 0 ||
            parsed.persistedStatus() == null
        ) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        return parsed
    }

    private fun writePersistedState(state: PersistedAccountState) {
        val bytes = json.encodeToString(PersistedAccountState.serializer(), state)
            .toByteArray(Charsets.UTF_8)
        fileStore.write(AccountFormat.STATE_FILE, bytes)
    }

    private fun readJournalOrNull(): TransitionJournal? {
        val bytes = fileStore.read(AccountFormat.JOURNAL_FILE, AccountFormat.MAX_JOURNAL_BYTES)
            ?: return null
        val parsed = try {
            json.decodeFromString(TransitionJournal.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        if (parsed.format != AccountFormat.JOURNAL_FORMAT ||
            parsed.version != AccountFormat.VERSION ||
            parsed.parsedOperation() == null ||
            parsed.targetGeneration < 0
        ) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        return parsed
    }

    private fun writeJournal(journal: TransitionJournal) {
        val bytes = json.encodeToString(TransitionJournal.serializer(), journal)
            .toByteArray(Charsets.UTF_8)
        fileStore.write(AccountFormat.JOURNAL_FILE, bytes)
    }

    /**
     * 失效完成后把 journal 标成已提交。写失败时必须保持清理中，不能进入提升/
     * 删除完成态；下一次启动会重新执行幂等失效（此时业务门闩仍关闭）。
     */
    private fun markJournalCommittedLocked(journal: TransitionJournal): TransitionJournal? {
        if (journal.invalidationCompleted) return journal
        val committed = journal.copy(invalidationCompleted = true)
        return try {
            writeJournal(committed)
            committed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * final state 已发布后删除已提交 journal。删除失败只留下可重试 tombstone，
     * 不能把它当失败回滚正式密文，也不能让重启重放破坏性失效。
     */
    private fun deleteJournalTombstoneBestEffort() {
        runCatching { fileStore.delete(AccountFormat.JOURNAL_FILE) }
    }

    private fun deleteQuietly(name: String) {
        runCatching { fileStore.delete(name) }
    }

    private fun normalizeAccount(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.length !in 1..MAX_ACCOUNT_CHARS) return null
        if (value.any { it.isISOControl() || it.isWhitespace() }) return null
        return value
    }

    private companion object {
        const val ACCOUNT_DIRECTORY = "school-account"
        const val MAX_ACCOUNT_CHARS = 128
        const val MAX_PASSWORD_CHARS = 512
    }
}
