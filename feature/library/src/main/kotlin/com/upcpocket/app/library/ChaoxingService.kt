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
import com.upcpocket.app.data.chaoxing.ChaoxingReserveContext
import com.upcpocket.app.data.chaoxing.ChaoxingReserveRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomPage
import com.upcpocket.app.data.chaoxing.ChaoxingSession
import com.upcpocket.app.data.chaoxing.ChaoxingSessionExpiredException
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
import com.upcpocket.app.data.chaoxing.ChaoxingTimeInterval
import com.upcpocket.app.data.chaoxing.ChaoxingUser
import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface ChaoxingLoginResult {
    data object Success : ChaoxingLoginResult
    data class Failure(val reason: ChaoxingAuthFailure) : ChaoxingLoginResult
}

enum class ChaoxingAuthFailure {
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    IDENTITY_MISMATCH,
    SESSION_EXPIRED,
    INVALID_SESSION,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
    CLEANUP_IN_PROGRESS,
    COOKIE_CLEANUP_FAILED,
    STALE_WEB_ENTRY,
}

/** 页面入口；reservepc 的租户上下文页面复用同一门闩，但使用独立来源路径。 */
enum class ChaoxingWebSource(val path: String) {
    SEAT_LOGIN("/front/third/apps/seat/index"),
    RESERVEPC_TENANT("/front/web/apps/reservepc/index"),
}

/**
 * 打开 WebView 时捕获的页面入口上下文。
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
    val source: ChaoxingWebSource,
    val tenant: ChaoxingTenant? = null,
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
 * Chaoxing 座位/研讨间内存会话。
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
    private var epoch = 0L
    private var revision = 0L
    private var entrySequence = 0L
    private var activeEntryId: Long? = null
    private var cleanupInProgress = false
    private var cleanupFailed = false
    private var cleanupJob: CompletableDeferred<Boolean>? = null
    private var reserveContext: ChaoxingReserveContext? = null
    private var reserveContextTenant: ChaoxingTenant? = null
    private var reserveContextEpoch = -1L
    private var reserveContextRevision = -1L

    fun hasSavedAccount(): Boolean = synchronized(stateLock) {
        currentReferenceLocked() != null
    }

    /**
     * 在打开 WebView 前调用。清理进行中/清理失败/账号不可用时返回 null，页面不应开始登录。
     */
    fun beginWebLogin(
        source: ChaoxingWebSource = ChaoxingWebSource.SEAT_LOGIN,
        tenant: ChaoxingTenant? = null,
    ): ChaoxingWebEntryContext? = synchronized(stateLock) {
        if (cleanupInProgress || cleanupFailed) return@synchronized null
        val reference = currentReferenceLocked() ?: return@synchronized null
        val entry = ChaoxingWebEntryContext(
            entryId = ++entrySequence,
            accountId = reference.accountId,
            generation = reference.credentialGeneration,
            epoch = epoch,
            revision = revision,
            source = source,
            tenant = tenant,
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
     * 使用当前内存租户上下文读取 reservepc；没有上下文时才回退到官方页面解析。
     * 上下文由 WebView 持有页面的 `reserveIdEnc` 捕获，绑定账号/代次/租户/来源路径。
     */
    suspend fun reserveRooms(tenant: ChaoxingTenant): List<ChaoxingReserveRoom> =
        callSession { cookie, _ ->
            val context = synchronized(stateLock) {
                reserveContext?.takeIf {
                    reserveContextTenant == tenant &&
                        reserveContextEpoch == epoch &&
                        reserveContextRevision == revision
                }
            }
            if (context != null) {
                repository.reserveRooms(tenant, cookie, context).rooms
            } else {
                repository.reserveRooms(tenant, cookie).rooms
            }
        }

    /**
     * 由受限 WebView 在已加载的租户页面中读取到 `reserveIdEnc` 后调用。
     * 只接受固定 reservepc 页面入口、当前账号/代次/epoch/revision 与匹配租户。
     */
    fun adoptReserveContext(
        entry: ChaoxingWebEntryContext,
        tenant: ChaoxingTenant,
        reserveIdEnc: String,
    ): Boolean = synchronized(stateLock) {
        if (entry.source != ChaoxingWebSource.RESERVEPC_TENANT) return@synchronized false
        if (entry.tenant != tenant) return@synchronized false
        if (!isPlausibleReserveContext(reserveIdEnc)) return@synchronized false
        if (cleanupInProgress || cleanupFailed || !isCurrentLocked(entry)) {
            return@synchronized false
        }
        activeEntryId = null
        reserveContext = ChaoxingReserveContext(
            tenant = tenant,
            reserveIdEnc = reserveIdEnc,
            sourcePath = entry.source.path,
        )
        reserveContextTenant = tenant
        reserveContextEpoch = epoch
        reserveContextRevision = revision
        true
    }

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
            activeEntryId = null
            clearReserveContextLocked()
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

    private fun clearReserveContextLocked() {
        reserveContext = null
        reserveContextTenant = null
        reserveContextEpoch = -1
        reserveContextRevision = -1
    }

    private fun isPlausibleReserveContext(value: String): Boolean =
        value.matches(Regex("""[A-Za-z0-9+/=]{8,128}"""))

    private fun isPlausibleCookieHeader(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 8 * 1024 &&
            !value.contains('\r') &&
            !value.contains('\n')
}
