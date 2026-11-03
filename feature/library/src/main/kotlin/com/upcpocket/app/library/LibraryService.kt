package com.upcpocket.app.library

import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore

/**
 * 图书馆插件对页面暴露的业务入口。
 *
 * 会话 token 仍由原来的内存 Repository 持有；开启“记住登录”时使用原有
 * [SchoolSessionStore] 加密副本。rememberLogin 由宿主提供，插件不直接依赖
 * 宿主偏好存储。
 */
class LibraryService(
    val repository: LibrarySeatRepository,
    val sessionStore: SchoolSessionStore,
    private val rememberLogin: suspend () -> Boolean,
) {
    suspend fun rememberLoginEnabled(): Boolean = rememberLogin()
}
