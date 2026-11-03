package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatException
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 打开学校登录页或自动认证时捕获的上下文；不包含账号、密码、Cookie 或 token。 */
internal data class LibraryLoginContext(
    val loginEpoch: Long,
    val accountGeneration: AccountGeneration,
    val accountId: SchoolAccountId?,
    val sessionRevision: Long,
)

/** 自动认证给 UI 的固定结果；不携带上游正文、账号或 token。 */
sealed interface LibraryAccountLoginResult {
    data object Success : LibraryAccountLoginResult

    data class Failure(
        val reason: LibraryAccountAuthFailure,
        val interactionUrl: String? = null,
    ) : LibraryAccountLoginResult
}

/**
 * 图书馆插件对页面与设置区暴露的业务入口。
 *
 * 会话 token 仍由原来的内存 Repository 持有；开启“记住登录”时使用原有
 * [SchoolSessionStore] 加密副本，并且只有在会话绑定到当前 accountId/凭据代次时才允许
 * 持久化或恢复。偏好读写与 Cookie 清理由宿主适配器提供，插件不直接依赖宿主的 AppGraph。
 *
 * [accountGate] 是统一账号的只读门闩：启动恢复、换号/删号清理和清理失败重试期间，
 * 图书馆不能打开登录页、恢复旧 token 或发布新会话。账号未配置或清理已完成但没有可用
 * 账号引用时，仍允许原有学校网页手动登录；这种手动会话只在内存中存在，不写入持久层。
 */
