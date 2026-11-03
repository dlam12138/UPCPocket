package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.blocksPersonalSessions
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
    private val authenticator: LibraryAccountAuthenticator,
    private val clearCookies: () -> Unit,
    private val clearSchoolCookiesAndAwait: suspend () -> Boolean = {
        SchoolWebCookies.clearAllSchoolCookiesAndAwait()
    },
) {
    private val loginEpoch = AtomicLong(0)
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
        )
    }

    internal fun isLoginContextCurrent(context: LibraryLoginContext): Boolean {
        val snapshot = accountGate.state.value
        if (loginEpoch.get() != context.loginEpoch ||
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
     * 写入登录结果与失效代次使用同一把锁，避免“检查代次后、写 token 前”被换号
     * 清理插入，导致旧 token 重新落盘。上下文同时绑定打开页面/开始认证时的账号代次。
     *
     * 没有 accountId 的旧式手动登录只保留内存会话；不把无法归属的 token 写入持久层。
     */
    internal suspend fun persistLoginIfCurrent(
        context: LibraryLoginContext,
        session: LibrarySeatSession,
    ): Boolean = loginMutex.withLock {
        if (!isLoginContextCurrent(context)) return@withLock false
        val accountId = context.accountId
        if (accountId == null) {
            sessionStore.clearLibrary()
            return@withLock true
        }
        if (rememberLoginEnabled()) {
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
        true
    }

    /**
     * 使用已保存统一账号自动完成 libseat 正常 CAS，不把密码/网络请求放在登录锁内。
     * 结果返回后再次经过 repository 代次与账号门闩复核，迟到结果不能发布。
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
        try {
            repository.publishAuthenticatedSession(session) {
                isLoginContextCurrent(context)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LibraryAccountLoginResult.Failure(LibraryAccountAuthFailure.ACCOUNT_CHANGED)
        }

        if (!persistLoginIfCurrent(context, session)) {
            repository.clearSession()
            clearCookies()
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
     * 重启后恢复图书馆持久 token 必须走同一门闩；只有绑定到当前 accountId/代次且服务端
     * 返回同一本人标识时才恢复。
     */
    suspend fun restoreSavedLibrarySession(): LibrarySeatSession? {
        val context = captureLoginContext() ?: return null
        val accountId = context.accountId ?: run {
            sessionStore.clearLibrary()
            return null
        }
        val saved = sessionStore.loadLibrary(accountId, context.accountGeneration) ?: return null
        if (!isLoginContextCurrent(context)) {
            sessionStore.clearLibrary()
            return null
        }
        return try {
            val session = repository.adoptSeatToken(saved.token) {
                isLoginContextCurrent(context)
            }
            if (session.user.id != saved.user.id ||
                session.user.username != saved.user.username
            ) {
                // token 仍有效但返回了另一个本人：不能把旧持久副本恢复成新会话。
                sessionStore.clearLibrary()
                repository.clearSession()
                null
            } else {
                session
            }
        } catch (e: LibrarySessionExpiredException) {
            // 只有学校明确判定会话失效才删除持久副本；瞬时网络/TLS/协议失败保留，
            // 让用户下次进入时可以重试，而不是把“没连上”误当成“已登出”。
            sessionStore.clearLibrary()
            repository.clearSession()
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
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
            sessionStore.clear()
            repository.clearSession()
        }
        clearCookies()
    }

    /**
     * 统一账号换号/删号使用：先让本业务的登录代次失效，再清持久会话、内存会话并
     * 等待学校 Cookie 清理完成。失败时账号层保持清理中；账号状态门闩会同时阻止
     * 新登录，直到清理重试完成。
     */
    suspend fun signOutForAccountChange(): Boolean {
        val persistedCleared = loginMutex.withLock {
            loginEpoch.incrementAndGet()
            sessionStore.clear().also { repository.clearSession() }
        }
        if (!persistedCleared) return false
        return clearSchoolCookiesAndAwait()
    }
}
