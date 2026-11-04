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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * - 发布前用服务端 `userLoginInfo.sno` 与当前统一账号比较；
 * - 网络在锁外，发布在锁内；旧回调不能覆盖新登录。
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

    val accountState: StateFlow<SchoolAccountState> get() = accountGate.state

    private val epoch = AtomicLong(0)
    private val revision = AtomicLong(0)
    private val loginMutex = Mutex()

    fun hasSavedAccount(): Boolean =
        accountGate.state.value.isCredentialSaved &&
            accountReference.accountReference.value != null

    /**
     * 受限 WebView 登录完成后调用。Cookie 只作为参数传入，不写日志/不持久化。
     */
    suspend fun loginWithWebCookies(cookieHeader: String): ChaoxingLoginResult {
        val context = captureContext()
            ?: return ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
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
                context.accountId,
                context.generation,
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
        val published = loginMutex.withLock {
            if (!isCurrent(context)) return@withLock false
            _session.value = ChaoxingSession(cookieHeader, user)
            revision.incrementAndGet()
            true
        }
        return if (published) {
            ChaoxingLoginResult.Success
        } else {
            ChaoxingLoginResult.Failure(ChaoxingAuthFailure.ACCOUNT_CHANGED)
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

    suspend fun reserveRooms(tenant: ChaoxingTenant): List<ChaoxingReserveRoom> =
        callSession { cookie, _ ->
            repository.reserveRooms(tenant, cookie).rooms
        }

    suspend fun signOut() {
        loginMutex.withLock {
            epoch.incrementAndGet()
            revision.incrementAndGet()
            _session.value = null
        }
        clearCookies()
    }

    suspend fun signOutForAccountChange(): Boolean {
        loginMutex.withLock {
            epoch.incrementAndGet()
            revision.incrementAndGet()
            _session.value = null
        }
        return clearCookies()
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
        val cleared = loginMutex.withLock {
            if (_session.value?.cookieHeader == cookieHeader) {
                epoch.incrementAndGet()
                revision.incrementAndGet()
                _session.value = null
                true
            } else {
                false
            }
        }
        if (cleared) clearCookies()
    }

    private fun captureContext(): ChaoxingLoginContext? {
        val state = accountGate.state.value
        if (!state.isCredentialSaved || state.blocksPersonalSessions) return null
        val reference = accountReference.accountReference.value
            ?.takeIf { it.credentialGeneration == state.generation }
            ?: return null
        return ChaoxingLoginContext(
            epoch = epoch.get(),
            revision = revision.get(),
            accountId = reference.accountId,
            generation = reference.credentialGeneration,
        )
    }

    private fun isCurrent(context: ChaoxingLoginContext): Boolean {
        val state = accountGate.state.value
        if (epoch.get() != context.epoch || revision.get() != context.revision ||
            state.blocksPersonalSessions || state.generation != context.generation
        ) {
            return false
        }
        val reference = accountReference.accountReference.value ?: return false
        return reference.accountId == context.accountId &&
            reference.credentialGeneration == context.generation
    }

    private fun isPlausibleCookieHeader(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 8 * 1024 &&
            !value.contains('\r') &&
            !value.contains('\n')
}
