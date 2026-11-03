package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 打开学校登录页时捕获的上下文；不包含账号、密码、Cookie 或 token。 */
internal data class LibraryLoginContext(
    val loginEpoch: Long,
    val accountGeneration: AccountGeneration,
)

/**
 * 图书馆插件对页面与设置区暴露的业务入口。
 *
 * 会话 token 仍由原来的内存 Repository 持有；开启“记住登录”时使用原有
 * [SchoolSessionStore] 加密副本。偏好读写与 Cookie 清理由宿主适配器提供，
 * 插件不直接依赖宿主的 AppGraph。
 *
 * [accountGate] 是统一账号的只读门闩：启动恢复、换号/删号清理和清理失败重试期间，
 * 图书馆不能打开登录页、恢复旧 token 或发布新会话。账号未配置/已保存未验证且
 * 没有清理时，仍允许原有的学校网页手动登录。
 */
class LibraryService(
    val repository: LibrarySeatRepository,
    val sessionStore: SchoolSessionStore,
    private val settings: LibraryAccountSettings,
    private val accountGate: SchoolAccountGate,
    private val clearCookies: () -> Unit,
    private val clearSchoolCookiesAndAwait: suspend () -> Boolean = {
        SchoolWebCookies.clearAllSchoolCookiesAndAwait()
    },
) {
    private val loginEpoch = AtomicLong(0)
    private val loginMutex = Mutex()

    val rememberLogin: Flow<Boolean> get() = settings.rememberLogin

    val accountState: StateFlow<SchoolAccountState> get() = accountGate.state

    suspend fun rememberLoginEnabled(): Boolean = settings.rememberLogin.first()

    /**
     * 登录页打开时捕获，而不是在 WebView finish 时才读取。这样旧页面即使晚到
     * token，也属于旧登录上下文，不能借清理后的新代次写回。
     */
    internal fun captureLoginContext(): LibraryLoginContext? {
        val snapshot = accountGate.state.value
        if (snapshot.blocksPersonalSessions) return null
        return LibraryLoginContext(loginEpoch.get(), snapshot.generation)
    }

    internal fun isLoginContextCurrent(context: LibraryLoginContext): Boolean {
        val snapshot = accountGate.state.value
        return loginEpoch.get() == context.loginEpoch &&
            !snapshot.blocksPersonalSessions &&
            snapshot.generation == context.accountGeneration
    }

    /**
     * 写入登录结果与失效代次使用同一把锁，避免“检查代次后、写 token 前”被换号
     * 清理插入，导致旧 token 重新落盘。上下文同时绑定打开页面时的账号代次。
     */
    internal suspend fun persistLoginIfCurrent(
        context: LibraryLoginContext,
        session: LibrarySeatSession,
    ): Boolean = loginMutex.withLock {
        if (!isLoginContextCurrent(context)) return@withLock false
        if (rememberLoginEnabled()) {
            sessionStore.saveLibrary(session.token, session.user)
        }
        true
    }

    /**
     * 重启后恢复图书馆持久 token 必须走同一门闩；不能在账号清理中直接把旧 token
     * 交给 Repository 恢复。
     */
    internal suspend fun restoreSavedLibrarySession(): LibrarySeatSession? {
        val context = captureLoginContext() ?: return null
        val saved = sessionStore.loadLibrary() ?: return null
        if (!isLoginContextCurrent(context)) return null
        return try {
            repository.adoptSeatToken(saved.token) {
                isLoginContextCurrent(context)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 账号已进入清理或 token 本身/网络恢复失败：只清图书馆这一份。
            sessionStore.clearLibrary()
            repository.clearSession()
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
