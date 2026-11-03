package com.upcpocket.app.library

import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
    val rememberLogin: Flow<Boolean> get() = settings.rememberLogin

    suspend fun rememberLoginEnabled(): Boolean = settings.rememberLogin.first()

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
        sessionStore.clear()
        repository.clearSession()
        clearCookies()
    }
}
