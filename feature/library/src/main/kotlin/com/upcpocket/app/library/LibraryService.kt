package com.upcpocket.app.library

import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 图书馆插件对页面与设置区暴露的业务入口。
 *
 * 会话 token 仍由原来的内存 Repository 持有；开启“记住登录”时使用原有
 * [SchoolSessionStore] 加密副本。偏好读写与 Cookie 清理由宿主适配器提供，
 * 插件不直接依赖宿主的 AppGraph。
 */
class LibraryService(
    val repository: LibrarySeatRepository,
    val sessionStore: SchoolSessionStore,
    private val settings: LibraryAccountSettings,
    private val clearCookies: () -> Unit,
) {
    private val loginEpoch = AtomicLong(0)
    private val loginMutex = Mutex()

    val rememberLogin: Flow<Boolean> get() = settings.rememberLogin

    suspend fun rememberLoginEnabled(): Boolean = settings.rememberLogin.first()

    /** 登录页在发起交换前捕获；换号/删号会使旧登录不能写回。 */
    fun currentLoginEpoch(): Long = loginEpoch.get()

    /**
     * 写入登录结果与失效代次使用同一把锁，避免“检查代次后、写 token 前”被换号
     * 清理插入，导致旧 token 重新落盘。若清理先拿到锁，这里看到新代次并拒绝；
     * 若保存先拿到锁，清理随后会把它删掉。
     */
    suspend fun persistLoginIfCurrent(epoch: Long, session: LibrarySeatSession): Boolean =
        loginMutex.withLock {
            if (epoch != loginEpoch.get()) return@withLock false
            if (rememberLoginEnabled()) {
                sessionStore.saveLibrary(session.token, session.user)
            }
            true
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
     * 等待学校 Cookie 清理完成。失败时账号层保持清理中，避免旧身份被新登录带回。
     */
    suspend fun signOutForAccountChange(): Boolean {
        val persistedCleared = loginMutex.withLock {
            loginEpoch.incrementAndGet()
            sessionStore.clear().also { repository.clearSession() }
        }
        if (!persistedCleared) return false
        return SchoolWebCookies.clearAllSchoolCookiesAndAwait()
    }
}
