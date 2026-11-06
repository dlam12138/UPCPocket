package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.chaoxing.ChaoxingException
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.chaoxing.ChaoxingReservationResult
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomPage
import com.upcpocket.app.data.chaoxing.ChaoxingSession
import com.upcpocket.app.data.chaoxing.ChaoxingSessionExpiredException
import com.upcpocket.app.data.chaoxing.ChaoxingTimeInterval
import com.upcpocket.app.data.chaoxing.ChaoxingUser
import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ChaoxingLoginResult {
    data object Success : ChaoxingLoginResult
    data class Failure(val reason: ChaoxingAuthFailure) : ChaoxingLoginResult
}

enum class ChaoxingAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    IDENTITY_MISMATCH,
    SESSION_EXPIRED,
    INVALID_SESSION,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    ACCESS_DENIED,
    PROTOCOL_ERROR,
    CLEANUP_IN_PROGRESS,
    COOKIE_CLEANUP_FAILED,
    STALE_WEB_ENTRY,
}

/**
 * 打开 Chaoxing 座位 WebView 时捕获的页面入口上下文。
 *
 * 不包含 Cookie/密码/页面正文；旧页面在退出、换号或清理期间的回调会因 epoch/
 * revision/entryId 不匹配而失败，不能重新绑定成新会话。
 */
data class ChaoxingWebEntryContext(
    val entryId: Long,
    val accountId: SchoolAccountId,
    val generation: AccountGeneration,
    val epoch: Long,
    val revision: Long,
)

enum class ChaoxingCleanupState {
    IDLE,
    CLEARING,
    FAILED,
}

sealed interface ChaoxingCleanupResult {
    data object Success : ChaoxingCleanupResult
    data class Failed(val reason: String) : ChaoxingCleanupResult
}

private data class ChaoxingLoginContext(
    val epoch: Long,
    val revision: Long,
    val accountId: SchoolAccountId,
    val generation: AccountGeneration,
)

/**
 * Chaoxing 座位内存会话。
 *
 * - Cookie 只来自应用内受限官方 WebView 的正常登录，不来自系统浏览器/临时 profile；
 * - 不持久化 Cookie；退出/换号清除内存会话并清理应用 WebView Cookie；
 * - 网络在状态锁外，发布在锁内；旧 WebView 回调由页面入口 epoch/revision 拒绝；
 * - 清理开始后立即封住新发布；清理失败/超时保持封禁，必须显式重试成功后才可再登录；
 * - 不把账号锁跨网络持有，避免 CAS/身份请求互相等待。
 */
