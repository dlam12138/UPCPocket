package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.nspace.NspaceBookingRequest
import com.upcpocket.app.data.nspace.NspaceException
import com.upcpocket.app.data.nspace.NspaceFormField
import com.upcpocket.app.data.nspace.NspaceRepository
import com.upcpocket.app.data.nspace.NspaceReservationPage
import com.upcpocket.app.data.nspace.NspaceRoomDetail
import com.upcpocket.app.data.nspace.NspaceRoomPage
import com.upcpocket.app.data.nspace.NspaceRoomType
import com.upcpocket.app.data.nspace.NspaceSession
import com.upcpocket.app.data.nspace.NspaceSessionExpiredException
import com.upcpocket.app.data.nspace.NspaceUseType
import com.upcpocket.app.data.nspace.NspaceUser
import com.upcpocket.app.data.nspace.NspaceVenue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** nspace 认证给 feature 的固定结果；不携带上游正文、账号或 token。 */
sealed interface NspaceAccountAuthResult {
    data class Success(
        val token: String,
        val user: NspaceUser,
    ) : NspaceAccountAuthResult

    data class Failure(
        val reason: NspaceAuthFailure,
        val interactionUrl: String? = null,
    ) : NspaceAccountAuthResult
}

enum class NspaceAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
    SESSION_EXPIRED,
}

fun interface NspaceAccountAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): NspaceAccountAuthResult
}

sealed interface NspaceLoginResult {
    data object Success : NspaceLoginResult
    data class Failure(
        val reason: NspaceAuthFailure,
        val interactionUrl: String? = null,
    ) : NspaceLoginResult
}

private data class NspaceLoginContext(
    val epoch: Long,
    val revision: Long,
    val accountId: SchoolAccountId,
    val generation: AccountGeneration,
)

/**
 * nspace 独立的内存会话服务。
 *
 * - 使用已保存统一账号的正常 CAS；nspace token 与 libseat token 分属不同对象；
 * - 不持久化 nspace token，因此重启后重新授权；不写账号库；
 * - 网络在锁外，发布在锁内；换号/清理时旧结果不能发布，也不能清掉新登录；
 * - 页面只通过本服务读馆区/房间，未知字段按协议失败，不伪造空列表。
 */