class LibraryService(
    val repository: LibrarySeatRepository,
    val sessionStore: SchoolSessionStore,
    private val settings: LibraryAccountSettings,
    private val accountGate: SchoolAccountGate,
    private val accountReference: SchoolAccountReferenceReader,
    private val identityVerifier: SchoolAccountIdentityVerifier,
    private val authenticator: LibraryAccountAuthenticator,
    private val clearCookies: () -> Unit,
    private val clearSchoolCookiesAndAwait: suspend () -> Boolean = {
        SchoolWebCookies.clearAllSchoolCookiesAndAwait()
    },
    private val resolveSeatSession: suspend (
        String,
        Boolean,
        () -> Boolean,
    ) -> LibrarySeatSession = { token, alreadySeatToken, guard ->
        repository.resolveSeatSession(token, alreadySeatToken, guard)
    },
) {
    private val loginEpoch = AtomicLong(0)
    private val sessionRevision = AtomicLong(0)
    private val loginMutex = Mutex()

    val rememberLogin: Flow<Boolean> get() = settings.rememberLogin

    val accountState: StateFlow<SchoolAccountState> get() = accountGate.state

    /** 当前是否存在可自动认证的已保存账号引用。 */
    fun hasSavedAccount(): Boolean =
        accountGate.state.value.isCredentialSaved &&
            accountReference.accountReference.value != null

    suspend fun rememberLoginEnabled(): Boolean = settings.rememberLogin.first()

    /**
     * 登录页打开或自动认证开始时捕获，而不是在结果返回时才读取。这样旧页面/旧网络结果
     * 即使晚到，也属于旧登录上下文，不能借清理后的新代次写回。
     */
    internal fun captureLoginContext(): LibraryLoginContext? {
        val snapshot = accountGate.state.value
        if (snapshot.blocksPersonalSessions) return null
        val reference = accountReference.accountReference.value
            ?.takeIf { it.credentialGeneration == snapshot.generation }
        return LibraryLoginContext(
            loginEpoch = loginEpoch.get(),
            accountGeneration = snapshot.generation,
            accountId = reference?.accountId,
            sessionRevision = sessionRevision.get(),
        )
    }

    internal fun isLoginContextCurrent(context: LibraryLoginContext): Boolean {
        val snapshot = accountGate.state.value
        if (loginEpoch.get() != context.loginEpoch ||
            sessionRevision.get() != context.sessionRevision ||
            snapshot.blocksPersonalSessions ||
            snapshot.generation != context.accountGeneration
        ) {
            return false
        }
        val currentReference = accountReference.accountReference.value
        return if (context.accountId == null) {
            currentReference == null
        } else {
            currentReference?.accountId == context.accountId &&
                currentReference.credentialGeneration == context.accountGeneration
        }
    }

    /**
     * 检查学校返回的本人登录名是否属于当前保存账号。
     *
     * 这里只把候选登录名交给受控账号读取面比较；不读取密码、不依赖 core:account，
     * 也不把账号原文带到 feature 内存。没有已保存账号时返回 null，由调用方按内存
     * 会话处理。
     */
    private suspend fun verifySessionIdentity(
        context: LibraryLoginContext,
        session: LibrarySeatSession,
    ): LibraryAccountAuthFailure? {
        val accountId = context.accountId ?: return null
        val check = try {
            identityVerifier.verifyLoginName(
                expectedAccountId = accountId,
                expectedCredentialGeneration = context.accountGeneration,
                candidateLoginName = session.user.username,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE
        }
        return when (check) {
            SchoolAccountIdentityCheck.MATCH -> null
            SchoolAccountIdentityCheck.MISMATCH -> LibraryAccountAuthFailure.IDENTITY_MISMATCH
            SchoolAccountIdentityCheck.CHANGED -> LibraryAccountAuthFailure.ACCOUNT_CHANGED
            SchoolAccountIdentityCheck.UNAVAILABLE ->
                LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE
        }
    }

    /**
     * 唯一的会话提交门闩：身份已核验后，在同一把登录锁里完成持久化、Repository
     * 发布和会话修订递增。旧上下文在拿到锁后会因 revision 变化被拒绝，不能清掉
     * 新登录；网络/身份核验都在锁外。
     */
    private suspend fun commitAuthenticatedSession(
        context: LibraryLoginContext,
        session: LibrarySeatSession,
        persist: Boolean,
    ): Boolean = loginMutex.withLock {
        if (!isLoginContextCurrent(context)) return@withLock false
        if (persist) {
            val accountId = context.accountId
            if (accountId == null) {
                sessionStore.clearLibrary()
            } else if (rememberLoginEnabled()) {
                val saved = sessionStore.saveLibrary(
                    token = session.token,
                    user = session.user,
                    accountId = accountId,
                    credentialGeneration = context.accountGeneration,
                )
                if (!saved) {
                    sessionStore.clearLibrary()
                    return@withLock false
                }
            } else {
                sessionStore.clearLibrary()
            }
        }
        val published = try {
            repository.publishAuthenticatedSession(session) {
                isLoginContextCurrent(context)
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!published) {
            // 只有上下文仍然有效时才清掉本次刚写入的副本；旧上下文绝不在这里
            // 删除并发新登录的数据。
            if (persist && isLoginContextCurrent(context)) sessionStore.clearLibrary()
            return@withLock false
        }
        sessionRevision.incrementAndGet()
        true
    }

    /**
     * 较早的恢复/自动/手动操作失败时，只允许在其上下文仍是当前会话时清理。
     * 若期间已经有新登录成功，revision 已变化，本操作不得删除新数据。
     */
    private suspend fun discardSavedSessionIfCurrent(context: LibraryLoginContext) {
        loginMutex.withLock {
            if (!isLoginContextCurrent(context)) return@withLock
            sessionStore.clearLibrary()
            repository.clearSession()
            sessionRevision.incrementAndGet()
        }
    }

    /**
     * 手动 WebView 登录的受控收尾：先在 Service 里兑换/读取本人，再核验账号身份，
     * 最后通过同一提交门闩发布。页面不再直接调用 Repository 的发布方法，因此 A
     * 账号下登录 B 时不会短暂出现在 UI 或持久层。
     */
    internal suspend fun completeWebLogin(
        context: LibraryLoginContext,
        token: String,
        alreadySeatToken: Boolean,
    ): LibraryAccountLoginResult {
        if (!isLoginContextCurrent(context)) {
            return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.ACCOUNT_CHANGED)
        }
        val session = try {
            resolveSeatSession(token, alreadySeatToken) {
                isLoginContextCurrent(context)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: LibrarySessionExpiredException) {
            return LibraryAccountLoginResult.Failure(
                if (isLoginContextCurrent(context)) {
                    LibraryAccountAuthFailure.SESSION_EXPIRED
                } else {
                    LibraryAccountAuthFailure.ACCOUNT_CHANGED
                },
            )
        } catch (_: LibrarySeatException) {
            return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.UPSTREAM_ERROR)
        } catch (_: Exception) {
            return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.PROTOCOL_ERROR)
        }
        verifySessionIdentity(context, session)?.let {
            return LibraryAccountLoginResult.Failure(it)
        }
        if (!commitAuthenticatedSession(context, session, persist = true)) {
            return LibraryAccountLoginResult.Failure(
                if (isLoginContextCurrent(context)) {
                    LibraryAccountAuthFailure.PERSISTENCE_FAILED
                } else {
                    LibraryAccountAuthFailure.ACCOUNT_CHANGED
                },
            )
        }
        return LibraryAccountLoginResult.Success
    }

    /**
     * 使用已保存统一账号自动完成 libseat 正常 CAS，不把密码/网络请求放在登录锁内。
     * provider 已在 core 内做本人校验；这里再用账号身份契约复核一次，随后统一提交。
     */
    suspend fun loginWithSavedAccount(): LibraryAccountLoginResult {
        val context = captureLoginContext()
            ?: return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE)
        val accountId = context.accountId
            ?: return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE)

        val auth = try {
            authenticator.authenticate(accountId, context.accountGeneration.value)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.PROTOCOL_ERROR)
        }
        val success = when (auth) {
            is LibraryAccountAuthResult.Success -> auth
            is LibraryAccountAuthResult.Failure -> return LibraryAccountLoginResult.Failure(
                auth.reason,
                auth.interactionUrl,
            )
        }

        val session = LibrarySeatSession(success.seatToken, success.user)
        verifySessionIdentity(context, session)?.let {
            return LibraryAccountLoginResult.Failure(it)
        }
        if (!commitAuthenticatedSession(context, session, persist = true)) {
            return LibraryAccountLoginResult.Failure(
                if (isLoginContextCurrent(context)) {
                    LibraryAccountAuthFailure.PERSISTENCE_FAILED
                } else {
                    LibraryAccountAuthFailure.ACCOUNT_CHANGED
                },
            )
        }
        return LibraryAccountLoginResult.Success
    }

    /**
     * 重启后恢复图书馆持久 token 必须走同一门闩；只有绑定到当前 accountId/代次、
     * 受控账号身份校验通过且服务端返回同一本人时才恢复。旧恢复失败只做条件清理，
     * 不能删除期间已经成功的新登录。
     */
    suspend fun restoreSavedLibrarySession(): LibrarySeatSession? {
        val context = captureLoginContext() ?: return null
        val accountId = context.accountId ?: run {
            discardSavedSessionIfCurrent(context)
            return null
        }
        val saved = sessionStore.loadLibrary(accountId, context.accountGeneration) ?: return null
        if (!isLoginContextCurrent(context)) return null
        val session = try {
            resolveSeatSession(saved.token, true) {
                isLoginContextCurrent(context)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: LibrarySessionExpiredException) {
            // 只有学校明确判定会话失效才删除持久副本；瞬时网络/TLS/协议失败保留，
            // 让用户下次进入时可以重试，而不是把“没连上”误当成“已登出”。
            discardSavedSessionIfCurrent(context)
            return null
        } catch (_: Exception) {
            return null
        }
        when (verifySessionIdentity(context, session)) {
            LibraryAccountAuthFailure.IDENTITY_MISMATCH,
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            -> {
                discardSavedSessionIfCurrent(context)
                return null
            }
            null -> Unit
            // 账号暂时不可用/加载中不是“会话失效”，保留副本等待下一次恢复。
            else -> return null
        }
        if (session.user.id != saved.user.id ||
            session.user.username != saved.user.username
        ) {
            // token 仍有效但返回了另一个本人：不能把旧持久副本恢复成新会话。
            discardSavedSessionIfCurrent(context)
            return null
        }
        if (!commitAuthenticatedSession(context, session, persist = false)) {
            return null
        }
        return session
    }

    /**
     * 切换“记住学校登录”。关闭时同时清理持久副本、内存会话与学校业务 Cookie，
     * 与重构前的设置页行为一致。
     */
    suspend fun setRememberLogin(enabled: Boolean) {
        settings.setRememberLogin(enabled)
        if (!enabled) signOut()
    }

    /** 只清理图书馆会话；不触碰其它业务的持久条目。 */
    suspend fun signOut() {
        loginMutex.withLock {
            loginEpoch.incrementAndGet()
            sessionRevision.incrementAndGet()
            sessionStore.clear()
            repository.clearSession()
        }
        clearCookies()
    }

    /**
     * 旧查询迟到返回“会话失效”时，只允许清理它实际使用的那个 token。
     * 与 [signOut] 使用同一把登录锁，避免检查后、清理前插入新登录。
     */
    suspend fun signOutIfCurrent(expectedToken: String): Boolean {
        val cleared = loginMutex.withLock {
            if (repository.session.value?.token != expectedToken) {
                return@withLock false
            }
            loginEpoch.incrementAndGet()
            sessionRevision.incrementAndGet()
            sessionStore.clear()
            repository.clearSession()
            true
        }
        if (cleared) clearCookies()
        return cleared
    }

    /**
     * 统一账号换号/删号使用：先让本业务的登录代次失效，再清持久会话、内存会话并
     * 等待学校 Cookie 清理完成。失败时账号层保持清理中；账号状态门闩会同时阻止
     * 新登录，直到清理重试完成。
     */
    suspend fun signOutForAccountChange(): Boolean {
        val persistedCleared = loginMutex.withLock {
            loginEpoch.incrementAndGet()
            sessionRevision.incrementAndGet()
            sessionStore.clear().also { repository.clearSession() }
        }
        if (!persistedCleared) return false
        return clearSchoolCookiesAndAwait()
    }
}