class ChaoxingService(
    private val repository: ChaoxingRepository,
    private val accountGate: SchoolAccountGate,
    private val accountReference: SchoolAccountReferenceReader,
    private val identityVerifier: SchoolAccountIdentityVerifier,
    private val authenticator: ChaoxingAccountAuthenticator,
    private val clearCookies: suspend () -> Boolean = {
        ChaoxingWebCookies.clearAllAndAwait()
    },
) {
    private val _session = MutableStateFlow<ChaoxingSession?>(null)
    val session: StateFlow<ChaoxingSession?> = _session.asStateFlow()

    private val _cleanupState = MutableStateFlow(ChaoxingCleanupState.IDLE)
    val cleanupState: StateFlow<ChaoxingCleanupState> = _cleanupState.asStateFlow()

    val accountState: StateFlow<SchoolAccountState> get() = accountGate.state

    private val stateLock = Any()
    private val autoLoginMutex = Mutex()
    private var epoch = 0L
    private var revision = 0L
    private var entrySequence = 0L
    private var activeEntryId: Long? = null
    // 会话内记住“提交结果未知”的精确请求；关闭详情页/重建 ViewModel 后仍不能重发。
    private val unknownSubmissions = mutableSetOf<String>()
    private var cleanupInProgress = false
    private var cleanupFailed = false
    private var cleanupJob: CompletableDeferred<Boolean>? = null

    fun hasSavedAccount(): Boolean = synchronized(stateLock) {
        currentReferenceLocked() != null
    }

    /**
     * 在打开 WebView 前调用。清理进行中/清理失败/账号不可用时返回 null，页面不应开始登录。
     */
    fun beginWebLogin(): ChaoxingWebEntryContext? = synchronized(stateLock) {
        if (cleanupInProgress || cleanupFailed) return@synchronized null
        val reference = currentReferenceLocked() ?: return@synchronized null
        val entry = ChaoxingWebEntryContext(
            entryId = ++entrySequence,
            accountId = reference.accountId,
            generation = reference.credentialGeneration,
            epoch = epoch,
            revision = revision,
        )
        activeEntryId = entry.entryId
        entry
    }

    /**
     * 受限 WebView 登录完成后调用。Cookie 只作为参数传入，不写日志/不持久化。
     * 兼容测试/旧调用：在调用点创建入口；正式 UI 必须传入 beginWebLogin 的上下文。
     */
    suspend fun loginWithWebCookies(cookieHeader: String): ChaoxingLoginResult {
        val entry = beginWebLogin()
            ?: return ChaoxingLoginResult.Failure(
                if (synchronized(stateLock) { cleanupFailed }) {
                    ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED
                } else if (synchronized(stateLock) { cleanupInProgress }) {
                    ChaoxingAuthFailure.CLEANUP_IN_PROGRESS
                } else {
                    ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE
                },
            )
        return loginWithWebCookies(entry, cookieHeader)
    }

    suspend fun loginWithWebCookies(
        entry: ChaoxingWebEntryContext,
        cookieHeader: String,
    ): ChaoxingLoginResult {
        val earlyFailure = entryFailure(entry)
        if (earlyFailure != null) return ChaoxingLoginResult.Failure(earlyFailure)
        if (!isPlausibleCookieHeader(cookieHeader)) {
            return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.INVALID_SESSION)
        }
        val user: ChaoxingUser = try {
            repository.identity(cookieHeader)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: ChaoxingSessionExpiredException) {
            return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.SESSION_EXPIRED)
        } catch (_: ChaoxingException) {
            return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.PROTOCOL_ERROR)
        } catch (_: Exception) {
            return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.NETWORK_UNAVAILABLE)
        }
        val identity = try {
            identityVerifier.verifyLoginName(
                entry.accountId,
                entry.generation,
                user.sno,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
        }
        when (identity) {
            SchoolAccountIdentityCheck.MISMATCH ->
                return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.IDENTITY_MISMATCH)
            SchoolAccountIdentityCheck.CHANGED ->
                return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_CHANGED)
            SchoolAccountIdentityCheck.UNAVAILABLE ->
                return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
            SchoolAccountIdentityCheck.MATCH -> Unit
        }
        return synchronized(stateLock) {
            when {
                cleanupInProgress ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.CLEANUP_IN_PROGRESS)
                cleanupFailed ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED)
                activeEntryId != entry.entryId ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.STALE_WEB_ENTRY)
                !isCurrentLocked(entry) ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_CHANGED)
                else -> {
                    _session.value = ChaoxingSession(cookieHeader, user)
                    revision++
                    activeEntryId = null
                    ChaoxingLoginResult.Success
                }
            }
        }
    }

    /**
     * 用本机已保存的学校账号走公共 CAS 引擎取得 Chaoxing 会话。
     *
     * 密码、票据、Cookie 只在本次调用内存在；身份核验和发布仍在同一把状态锁内完成。
     * 自动登录失败不会清理已有会话；账号/代次/epoch/revision 变化时旧结果不能发布。
     */
    suspend fun loginWithSavedAccount(): ChaoxingLoginResult = autoLoginMutex.withLock {
        val context = captureContext()
            ?: return@withLock ChaoxingLoginResult.Failure(unavailableReason())
        val started = System.currentTimeMillis()
        val operationId = Diagnostics.newOperationId()
        Diagnostics.record(
            module = DiagnosticModule.LIBRARY,
            action = DiagnosticAction.AUTH_START,
            result = DiagnosticResult.PENDING,
            operationId = operationId,
        )
        val auth = try {
            authenticator.authenticate(context.accountId, context.generation.value)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.AUTH_COMPLETE,
                result = DiagnosticResult.CANCELLED,
                durationMs = (System.currentTimeMillis() - started).coerceAtLeast(0),
                operationId = operationId,
            )
            throw cancelled
        } catch (failure: Exception) {
            Diagnostics.recordException(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.AUTH_COMPLETE,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.UNKNOWN,
                failure = failure,
                durationMs = (System.currentTimeMillis() - started).coerceAtLeast(0),
                operationId = operationId,
            )
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.PROTOCOL_ERROR)
        }
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(0)
        val cookieHeader = when (auth) {
            is ChaoxingAccountAuthResult.Success -> {
                Diagnostics.record(
                    module = DiagnosticModule.LIBRARY,
                    action = DiagnosticAction.AUTH_COMPLETE,
                    result = DiagnosticResult.OK,
                    durationMs = duration,
                    operationId = operationId,
                )
                auth.cookieHeader
            }
            is ChaoxingAccountAuthResult.Failure -> {
                Diagnostics.record(
                    module = DiagnosticModule.LIBRARY,
                    action = DiagnosticAction.AUTH_COMPLETE,
                    result = DiagnosticResult.FAIL,
                    reason = chaoxingDiagnosticReason(auth.reason),
                    durationMs = duration,
                    operationId = operationId,
                )
                if (auth.reason == ChaoxingAuthFailure.INTERACTION_REQUIRED) {
                    Diagnostics.record(
                        module = DiagnosticModule.LIBRARY,
                        action = DiagnosticAction.AUTH_INTERACTION,
                        result = DiagnosticResult.BLOCKED,
                        reason = DiagnosticReason.INTERACTION,
                        durationMs = duration,
                        operationId = operationId,
                    )
                }
                return@withLock ChaoxingLoginResult.Failure(auth.reason)
            }
        }
        if (!isPlausibleCookieHeader(cookieHeader)) {
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.INVALID_SESSION)
        }
        val user = try {
            repository.identity(cookieHeader)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: ChaoxingSessionExpiredException) {
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.SESSION_EXPIRED)
        } catch (_: ChaoxingException) {
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.PROTOCOL_ERROR)
        } catch (_: Exception) {
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.NETWORK_UNAVAILABLE)
        }
        val identity = try {
            identityVerifier.verifyLoginName(
                context.accountId,
                context.generation,
                user.sno,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
        }
        when (identity) {
            SchoolAccountIdentityCheck.MISMATCH ->
                return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.IDENTITY_MISMATCH)
            SchoolAccountIdentityCheck.CHANGED ->
                return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_CHANGED)
            SchoolAccountIdentityCheck.UNAVAILABLE ->
                return@withLock ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
            SchoolAccountIdentityCheck.MATCH -> Unit
        }
        synchronized(stateLock) {
            when {
                cleanupInProgress ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.CLEANUP_IN_PROGRESS)
                cleanupFailed ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED)
                !isCurrent(context) ->
                    ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_CHANGED)
                else -> {
                    _session.value = ChaoxingSession(cookieHeader, user)
                    revision++
                    // 自动登录成功即让旧的网页登录入口失效。
                    activeEntryId = null
                    ChaoxingLoginResult.Success
                }
            }
        }
    }

    private fun unavailableReason(): ChaoxingAuthFailure = synchronized(stateLock) {
        when {
            cleanupInProgress -> ChaoxingAuthFailure.CLEANUP_IN_PROGRESS
            cleanupFailed -> ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED
            else -> ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE
        }
    }

    suspend fun seatRooms(
        kind: ChaoxingSeatKind,
        date: String,
    ): ChaoxingSeatRoomPage = callSession { cookie, _ ->
        repository.seatRooms(kind, cookie, date)
    }

    suspend fun seatRoomDetail(
        kind: ChaoxingSeatKind,
        roomId: Int,
        date: String,
    ): ChaoxingSeatRoomDetail = callSession { cookie, _ ->
        repository.seatRoomDetail(kind, cookie, roomId, date)
    }

    suspend fun usedSeatNums(
        kind: ChaoxingSeatKind,
        roomId: Int,
        startMinute: Int,
        endMinute: Int,
        date: String,
    ): Set<String> = callSession { cookie, _ ->
        repository.usedSeatNums(kind, cookie, roomId, startMinute, endMinute, date)
    }

    suspend fun usedTimes(
        kind: ChaoxingSeatKind,
        roomId: Int,
        seatNum: String,
        date: String,
    ): List<ChaoxingTimeInterval> = callSession { cookie, _ ->
        repository.usedTimes(kind, cookie, roomId, seatNum, date)
    }

    /**
     * 用户确认后的真实预约提交。只在当前会话/账号仍有效时发布结果；提交后账号变化时
     * 返回 Unknown，不假装成功也不自动重试。调用方必须自行做座位/时段/已用状态校验。
     */
    suspend fun submitReservation(
        kind: ChaoxingSeatKind,
        roomId: Int,
        date: String,
        startMinute: Int,
        endMinute: Int,
        seatNum: String,
    ): ChaoxingReservationResult {
        val current = _session.value ?: throw ChaoxingSessionExpiredException()
        val context = captureContext() ?: throw ChaoxingSessionExpiredException()
        val submissionKey = submissionKey(
            current.user.sno,
            kind,
            roomId,
            date,
            startMinute,
            endMinute,
            seatNum,
        )
        if (synchronized(stateLock) { submissionKey in unknownSubmissions }) {
            return ChaoxingReservationResult.Unknown(
                "上次提交结果未知；请先到学校记录核对，本次不会再次发送。" +
                    "确认未成功后重新登录再试。",
            )
        }
        val result = try {
            repository.submitReservation(
                kind = kind,
                cookieHeader = current.cookieHeader,
                roomId = roomId,
                date = date,
                startMinute = startMinute,
                endMinute = endMinute,
                seatNum = seatNum,
            )
        } catch (expired: ChaoxingSessionExpiredException) {
            signOutIfCurrent(current.cookieHeader)
            throw expired
        }
        val stillCurrent = isCurrent(context) &&
            _session.value?.cookieHeader == current.cookieHeader
        if (!stillCurrent) {
            synchronized(stateLock) { unknownSubmissions += submissionKey }
            return ChaoxingReservationResult.Unknown(
                "账号或登录在提交期间变化，结果未知；请到学校记录核对，不要连续重试。",
            )
        }
        if (result is ChaoxingReservationResult.Unknown) {
            synchronized(stateLock) { unknownSubmissions += submissionKey }
        }
        return result
    }

    private fun submissionKey(
        userSno: String,
        kind: ChaoxingSeatKind,
        roomId: Int,
        date: String,
        startMinute: Int,
        endMinute: Int,
        seatNum: String,
    ): String = listOf(
        userSno,
        kind.name,
        roomId,
        date,
        startMinute,
        endMinute,
        seatNum,
    ).joinToString("|")

    suspend fun signOut(): ChaoxingCleanupResult = performCleanup(expectedCookie = null)

    suspend fun signOutForAccountChange(): Boolean =
        performCleanup(expectedCookie = null) is ChaoxingCleanupResult.Success

    suspend fun retryCookieCleanup(): Boolean {
        val canRetry = synchronized(stateLock) {
            if (!cleanupFailed || cleanupInProgress) return@synchronized false
            cleanupFailed = false
            true
        }
        if (!canRetry) return false
        return performCleanup(expectedCookie = null) is ChaoxingCleanupResult.Success
    }

    private suspend fun <T> callSession(
        block: suspend (String, () -> Boolean) -> T,
    ): T {
        val current = _session.value ?: throw ChaoxingSessionExpiredException()
        val context = captureContext() ?: throw ChaoxingSessionExpiredException()
        val guard = { isCurrent(context) && _session.value?.cookieHeader == current.cookieHeader }
        return try {
            val result = block(current.cookieHeader) { guard() }
            if (!guard()) throw ChaoxingSessionExpiredException()
            result
        } catch (expired: ChaoxingSessionExpiredException) {
            signOutIfCurrent(current.cookieHeader)
            throw expired
        } catch (failure: ChaoxingException) {
            throw failure
        }
    }

    private suspend fun signOutIfCurrent(cookieHeader: String) {
        val shouldClear = synchronized(stateLock) {
            _session.value?.cookieHeader == cookieHeader
        }
        if (shouldClear) {
            performCleanup(expectedCookie = cookieHeader)
        }
    }

    /**
     * 清理是全局 WebView 操作。同一时刻只跑一个清理任务；所有调用者等待同一个结果。
     * 清理没有成功前 cleanupInProgress/cleanupFailed 会阻止新登录发布，旧清理完成
     * 也不会删掉稍后发布的新会话。
     */
    private suspend fun performCleanup(expectedCookie: String?): ChaoxingCleanupResult {
        var owner = false
        var job: CompletableDeferred<Boolean>
        synchronized(stateLock) {
            if (expectedCookie != null && _session.value?.cookieHeader != expectedCookie) {
                return ChaoxingCleanupResult.Success
            }
            epoch++
            revision++
            _session.value = null
            unknownSubmissions.clear()
            activeEntryId = null
            cleanupInProgress = true
            cleanupFailed = false
            _cleanupState.value = ChaoxingCleanupState.CLEARING
            val existing = cleanupJob
            if (existing == null) {
                job = CompletableDeferred()
                cleanupJob = job
                owner = true
            } else {
                job = existing
            }
        }
        val success = if (owner) {
            val result = withContext(NonCancellable) {
                runCatching { clearCookies() }.getOrDefault(false)
            }
            synchronized(stateLock) {
                cleanupJob = null
                cleanupInProgress = false
                cleanupFailed = !result
                _cleanupState.value = if (result) {
                    ChaoxingCleanupState.IDLE
                } else {
                    ChaoxingCleanupState.FAILED
                }
                job.complete(result)
            }
            result
        } else {
            job.await()
        }
        return if (success) {
            ChaoxingCleanupResult.Success
        } else {
            ChaoxingCleanupResult.Failed("Chaoxing Cookie 清理失败或超时")
        }
    }

    private fun entryFailure(entry: ChaoxingWebEntryContext): ChaoxingAuthFailure? =
        synchronized(stateLock) {
            when {
                cleanupInProgress -> ChaoxingAuthFailure.CLEANUP_IN_PROGRESS
                cleanupFailed -> ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED
                activeEntryId != entry.entryId -> ChaoxingAuthFailure.STALE_WEB_ENTRY
                !isCurrentLocked(entry) -> ChaoxingAuthFailure.ACCOUNT_CHANGED
                else -> null
            }
        }

    private fun captureContext(): ChaoxingLoginContext? = synchronized(stateLock) {
        if (cleanupInProgress || cleanupFailed) return@synchronized null
        val reference = currentReferenceLocked() ?: return@synchronized null
        ChaoxingLoginContext(
            epoch = epoch,
            revision = revision,
            accountId = reference.accountId,
            generation = reference.credentialGeneration,
        )
    }

    private fun isCurrent(context: ChaoxingLoginContext): Boolean = synchronized(stateLock) {
        if (cleanupInProgress || cleanupFailed) return@synchronized false
        epoch == context.epoch && revision == context.revision &&
            currentReferenceLocked()?.let {
                it.accountId == context.accountId &&
                    it.credentialGeneration == context.generation
            } == true
    }

    private fun isCurrentLocked(entry: ChaoxingWebEntryContext): Boolean {
        if (epoch != entry.epoch || revision != entry.revision) return false
        val state = accountGate.state.value
        if (state.blocksPersonalSessions || state.generation != entry.generation) return false
        val reference = accountReference.accountReference.value ?: return false
        return reference.accountId == entry.accountId &&
            reference.credentialGeneration == entry.generation
    }

    private fun currentReferenceLocked(): com.upcpocket.app.account.api.SchoolAccountReference? {
        val state = accountGate.state.value
        if (!state.isCredentialSaved || state.blocksPersonalSessions) return null
        return accountReference.accountReference.value
            ?.takeIf { it.credentialGeneration == state.generation }
    }

    private fun isPlausibleCookieHeader(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 8 * 1024 &&
            !value.contains('\r') &&
            !value.contains('\n')

    private fun chaoxingDiagnosticReason(reason: ChaoxingAuthFailure): DiagnosticReason =
        when (reason) {
            ChaoxingAuthFailure.INVALID_CREDENTIALS,
            ChaoxingAuthFailure.ACCESS_DENIED,
            ChaoxingAuthFailure.IDENTITY_MISMATCH,
            ChaoxingAuthFailure.SESSION_EXPIRED,
            ChaoxingAuthFailure.INVALID_SESSION,
            -> DiagnosticReason.AUTH
            ChaoxingAuthFailure.INTERACTION_REQUIRED -> DiagnosticReason.INTERACTION
            ChaoxingAuthFailure.NETWORK_UNAVAILABLE -> DiagnosticReason.NETWORK
            ChaoxingAuthFailure.TLS_ERROR -> DiagnosticReason.TLS
            ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE,
            ChaoxingAuthFailure.ACCOUNT_CHANGED,
            -> DiagnosticReason.ACCOUNT
            ChaoxingAuthFailure.CLEANUP_IN_PROGRESS,
            ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED,
            -> DiagnosticReason.CLEANUP
            ChaoxingAuthFailure.STALE_WEB_ENTRY -> DiagnosticReason.STALE
            ChaoxingAuthFailure.PROTOCOL_ERROR -> DiagnosticReason.SCHEMA
            ChaoxingAuthFailure.UPSTREAM_ERROR -> DiagnosticReason.UNKNOWN
        }
}
