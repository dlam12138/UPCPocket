package com.upcpocket.app.library

import kotlinx.coroutines.flow.Flow

/**
 * 宿主提供给图书馆插件的账号偏好边界。
 *
 * 插件不依赖宿主的 UiPrefsStore；持久层的键名、DataStore 文件与写入顺序仍由宿主
 * 负责，插件只读取“记住登录”状态并在关闭时触发清理。
 */
interface LibraryAccountSettings {
    val rememberLogin: Flow<Boolean>

    suspend fun setRememberLogin(enabled: Boolean)
}
