package com.upcpocket.app.card

import kotlinx.coroutines.flow.Flow

/**
 * 校园卡学号输入的持久化边界。
 *
 * 由宿主提供实现（当前是 app 的 DataStore 适配器）；插件不直接认识 `UiPrefsStore`，
 * 也不把学号写进自己的快照文件。
 */
interface CardInputStore {
    val studentId: Flow<String>

    suspend fun save(studentId: String)

    suspend fun clear()
}