class NspaceService(
    private val repository: NspaceRepository,
    private val accountGate: SchoolAccountGate,
    private val accountReference: SchoolAccountReferenceReader,
    private val identityVerifier: SchoolAccountIdentityVerifier,
    private val authenticator: NspaceAccountAuthenticator,
) {
    private val _session = MutableStateFlow<NspaceSession?>(null)
    val session: StateFlow<NspaceSession?> = _session.asStateFlow()

    val accountState: StateFlow<SchoolAccountState> get() = accountGate.state

    private val epoch = AtomicLong(0)
    private val revision = AtomicLong(0)
    private val loginMutex = Mutex()

    fun hasSavedAccount(): Boolean =
        accountGate.state.value.isCredentialSaved &&
            accountReference.accountReference.value != null

    suspend fun loginWithSavedAccount(): NspaceLoginResult {
        val context = captureContext()
            ?: return NspaceLoginResult.Failure(NspaceAuthFailure.ACCOUNT_UNAVAILABLE)
        val auth = try {
            authenticator.authenticate(context.accountId, context.generation.value)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NspaceLoginResult.Failure(NspaceAuthFailure.PROTOCOL_ERROR)
        }
        val success = when (auth) {
            is NspaceAccountAuthResult.Success -> auth
            is NspaceAccountAuthResult.Failure -> return NspaceLoginResult.Failure(
                auth.reason,
                auth.interactionUrl,
            )
        }
        val identity = try {
            identityVerifier.verifyLoginName(
                context.accountId,
                context.generation,
                success.user.username,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NspaceLoginResult.Failure(NspaceAuthFailure.ACCOUNT_UNAVAILABLE)
        }
        when (identity) {
            SchoolAccountIdentityCheck.MISMATCH ->
                return NspaceLoginResult.Failure(NspaceAuthFailure.IDENTITY_MISMATCH)
            SchoolAccountIdentityCheck.CHANGED ->
                return NspaceLoginResult.Failure(NspaceAuthFailure.ACCOUNT_CHANGED)
            SchoolAccountIdentityCheck.UNAVAILABLE ->
                return NspaceLoginResult.Failure(NspaceAuthFailure.ACCOUNT_UNAVAILABLE)
            SchoolAccountIdentityCheck.MATCH -> Unit
        }
        val published = loginMutex.withLock {
            if (!isCurrent(context)) return@withLock false
            _session.value = NspaceSession(success.token, success.user)
            revision.incrementAndGet()
            true
        }
        return if (published) {
            NspaceLoginResult.Success
        } else {
            NspaceLoginResult.Failure(NspaceAuthFailure.ACCOUNT_CHANGED)
        }
    }

    suspend fun venues(): List<NspaceVenue> = callSession { token, guard ->
        repository.venues(token)
    }

    suspend fun roomTypes(venueId: String): List<NspaceRoomType> = callSession { token, guard ->
        repository.roomTypes(token, venueId)
    }

    suspend fun openDates(venueId: String, roomTypeId: String): List<String> =
        callSession { token, guard ->
            repository.openDates(token, venueId, roomTypeId)
        }

    suspend fun rooms(
        venueId: String,
        roomTypeId: String,
        date: String,
        page: Int,
    ): NspaceRoomPage = callSession { token, guard ->
        repository.rooms(token, venueId, roomTypeId, date, page)
    }

    suspend fun myReservations(page: Int): NspaceReservationPage = callSession { token, guard ->
        repository.myReservations(token, page)
    }

    suspend fun roomDetail(
        roomId: String,
        date: String,
        seatNo: String,
    ): NspaceRoomDetail = callSession { token, guard ->
        repository.roomDetail(token, roomId, date, seatNo)
    }

    suspend fun useTypes(): List<NspaceUseType> = callSession { token, guard ->
        repository.useTypes(token)
    }

    suspend fun requiredFields(): List<NspaceFormField> = callSession { token, guard ->
        repository.requiredFields(token)
    }

    suspend fun book(request: NspaceBookingRequest) {
        val current = _session.value ?: throw NspaceSessionExpiredException()
        val context = captureContext() ?: throw NspaceSessionExpiredException()
        if (_session.value?.token != current.token || !isCurrent(context)) {
            throw NspaceSessionExpiredException()
        }
        try {
            repository.book(current.token, request)
        } catch (expired: NspaceSessionExpiredException) {
            signOutIfCurrent(current.token)
            throw expired
        }
    }

    suspend fun cancelReservation(reservationId: String) {
        val current = _session.value ?: throw NspaceSessionExpiredException()
        val context = captureContext() ?: throw NspaceSessionExpiredException()
        if (_session.value?.token != current.token || !isCurrent(context)) {
            throw NspaceSessionExpiredException()
        }
        try {
            // 写请求不使用 callSession 的“结果后复核”：请求可能已到达学校，不能因为
            // 账号代次随后变化就把已提交的取消误报为失败。发送前已复核当前会话。
            repository.cancelReservation(current.token, reservationId)
        } catch (expired: NspaceSessionExpiredException) {
            signOutIfCurrent(current.token)
            throw expired
        }
    }

    suspend fun signOut() {
        loginMutex.withLock {
            epoch.incrementAndGet()
            revision.incrementAndGet()
            _session.value = null
        }
    }

    /** 换号/删号：nspace 只有内存 token；返回 true 表示已无条件撤下。 */
    suspend fun signOutForAccountChange(): Boolean {
        signOut()
        return true
    }

    private suspend fun <T> callSession(
        block: suspend (String, () -> Boolean) -> T,
    ): T {
        val current = _session.value ?: throw NspaceSessionExpiredException()
        val context = captureContext()
            ?: throw NspaceSessionExpiredException()
        val guard = { isCurrent(context) && _session.value?.token == current.token }
        return try {
            val result = block(current.token) { guard() }
            if (!guard()) throw NspaceSessionExpiredException()
            result
        } catch (expired: NspaceSessionExpiredException) {
            signOutIfCurrent(current.token)
            throw expired
        } catch (failure: NspaceException) {
            throw failure
        }
    }

    private suspend fun signOutIfCurrent(token: String) {
        loginMutex.withLock {
            if (_session.value?.token == token) {
                epoch.incrementAndGet()
                revision.incrementAndGet()
                _session.value = null
            }
        }
    }

    private fun captureContext(): NspaceLoginContext? {
        val state = accountGate.state.value
        if (!state.isCredentialSaved || state.blocksPersonalSessions) return null
        val reference = accountReference.accountReference.value
            ?.takeIf { it.credentialGeneration == state.generation }
            ?: return null
        return NspaceLoginContext(
            epoch = epoch.get(),
            revision = revision.get(),
            accountId = reference.accountId,
            generation = reference.credentialGeneration,
        )
    }

    private fun isCurrent(context: NspaceLoginContext): Boolean {
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
}
