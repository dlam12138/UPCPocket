package com.upcpocket.app.core

import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.library.LibraryAccountSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 把宿主的 DataStore 偏好适配成图书馆插件能读写的边界。
 *
 * 只暴露“记住登录”一个开关；DataStore 文件名与键名仍由 [UiPrefsStore] 维护，
 * 插件不会接触宿主的其它偏好。
 */
class HostLibraryAccountSettings(
    private val store: UiPrefsStore,
) : LibraryAccountSettings {

    override val rememberLogin: Flow<Boolean> = store.flow
        .map { it.rememberSchoolLogin }
        .distinctUntilChanged()

    override suspend fun setRememberLogin(enabled: Boolean) {
        store.setRememberSchoolLogin(enabled)
    }
}
